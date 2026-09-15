package com.greenlight.nav

import android.content.Context
import com.greenlight.core.LatLon
import com.greenlight.core.boundingBox
import com.greenlight.core.haversineMeters
import com.greenlight.data.DebugLog
import com.greenlight.data.Prefs
import com.greenlight.data.GreenLightDb
import com.greenlight.data.Route
import com.greenlight.data.fetchSignals
import com.greenlight.data.route
import com.greenlight.learn.DestinationPrediction
import com.greenlight.learn.DestinationPredictor
import com.greenlight.learn.ObservationDetector
import com.greenlight.learn.TripTracker
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
import kotlinx.coroutines.flow.update
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
    /** Ranked guesses at where this trip is heading, best first. */
    val predictions: List<DestinationPrediction> = emptyList(),
    /** True when the route currently loaded came from a prediction, not from the user. */
    val destinationWasPredicted: Boolean = false,
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

    private val prefs = Prefs(context)

    /**
     * Applied before anything else sees the fix. Raw GNSS speed does not fall to zero at
     * rest, which previously stopped the observation detector ever registering a halt.
     */
    private val speedFilter = SpeedFilter()

    private val tripTracker = TripTracker()
    private val predictor = DestinationPredictor()

    /** Where the current trip began, so the predictor can condition on it. */
    private var originPlaceId: Long? = null
    private var lastPredictionAt = 0.0
    private var autoRoutedTo: Long? = null

    /** Above this probability the engine quietly routes itself, no typing required. */
    private val autoRouteThreshold = 0.55

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
    private var lastAttemptAt = 0.0
    @Volatile
    private var fetching = false

    /** Cache radius. Big enough that normal driving rarely leaves it mid-trip. */
    private val fetchRadiusMeters = 2_500.0
    private val refetchAfterMeters = 1_400.0
    private val refetchAfterSec = 1_800.0

    /** Minimum gap between network attempts, so an outage does not drain the battery. */
    private val attemptBackoffSec = 30.0

    suspend fun setDestination(destination: LatLon, label: String, from: LatLon): Boolean {
        val r = runCatching { route(from, destination) }.getOrNull()
        if (r == null) {
            _status.update { it.copy(message = "Could not fetch a route") }
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
                fetched.map { TrafficSignal(
                        id = it.id,
                        position = it.position,
                        speedLimitMps = it.maxspeedMps,
                        name = it.name,
                        approaches = it.approaches,
                        totalLanes = it.totalLanes,
                        crossingMeters = it.crossingMeters,
                    ) },
                System.currentTimeMillis() / 1000.0,
            )
        }
        val signals = db.signalsInBox(box[0], box[1], box[2], box[3])
        mutex.withLock {
            routeIndex = RouteSignalIndex(r.geometry, signals)
            nearbySignals = signals
        }
        _status.update { it.copy(
            mode = EngineStatus.Mode.ROUTE,
            destinationLabel = label,
            routeDistanceMeters = r.distanceMeters,
            signalsKnownNearby = routeIndex?.signalCount ?: 0,
            message = "Route ready: ${routeIndex?.signalCount ?: 0} signals on it",
        ) }
    }

    suspend fun clearDestination() {
        mutex.withLock { routeIndex = null }
        autoRoutedTo = null
        _status.update { it.copy(
            mode = EngineStatus.Mode.FREE_DRIVE,
            destinationLabel = null,
            routeDistanceMeters = null,
            destinationWasPredicted = false,
            message = "Free drive",
        ) }
    }

    /** Main entry point: call once per GPS fix. */
    suspend fun onFix(rawFix: Fix) {
        val fix = speedFilter.filter(rawFix)
        if (speedFilter.isStationary && rawFix.speedMps > 2.0) {
            DebugLog.throttled("stationary", 60.0, "gps") {
                "held stationary despite reported %.1f km/h".format(rawFix.speedMps * 3.6)
            }
        }
        ensureSignalCache(fix)

        val candidates = mutex.withLock { nearbySignals }

        // Learn from this pass regardless of whether we are advising on it.
        val observations = detector.onFix(fix, candidates.filter {
            haversineMeters(fix.position, it.position) < 120.0
        })
        for (o in observations) {
            DebugLog.log(
                "pass",
                "signal=${o.signalId} stopped=${o.stopped} " +
                    "bearing=${o.approachBearing.toInt()} plan=${o.planBucket}",
            )
            db.insertObservation(o)
            learned.invalidate(o.signalId)
            o.departureEpochSec?.let { learned.realign(o.signalId, o.approachBearing, it) }
        }
        if (observations.isNotEmpty()) refreshCounts()

        tripTracker.onFix(fix)?.let { trip -> recordTrip(trip) }
        maybePredict(fix)

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

        DebugLog.throttled("upcoming", 20.0, "nav") {
            "candidates=${candidates.size} ahead=${upcoming.size} " +
                "mode=${if (routeIndex != null) "route" else "free"} " +
                "speed=%.0f km/h".format(fix.speedMps * 3.6)
        }

        if (upcoming.isEmpty()) {
            _advice.value = GlosaAdvice.noAdvice(
                note = if (fix.speedMps < 1.0) "Stopped" else "No signal ahead",
                speedLimitMps = prefs.defaultSpeedLimitMps,
            ).copy(currentForDisplay = fix.speedMps)
            _status.update { it.copy(lastFix = fix) }
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
                note = "Learning this signal",
                speedLimitMps = nearest.signal.speedLimitMps ?: prefs.defaultSpeedLimitMps,
                distanceMeters = nearest.distanceMeters,
                signal = nearest.signal,
            ).copy(currentForDisplay = fix.speedMps)
            _status.update { it.copy(lastFix = fix) }
            return
        }

        val speedLimit = upcoming.firstOrNull()?.signal?.speedLimitMps
            ?: prefs.defaultSpeedLimitMps
        _advice.value = GlosaSolver.solve(
            targets = targets,
            nowEpochSec = fix.epochSec,
            currentMps = fix.speedMps,
            speedLimitMps = speedLimit,
            cfg = config,
        ).copy(currentForDisplay = fix.speedMps)
        _status.update { it.copy(lastFix = fix) }
    }

    /**
     * Keeps a local cache of signal geometry around the vehicle.
     *
     * Two separate clocks matter here. [lastFetchCentre] tracks where the cache is valid, and
     * is updated whether the data came from SQLite or the network — without that, a cache hit
     * still looks like a miss and we would re-query on every single fix. [lastAttemptAt]
     * throttles network retries, so an Overpass outage costs one request every 30 s rather
     * than one per second for the whole drive.
     */
    private suspend fun ensureSignalCache(fix: Fix) {
        val centre = mutex.withLock { lastFetchCentre }
        val movedFar = centre == null || haversineMeters(centre, fix.position) > refetchAfterMeters
        val stale = fix.epochSec - lastFetchAt > refetchAfterSec
        if (!movedFar && !stale) return
        if (fetching) return
        if (fix.epochSec - lastAttemptAt < attemptBackoffSec) return
        lastAttemptAt = fix.epochSec

        // Serve from the database immediately; refresh from the network behind it.
        val box = boundingBox(listOf(fix.position), fetchRadiusMeters)
        val cached = db.signalsInBox(box[0], box[1], box[2], box[3])
        if (cached.isNotEmpty()) {
            mutex.withLock {
                nearbySignals = cached
                // Claim the cache as valid here, so a good cache stops the per-fix churn
                // even when the network never comes back.
                if (!stale) lastFetchCentre = fix.position
            }
            _status.update { it.copy(signalsKnownNearby = cached.size) }
        }

        fetching = true
        scope.launch {
            try {
                DebugLog.log("fetch", "requesting signals for %.4f,%.4f".format(
                    fix.position.lat, fix.position.lon))
                val fetched = fetchSignals(box)
                db.upsertSignals(
                    fetched.map { TrafficSignal(
                        id = it.id,
                        position = it.position,
                        speedLimitMps = it.maxspeedMps,
                        name = it.name,
                        approaches = it.approaches,
                        totalLanes = it.totalLanes,
                        crossingMeters = it.crossingMeters,
                    ) },
                    fix.epochSec,
                )
                val fresh = db.signalsInBox(box[0], box[1], box[2], box[3])
                mutex.withLock {
                    nearbySignals = fresh
                    lastFetchCentre = fix.position
                }
                lastFetchAt = fix.epochSec
                _status.update {
                    it.copy(
                        signalsKnownNearby = fresh.size,
                        message = "${fresh.size} signals cached nearby",
                    )
                }
            } catch (e: Exception) {
                DebugLog.log("fetch", "FAILED: ${e.javaClass.simpleName}: ${e.message}")
                _status.update {
                    it.copy(
                        message = if (cached.isEmpty()) {
                            "No signal data yet: ${e.message}"
                        } else {
                            "Using cached signals (fetch failed)"
                        }
                    )
                }
            } finally {
                fetching = false
            }
        }
    }

    /** Turns a finished journey into a place visit the predictor can learn from. */
    private fun recordTrip(trip: com.greenlight.learn.Trip) {
        val placeId = db.upsertPlace(
            trip.destination.lat, trip.destination.lon,
            radiusMeters = 140.0, nowEpoch = trip.arrivalEpochSec,
        )
        db.insertVisit(
            placeId = placeId,
            arrivalEpoch = trip.arrivalEpochSec,
            dayOfWeek = clock.dayOfWeek(trip.arrivalEpochSec),
            minuteOfDay = clock.minuteOfDay(trip.arrivalEpochSec),
            originPlaceId = originPlaceId,
        )
        DebugLog.log(
            "trip",
            "arrived place=$placeId after %.1f km, origin=%s".format(
                trip.distanceMeters / 1000.0, originPlaceId?.toString() ?: "unknown",
            ),
        )
        // The place we just arrived at is the origin of whatever trip comes next.
        originPlaceId = placeId
        autoRoutedTo = null
    }

    /**
     * Re-ranks likely destinations every so often, and routes to the leader when it is
     * confident enough. A known route is what upgrades the advice from "next light" to a
     * green wave across a corridor, so guessing correctly is worth real accuracy.
     */
    private suspend fun maybePredict(fix: Fix) {
        if (fix.epochSec - lastPredictionAt < 30.0) return
        lastPredictionAt = fix.epochSec

        val predictions = predictor.predict(
            db = db,
            nowEpochSec = fix.epochSec,
            dayOfWeek = clock.dayOfWeek(fix.epochSec),
            minuteOfDay = clock.minuteOfDay(fix.epochSec),
            originPlaceId = originPlaceId,
            currentPosition = fix.position,
        )
        _status.update { it.copy(predictions = predictions) }

        val top = predictions.firstOrNull() ?: return
        val userSetDestination = routeIndex != null && !_status.value.destinationWasPredicted
        if (userSetDestination) return
        if (top.probability < autoRouteThreshold) return
        if (autoRoutedTo == top.placeId) return
        if (fix.speedMps < 4.0) return

        autoRoutedTo = top.placeId
        DebugLog.log(
            "predict",
            "auto-routing to ${top.label} p=%.2f (%s)".format(top.probability, top.because),
        )
        val ok = setDestination(top.position, top.label, fix.position)
        if (ok) _status.update { it.copy(destinationWasPredicted = true) }
    }

    /** Names a place, so the prediction list reads like a life rather than coordinates. */
    fun renamePlace(placeId: Long, label: String) = db.renamePlace(placeId, label)

    fun forgetPlace(placeId: Long) {
        db.deletePlace(placeId)
        if (autoRoutedTo == placeId) autoRoutedTo = null
    }

    fun refreshCounts() {
        _status.update { it.copy(
            observationsRecorded = db.observationCount(),
            learnedSignals = db.learnedSignalCount(),
        ) }
    }

    fun setRunning(running: Boolean) {
        DebugLog.log("engine", if (running) "running" else "halted")
        _status.update { it.copy(running = running) }
        if (!running) {
            tripTracker.flush(System.currentTimeMillis() / 1000.0)?.let { runCatching { recordTrip(it) } }
            tripTracker.reset()
            speedFilter.reset()
            detector.reset()
            _advice.value = GlosaAdvice.noAdvice("Stopped")
        }
    }
}
