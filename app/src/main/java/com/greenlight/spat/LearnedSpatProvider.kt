package com.greenlight.spat

import com.greenlight.data.GreenLightDb
import com.greenlight.learn.IntersectionGeometry
import com.greenlight.learn.IntersectionPriors
import com.greenlight.learn.TimingEstimator
import com.greenlight.model.FixedPlanSchedule
import com.greenlight.model.PlanBucket
import com.greenlight.model.SignalSchedule
import com.greenlight.model.TimingSource
import com.greenlight.model.TrafficSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Infers fixed-time plans from this phone's own history.
 *
 * Re-running the cycle sweep costs a few hundred circular-mean evaluations, so results are
 * memoised per (signal, approach octant, plan bucket) and only recomputed when new
 * observations land or the cache ages out.
 */
class LearnedSpatProvider(
    private val db: GreenLightDb,
    private val clock: TimeContext,
    private val cacheTtlSec: Double = 900.0,
) : SpatProvider {

    override val source = TimingSource.LEARNED

    private data class Key(val signalId: Long, val octant: Int, val bucket: PlanBucket)
    private data class Cached(val schedule: FixedPlanSchedule?, val builtAt: Double)

    private val cache = HashMap<Key, Cached>()
    private val mutex = Mutex()

    override fun covers(signal: TrafficSignal) = true

    override suspend fun scheduleFor(
        signal: TrafficSignal,
        approachBearing: Double,
        nowEpochSec: Double,
    ): SignalSchedule? = withContext(Dispatchers.IO) {
        val midnight = clock.localMidnightEpochSec(nowEpochSec)
        val bucket = PlanBucket.of(nowEpochSec - midnight, clock.isWeekend(nowEpochSec))
        val key = Key(signal.id, GreenLightDb.octantOf(approachBearing), bucket)

        mutex.withLock {
            cache[key]?.let { if (nowEpochSec - it.builtAt < cacheTtlSec) return@withContext it.schedule }
        }

        val observations = db.observationsFor(signal.id, bucket, key.octant)
        // Geometry narrows the cycle search and bounds the green duration before any
        // observation exists, which is what gets a junction to usable confidence sooner.
        val prior = IntersectionPriors.of(
            IntersectionGeometry(
                approaches = signal.approaches,
                totalLanes = signal.totalLanes,
                crossingMeters = signal.crossingMeters,
                speedLimitMps = signal.speedLimitMps,
            )
        )
        // Pull in this approach's passes from every other time-of-day plan too. The cycle
        // is usually shared even when offsets are not, so an evening commute can supply the
        // cycle that a thin morning sample cannot.
        val pooled = db.observationsForAnyBucket(signal.id, key.octant)
        val estimate = TimingEstimator.estimatePooled(observations, pooled, prior)
        val schedule = estimate?.let { TimingEstimator.toSchedule(it, midnight) }

        mutex.withLock { cache[key] = Cached(schedule, nowEpochSec) }
        schedule
    }

    /** Called when a fresh red-to-green lands, so the phase can be nudged without a full resweep. */
    suspend fun realign(signalId: Long, approachBearing: Double, greenStartEpochSec: Double) {
        val midnight = clock.localMidnightEpochSec(greenStartEpochSec)
        val bucket = PlanBucket.of(greenStartEpochSec - midnight, clock.isWeekend(greenStartEpochSec))
        val key = Key(signalId, GreenLightDb.octantOf(approachBearing), bucket)
        mutex.withLock {
            val cached = cache[key]?.schedule ?: return
            cache[key] = Cached(TimingEstimator.realign(cached, greenStartEpochSec), greenStartEpochSec)
        }
    }

    suspend fun invalidate(signalId: Long) = mutex.withLock {
        cache.keys.filter { it.signalId == signalId }.forEach { cache.remove(it) }
    }
}

/** Timezone-aware helpers, injected so the core stays testable without Android. */
interface TimeContext {
    fun localMidnightEpochSec(epochSec: Double): Double
    fun isWeekend(epochSec: Double): Boolean

    /** ISO day of week, 1 = Monday .. 7 = Sunday. */
    fun dayOfWeek(epochSec: Double): Int

    fun minuteOfDay(epochSec: Double): Double
}
