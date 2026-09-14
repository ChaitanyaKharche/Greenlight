package com.greenlight

import com.greenlight.learn.TimingEstimator
import com.greenlight.model.PlanBucket
import com.greenlight.model.SignalObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class TimingEstimatorTest {

    private fun obs(
        departureRelMidnight: Double?,
        arrivalRelMidnight: Double,
        stopped: Boolean,
        midnight: Double = 1_700_000_000.0,
    ) = SignalObservation(
        signalId = 1L,
        approachBearing = 90.0,
        arrivalEpochSec = midnight + arrivalRelMidnight,
        stopped = stopped,
        departureEpochSec = departureRelMidnight?.let { midnight + it },
        localMidnightEpochSec = midnight,
        planBucket = PlanBucket.WEEKDAY_MIDDAY,
    )

    @Test
    fun `recovers a clean 90 second cycle`() {
        val cycle = 90.0
        val phi = 37.0
        val rng = Random(7)
        val observations = (0 until 14).map { i ->
            val k = i * 11 + rng.nextInt(0, 3)
            val g = phi + k * cycle + TimingEstimator.QUEUE_BIAS_SEC
            obs(g, g, stopped = true)
        }
        val est = TimingEstimator.estimate(observations)
        assertNotNull(est)
        assertEquals(cycle, est!!.cycleSec, 0.5)
        assertTrue("R was ${est.resultantLength}", est.resultantLength > 0.95)
        // Phase should land near phi once the queue bias is removed.
        val err = abs(est.greenStartInCycleSec - phi).let { minOf(it, cycle - it) }
        assertTrue("phase error $err", err < 4.0)
    }

    @Test
    fun `survives a couple of seconds of queue jitter`() {
        val cycle = 120.0
        val phi = 64.0
        val rng = Random(11)
        val observations = (0 until 20).map { i ->
            val k = i * 7 + rng.nextInt(0, 4)
            val jitter = rng.nextDouble(0.0, 4.0) // late departures only, like a real queue
            val g = phi + k * cycle + TimingEstimator.QUEUE_BIAS_SEC + jitter
            obs(g, g, stopped = true)
        }
        val est = TimingEstimator.estimate(observations)
        assertNotNull(est)
        assertEquals(cycle, est!!.cycleSec, 1.0)
        assertTrue(est.confidence > 0.5)
    }

    @Test
    fun `picks the true cycle rather than a sub-multiple`() {
        // C/2, C/3 ... fit the data perfectly too, so the estimator must prefer the
        // largest well-scoring candidate, not the first one it finds.
        val cycle = 100.0
        val phi = 10.0
        val rng = Random(5)
        var k = 0
        val observations = (0 until 16).map {
            k += rng.nextInt(1, 5) // varied gaps, so gcd of the differences really is C
            val g = phi + k * cycle + TimingEstimator.QUEUE_BIAS_SEC
            obs(g, g, stopped = true)
        }
        val est = TimingEstimator.estimate(observations)!!
        assertEquals(100.0, est.cycleSec, 0.5)
    }

    @Test
    fun `significance collapses when the peak could be luck`() {
        // A tight peak over few samples and a short span is not evidence.
        val weak = TimingEstimator.significanceOf(r = 0.65, n = 15, span = 5000.0, maxCycle = 200.0)
        val strong = TimingEstimator.significanceOf(r = 0.99, n = 15, span = 5000.0, maxCycle = 200.0)
        assertTrue("weak=$weak", weak < 0.85)
        assertTrue("strong=$strong", strong > 0.99)
    }

    @Test
    fun `refuses to guess from too few samples`() {
        val observations = (0 until 3).map { i ->
            val g = 20.0 + i * 90.0
            obs(g, g, stopped = true)
        }
        assertNull(TimingEstimator.estimate(observations))
    }

    @Test
    fun `random noise yields low confidence`() {
        val rng = Random(3)
        val observations = (0 until 15).map {
            val g = rng.nextDouble(0.0, 5000.0)
            obs(g, g, stopped = true)
        }
        val est = TimingEstimator.estimate(observations)
        // It may still return something, but it must not claim to be sure.
        if (est != null) assertTrue("confidence ${est.confidence}", est.confidence < 0.55)
    }

    @Test
    fun `schedule windows line up with the estimate`() {
        val cycle = 80.0
        val phi = 25.0
        val observations = (0 until 12).map { i ->
            val g = phi + (i * 5) * cycle + TimingEstimator.QUEUE_BIAS_SEC
            obs(g, g, stopped = true)
        }
        val est = TimingEstimator.estimate(observations)!!
        val midnight = 1_700_000_000.0
        val schedule = TimingEstimator.toSchedule(est, midnight)
        val windows = schedule.greenWindows(midnight + 1000.0, 200.0)
        assertTrue(windows.isNotEmpty())
        windows.zipWithNext().forEach { (a, b) ->
            assertEquals(cycle, b.startEpochSec - a.startEpochSec, 0.6)
        }
    }
}
