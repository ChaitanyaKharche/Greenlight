package com.greenlight.nav

import android.content.Context
import com.greenlight.core.LatLon
import com.greenlight.core.boundingBox
import com.greenlight.core.haversineMeters
import com.greenlight.data.GreenLightDb
import com.greenlight.data.Route
import com.greenlight.data.fetchSignals
import com.greenlight.data.route
import com.greenlight.learn.ObservationDetector
import com.greenlight.glosa.GlosaConfig
import com.greenlight.glosa.GlosaSolver
import com.greenlight.glosa.SolverTarget
import com.greenlight.model.Fix
import com.greenlight.model.GlosaAdvice
import com.greenlight.model.TrafficSignal
import com.greenlight.spat.LearnedSpatProvider
import com.greenlight.spat.ManualSpatProvider
import com.greenlight.spat.RestSpatProvider
import com.greenlight.spat.SpatFusion
import com.greenlight.spat.SpatProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the engine is currently doing, for the UI to show without guessing. */
data class EngineStatus(
    val running: Boolean = false,
    val mode: Mode = Mode.FREE_DRIVE,
    val destinationLabel: String? = null,
    val signalsKnownNearby: Int = 0,
    val observationsRecorded: Int = 0,
    val learnedSignals: Int = 0,
    val lastFix: Fix? = null,
    val routeDistanceMeters: Double? = null,
    val message: String? = null,
) {
    enum class Mode { FREE_DRIVE, ROUTE }
}

/**
 * The orchestrator.
 *
 * Per fix it does four things:
 *  1. feeds the observation detector, so every trip teaches the learner something
 *  2. works out which signals are ahead (on-route if we have one, cone-ahead if not)
 *  3. asks the fusion layer for the best schedule it can get for each
 *  4. runs the corridor solve and publishes one advisory
 *
 * Signal geometry is fetched from Overpass and cached in SQLite, so a repeated commute makes
 * no network calls at all after the first run.
 */
