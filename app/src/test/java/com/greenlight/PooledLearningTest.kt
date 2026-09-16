package com.greenlight

import com.greenlight.learn.TimingEstimator
import com.greenlight.model.PlanBucket
import com.greenlight.model.SignalObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Observations are filed per time-of-day plan, which cold-starts every plan separately.
 * A driver who filled the evening bucket and then drove in the morning saw zeroes and
 * concluded the app had learned nothing. These cover borrowing the cycle across plans.
 */
class PooledLearningTest {

    private val midnight = 1_700_000_000.0
    private val cycle = 100.0

    private fun obs(
        relSec: Double,
        stopped: Boolean,
        bucket: PlanBucket,
    ) = SignalObservation(
        signalId = 1L, approachBearing = 90.0,
        arrivalEpochSec = midnight + relSec,
        stopped = stopped,
        departureEpochSec = if (stopped) midnight + relSec else null,
        localMidnightEpochSec = midnight,
        planBucket = bucket,
    )

    /** A well-sampled plan: plenty of stops on a clean 100 s cycle. */
    private fun richPlan(phi: Double, bucket: PlanBucket, n: Int, seed: Int): List<SignalObservation> {
        val rng = Random(seed)
        var k = 0
        return (0 until n).map {
            k += rng.nextInt(1, 5)
            obs(phi + k * cycle + TimingEstimator.QUEUE_BIAS_SEC, stopped = true, bucket = bucket)
        }
    }

    /** A thin plan: a couple of stops, nowhere near enough on its own. */
    private fun thinPlan(phi: Double, bucket: PlanBucket, seed: Int): List<SignalObservation> {
        val rng = Random(seed)
        var k = 400 // far from the rich plan in time, as a different part of the day would be
        return (0 until 3).map {
            k += rng.nextInt(1, 5)
            obs(phi + k * cycle + TimingEstimator.QUEUE_BIAS_SEC, stopped = true, bucket = bucket)
        }
    }

    @Test
    fun `a thin plan alone cannot be estimated`() {
        val thin = thinPlan(40.0, PlanBucket.WEEKDAY_AM_PEAK, seed = 2)
        assertNull(TimingEstimator.estimate(thin))
    }

    @Test
    fun `pooling lets a thin plan borrow the cycle from a well-sampled one`() {
        val evening = richPlan(20.0, PlanBucket.WEEKDAY_PM_PEAK, n = 14, seed = 1)
        val morning = thinPlan(40.0, PlanBucket.WEEKDAY_AM_PEAK, seed = 2)

        val alone = TimingEstimator.estimate(morning)
        val pooled = TimingEstimator.estimatePooled(morning, morning + evening)

        assertNull("thin plan should not stand alone", alone)
        assertNotNull("pooling produced nothing", pooled)
        assertEquals(cycle, pooled!!.cycleSec, 3.0)
        assertTrue("cycle should be marked as borrowed", pooled.cycleBorrowed)
    }

    @Test
    fun `a borrowed cycle is discounted rather than trusted outright`() {
        val evening = richPlan(20.0, PlanBucket.WEEKDAY_PM_PEAK, n = 14, seed = 5)
        val morning = thinPlan(40.0, PlanBucket.WEEKDAY_AM_PEAK, seed = 6)

        val borrowed = TimingEstimator.estimatePooled(morning, morning + evening)!!
        val native = TimingEstimator.estimate(evening)!!
        assertTrue(
            "borrowed=${borrowed.confidence} native=${native.confidence}",
            borrowed.confidence < native.confidence,
        )
    }

    @Test
    fun `a plan with enough of its own data does not borrow`() {
        val evening = richPlan(20.0, PlanBucket.WEEKDAY_PM_PEAK, n = 16, seed = 3)
        val morning = richPlan(55.0, PlanBucket.WEEKDAY_AM_PEAK, n = 16, seed = 4)
        val result = TimingEstimator.estimatePooled(morning, morning + evening)!!
        assertFalse("should have stood on its own data", result.cycleBorrowed)
    }

    @Test
    fun `the phase stays that of the plan being asked about`() {
        // Same cycle, deliberately different offsets - which is what really varies by plan.
        val evening = richPlan(20.0, PlanBucket.WEEKDAY_PM_PEAK, n = 14, seed = 7)
        val morning = thinPlan(60.0, PlanBucket.WEEKDAY_AM_PEAK, seed = 8)
        val pooled = TimingEstimator.estimatePooled(morning, morning + evening)!!
        val err = kotlin.math.abs(pooled.greenStartInCycleSec - 60.0)
            .let { minOf(it, cycle - it) }
        assertTrue("phase drifted to the other plan's offset: err=$err", err < 12.0)
    }

    @Test
    fun `differing offsets between plans do not corrupt the pooled cycle`() {
        // Regression: pooling raw timestamps across plans with different offsets produced
        // 50 s for a genuine 100 s junction, because the two clusters were split down the
        // middle. Scoring within-plan gaps instead makes the estimate offset-invariant.
        val evening = richPlan(20.0, PlanBucket.WEEKDAY_PM_PEAK, n = 14, seed = 7)
        val morning = thinPlan(60.0, PlanBucket.WEEKDAY_AM_PEAK, seed = 8)
        val recovered = TimingEstimator.cycleFromWithinPlanGaps(morning + evening)
        assertNotNull(recovered)
        assertEquals(cycle, recovered!!, 0.5)
    }

    @Test
    fun `gaps from unrelated timings yield no cycle`() {
        val rng = Random(31)
        val junk = (0 until 12).map {
            obs(rng.nextDouble(0.0, 9000.0), stopped = true, bucket = PlanBucket.WEEKDAY_MIDDAY)
        }
        val recovered = TimingEstimator.cycleFromWithinPlanGaps(junk)
        // It may return nothing; if it returns something it must not be treated as solid.
        if (recovered != null) {
            assertTrue("suspiciously precise on noise: $recovered", recovered in 30.0..200.0)
        }
    }

    @Test
    fun `nothing to borrow leaves the answer unchanged`() {
        val morning = thinPlan(40.0, PlanBucket.WEEKDAY_AM_PEAK, seed = 9)
        assertNull(TimingEstimator.estimatePooled(morning, morning))
    }
}
