package com.greenlight

import com.greenlight.learn.TimingEstimator
import com.greenlight.model.PlanBucket
import com.greenlight.model.SignalObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Green passes carry real information - the light was green at that instant - and ignoring
 * them threw away most of every drive. These cover that they are used, and that they are not
 * trusted beyond what they are worth.
 */
class GreenPassLearningTest {

    private val midnight = 1_700_000_000.0
    private val cycle = 90.0
    private val phi = 30.0
    private val green = 34.0

    private fun stop(relSec: Double) = SignalObservation(
        signalId = 1L, approachBearing = 90.0,
        arrivalEpochSec = midnight + relSec, stopped = true,
        departureEpochSec = midnight + relSec,
        localMidnightEpochSec = midnight, planBucket = PlanBucket.WEEKDAY_PM_PEAK,
    )

    private fun pass(relSec: Double) = SignalObservation(
        signalId = 1L, approachBearing = 90.0,
        arrivalEpochSec = midnight + relSec, stopped = false,
        departureEpochSec = null,
        localMidnightEpochSec = midnight, planBucket = PlanBucket.WEEKDAY_PM_PEAK,
    )

    /** A pass that genuinely happened while the light was green. */
    private fun greenPassAt(k: Int, fraction: Double) =
        pass(phi + k * cycle + fraction * green)

    @Test
    fun `green passes alone can recover the cycle`() {
        val rng = Random(21)
        var k = 0
        val observations = (0 until 14).map {
            k += rng.nextInt(1, 5)
            greenPassAt(k, rng.nextDouble(0.05, 0.95))
        }
        val est = TimingEstimator.estimate(observations)
        assertNotNull("green passes were ignored entirely", est)
        assertEquals(cycle, est!!.cycleSec, 3.0)
    }

    @Test
    fun `a cycle from green passes alone is not claimed with high confidence`() {
        val rng = Random(7)
        var k = 0
        val observations = (0 until 8).map {
            k += rng.nextInt(1, 5)
            greenPassAt(k, rng.nextDouble(0.05, 0.95))
        }
        val est = TimingEstimator.estimate(observations)
        // Each pass only pins the phase to a wide window, so the estimate must stay humble.
        if (est != null) assertTrue("confidence ${est.confidence}", est.confidence < 0.6)
    }

    @Test
    fun `mixing stops with passes beats stops alone`() {
        val rng = Random(11)
        var k = 0
        val stops = (0 until 3).map {
            k += rng.nextInt(1, 5)
            stop(phi + k * cycle + TimingEstimator.QUEUE_BIAS_SEC)
        }
        val passes = (0 until 12).map {
            k += rng.nextInt(1, 4)
            greenPassAt(k, rng.nextDouble(0.05, 0.95))
        }
        val stopsOnly = TimingEstimator.estimate(stops)
        val combined = TimingEstimator.estimate(stops + passes)
        assertNotNull(combined)
        assertEquals(cycle, combined!!.cycleSec, 3.0)
        if (stopsOnly != null) {
            assertTrue(
                "stopsOnly=${stopsOnly.confidence} combined=${combined.confidence}",
                combined.confidence > stopsOnly.confidence,
            )
        }
    }

    @Test
    fun `random pass times do not produce a confident cycle`() {
        val rng = Random(5)
        val observations = (0 until 14).map { pass(rng.nextDouble(0.0, 6000.0)) }
        val est = TimingEstimator.estimate(observations)
        if (est != null) assertTrue("confidence ${est.confidence}", est.confidence < 0.4)
    }

    @Test
    fun `one stop and two passes is still not enough to say anything`() {
        // Exactly the state after a single drive through an unfamiliar junction.
        val observations = listOf(stop(phi + 2.2), pass(phi + 90 + 10), pass(phi + 270 + 20))
        assertNull(TimingEstimator.estimate(observations))
    }

    @Test
    fun `progress reporting matches what the estimator will accept`() {
        val thin = listOf(stop(phi + 2.2), pass(phi + 100.0))
        assertTrue(TimingEstimator.samplesStillNeeded(thin) > 0)
        assertNull(TimingEstimator.estimate(thin))

        val rng = Random(3)
        var k = 0
        val enough = (0 until 16).map {
            k += rng.nextInt(1, 5)
            greenPassAt(k, rng.nextDouble(0.05, 0.95))
        }
        assertEquals(0, TimingEstimator.samplesStillNeeded(enough))
        assertNotNull(TimingEstimator.estimate(enough))
    }

    @Test
    fun `the state after one real drive needs more and says so`() {
        // Reproduces the observed diagnostics: ten passes spread over eight junctions, two
        // stops, so no single approach has anything close to enough.
        val oneJunction = listOf(stop(phi + 2.2))
        assertTrue(TimingEstimator.samplesStillNeeded(oneJunction) >= 1)
        assertNull(TimingEstimator.estimate(oneJunction))
    }
}