class GlosaEngine(
    private val context: Context,
    private val db: GreenLightDb,
    private val scope: CoroutineScope,
    private val config: GlosaConfig = GlosaConfig(),
) {
    private val clock = AndroidTimeContext()
    private val learned = LearnedSpatProvider(db, clock)
    private val manual = ManualSpatProvider(db, clock)

    /** Populated only when the user configures a real feed in settings. */
    var liveProvider: RestSpatProvider? = null
        set(value) {
            field = value
            fusion = SpatFusion(listOfNotNull(learned, manual, value as SpatProvider?))
        }

    private var fusion = SpatFusion(listOf(learned, manual))

    private val detector = ObservationDetector(
        localMidnightProvider = clock::localMidnightEpochSec,
        isWeekendProvider = clock::isWeekend,
    )

    private val _advice = MutableStateFlow(GlosaAdvice.noAdvice("Waiting for GPS"))
    val advice: StateFlow<GlosaAdvice> = _advice.asStateFlow()

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val mutex = Mutex()
    private var routeIndex: RouteSignalIndex? = null
    private var nearbySignals: List<TrafficSignal> = emptyList()
    private var lastFetchCentre: LatLon? = null
    private var lastFetchAt = 0.0
    private var fetching = false

    /** Cache radius. Big enough that normal driving rarely leaves it mid-trip. */
    private val fetchRadiusMeters = 2_500.0
    private val refetchAfterMeters = 1_400.0
    private val refetchAfterSec = 1_800.0

    suspend fun setDestination(destination: LatLon, label: String, from: LatLon): Boolean {
        val r = runCatching { route(from, destination) }.getOrNull()
        if (r == null) {
            _status.value = _status.value.copy(message = "Could not fetch a route")
            return false
        }
        buildRouteIndex(r, label)
        return true
    }

    private suspend fun buildRouteIndex(r: Route, label: String) {
        val box = boundingBox(r.geometry, padMeters = 150.0)
        val fetched = runCatching { fetchSignals(box) }.getOrNull()
        if (fetched != null) {
            db.upsertSignals(
                fetched.map { TrafficSignal(it.id, it.position, null, it.maxspeedMps, it.name) },
                System.currentTimeMillis() / 1000.0,
            )
        }
        val signals = db.signalsInBox(box[0], box[1], box[2], box[3])
        mutex.withLock {
            routeIndex = RouteSignalIndex(r.geometry, signals)
            nearbySignals = signals
        }
        _status.value = _status.value.copy(
            mode = EngineStatus.Mode.ROUTE,
            destinationLabel = label,
            routeDistanceMeters = r.distanceMeters,
            signalsKnownNearby = routeIndex?.signalCount ?: 0,
            message = "Route ready: ${routeIndex?.signalCount ?: 0} signals on it",
        )
    }

    suspend fun clearDestination() {
        mutex.withLock { routeIndex = null }
        _status.value = _status.value.copy(
            mode = EngineStatus.Mode.FREE_DRIVE,
            destinationLabel = null,
            routeDistanceMeters = null,
            message = "Free drive",
        )
    }

    /** Main entry point: call once per GPS fix. */
    suspend fun onFix(fix: Fix) {
        ensureSignalCache(fix)

        val candidates = mutex.withLock { nearbySignals }

        // Learn from this pass regardless of whether we are advising on it.
        val observations = detector.onFix(fix, candidates.filter {
            haversineMeters(fix.position, it.position) < 120.0
        })
        for (o in observations) {
            db.insertObservation(o)
            learned.invalidate(o.signalId)
            o.departureEpochSec?.let { learned.realign(o.signalId, o.approachBearing, it) }
        }
        if (observations.isNotEmpty()) refreshCounts()

        liveProvider?.refresh(fix.position, fix.epochSec)

        val upcoming = mutex.withLock {
            val index = routeIndex
            if (index != null) {
                val progress = index.progressMeters(fix.position)
                if (progress != null) {
                    index.ahead(progress, config.corridorSignals, config.maxRangeMeters)
                } else {
                    // Off route - fall back rather than advising from a stale projection.
                    FreeDriveSignals.ahead(fix, candidates, config.corridorSignals, config.maxRangeMeters)
                }
            } else {
                FreeDriveSignals.ahead(fix, candidates, config.corridorSignals, config.maxRangeMeters)
            }
        }

        if (upcoming.isEmpty()) {
            _advice.value = GlosaAdvice.noAdvice(
                if (fix.speedMps < 3.0) "Waiting until moving" else "No signal ahead"
            )
            _status.value = _status.value.copy(lastFix = fix)
            return
        }

        val targets = upcoming.mapNotNull { u ->
            val schedule = fusion.scheduleFor(u.signal, u.approachBearing, fix.epochSec)
                ?: return@mapNotNull null
            SolverTarget(u.signal, u.distanceMeters, schedule)
        }

        if (targets.isEmpty()) {
            val nearest = upcoming.first()
            _advice.value = GlosaAdvice.noAdvice(
                "Learning this signal - %.0f m ahead".format(nearest.distanceMeters)
            )
            _status.value = _status.value.copy(lastFix = fix)
            return
        }

        val speedLimit = upcoming.firstOrNull()?.signal?.speedLimitMps
        _advice.value = GlosaSolver.solve(
            targets = targets,
            nowEpochSec = fix.epochSec,
            currentMps = fix.speedMps,
            speedLimitMps = speedLimit,
            cfg = config,
        )
        _status.value = _status.value.copy(lastFix = fix)
    }

    /**
     * Keeps a local cache of signal geometry around the vehicle. Overpass is community
     * infrastructure with a fair-use policy, so we refetch on distance travelled, not on a timer.
     */
    private suspend fun ensureSignalCache(fix: Fix) {
        val centre = mutex.withLock { lastFetchCentre }
        val movedFar = centre == null || haversineMeters(centre, fix.position) > refetchAfterMeters
        val stale = fix.epochSec - lastFetchAt > refetchAfterSec
        if (!movedFar && !stale) return
        if (fetching) return

        // Serve from the database immediately; refresh from the network behind it.
        val box = boundingBox(listOf(fix.position), fetchRadiusMeters)
        val cached = db.signalsInBox(box[0], box[1], box[2], box[3])
        if (cached.isNotEmpty()) {
            mutex.withLock { nearbySignals = cached }
            _status.value = _status.value.copy(signalsKnownNearby = cached.size)
        }

        fetching = true
        lastFetchAt = fix.epochSec
        scope.launch {
            try {
                val fetched = fetchSignals(box)
                db.upsertSignals(
                    fetched.map { TrafficSignal(it.id, it.position, null, it.maxspeedMps, it.name) },
                    fix.epochSec,
                )
                val fresh = db.signalsInBox(box[0], box[1], box[2], box[3])
                mutex.withLock {
                    nearbySignals = fresh
                    lastFetchCentre = fix.position
                }
                _status.value = _status.value.copy(
                    signalsKnownNearby = fresh.size,
                    message = "${fresh.size} signals cached nearby",
                )
            } catch (e: Exception) {
                _status.value = _status.value.copy(message = "Signal fetch failed: ${e.message}")
            } finally {
                fetching = false
            }
        }
    }

    fun refreshCounts() {
        _status.value = _status.value.copy(
            observationsRecorded = db.observationCount(),
            learnedSignals = db.learnedSignalCount(),
        )
    }

    fun setRunning(running: Boolean) {
        _status.value = _status.value.copy(running = running)
        if (!running) {
            detector.reset()
            _advice.value = GlosaAdvice.noAdvice("Stopped")
        }
    }
}
