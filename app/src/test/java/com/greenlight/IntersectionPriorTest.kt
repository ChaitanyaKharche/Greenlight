package com.greenlight

import com.greenlight.learn.IntersectionGeometry
import com.greenlight.learn.IntersectionPriors
import com.greenlight.learn.TimingEstimator
import com.greenlight.model.PlanBucket
import com.greenlight.model.SignalObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class IntersectionPriorTest {

    @Test
    fun `yellow follows the ITE kinematic formula`() {
        // y = 1.0 + v / (2 * 3.05)
        assertEquals(1.0 + 13.9 / 6.1, IntersectionPriors.yellowInterval(13.9), 1e-6)
        // 40 km/h would give 2.8 s, but the MUTCD floor is 3 s.
        assertEquals(3.0, IntersectionPriors.yellowInterval(11.1), 1e-9)
        // Very fast approaches are capped at 6 s.
        assertEquals(6.0, IntersectionPriors.yellowInterval(45.0), 1e-9)
    }

    @Test
    fun `all-red scales with the box a car must clear`() {
        val narrow = IntersectionPriors.allRedClearance(10.0, 13.9)
        val wide = IntersectionPriors.allRedClearance(30.0, 13.9)
        assertTrue(wide > narrow)
        assertEquals((10.0 + 6.0) / 13.9, narrow, 1e-6)
    }

    @Test
    fun `pedestrian clearance sets a hard floor that grows with width`() {
        // MUTCD: 7 s walk + width / 1.1 m/s, plus change intervals.
        val narrow = IntersectionPriors.minimumPhaseSec(8.0, 13.9)
        val wide = IntersectionPriors.minimumPhaseSec(24.0, 13.9)
        assertTrue("narrow=$narrow", narrow > 7.0 + 8.0 / 1.1)
        assertTrue("wide=$wide", wide > 7.0 + 24.0 / 1.1)
        assertTrue(wide - narrow > 14.0)
    }

    @Test
    fun `wide four-leg junctions are assumed to run protected lefts`() {
        // Two lanes per approach: a simple crossroads, four phases.
        assertEquals(4, IntersectionPriors.estimatePhases(approaches = 4, totalLanes = 8))
        // Three lanes per approach implies turn pockets, so eight phases.
        assertEquals(8, IntersectionPriors.estimatePhases(approaches = 4, totalLanes = 12))
        assertEquals(3, IntersectionPriors.estimatePhases(approaches = 3, totalLanes = 6))
        assertEquals(2, IntersectionPriors.estimatePhases(approaches = 2, totalLanes = 4))
    }

    @Test
    fun `webster cycle grows with phase count`() {
        val two = IntersectionPriors.websterCycle(2, 0.65)
        val four = IntersectionPriors.websterCycle(4, 0.65)
        val eight = IntersectionPriors.websterCycle(8, 0.65)
        assertTrue(two < four)
        assertTrue(four < eight)
    }

    @Test
    fun `webster lands close to a real 90 second arterial cycle`() {
        // Four legs, two lanes per approach, 18 m crossing, 54 km/h: a textbook suburban
        // arterial crossroads. Webster puts it at 90 s, which is what such junctions run.
        val prior = IntersectionPriors.of(
            IntersectionGeometry(approaches = 4, totalLanes = 8, crossingMeters = 18.0, speedLimitMps = 15.0)
        )!!
        assertEquals(90.0, prior.likelyCycleSec, 8.0)
        assertTrue(90.0 in prior.minCycleSec..prior.maxCycleSec)
    }

    @Test
    fun `a residential T gets a shorter prior than a wide arterial crossroads`() {
        val small = IntersectionPriors.of(
            IntersectionGeometry(approaches = 3, totalLanes = 4, crossingMeters = 8.0, speedLimitMps = 11.1)
        )!!
        val big = IntersectionPriors.of(
            IntersectionGeometry(approaches = 4, totalLanes = 14, crossingMeters = 24.0, speedLimitMps = 18.0)
        )!!
        assertTrue("small=${small.describe()} big=${big.describe()}",
            small.likelyCycleSec < big.likelyCycleSec)
        assertTrue(small.phases < big.phases)
        // Every prior must stay inside the range the estimator will sweep.
        listOf(small, big).forEach {
            assertTrue(it.minCycleSec >= 30.0)
            assertTrue(it.maxCycleSec <= 200.0)
            assertTrue(it.minCycleSec < it.maxCycleSec)
            assertTrue(it.minGreenSec <= it.maxGreenSec)
            assertTrue(it.likelyCycleSec in it.minCycleSec..it.maxCycleSec)
        }
    }

    @Test
    fun `geometry we do not have yields no prior rather than a fabricated one`() {
        assertNull(IntersectionPriors.of(
            IntersectionGeometry(approaches = 0, totalLanes = 0, crossingMeters = 0.0, speedLimitMps = null)
        ))
    }

    private fun observations(cycle: Double, phi: Double, n: Int, seed: Int) =
        Random(seed).let { rng ->
            var k = 0
            (0 until n).map {
                k += rng.nextInt(1, 5)
                val g = phi + k * cycle + TimingEstimator.QUEUE_BIAS_SEC
                SignalObservation(
                    signalId = 1L, approachBearing = 90.0,
                    arrivalEpochSec = 1_700_000_000.0 + g, stopped = true,
                    departureEpochSec = 1_700_000_000.0 + g,
                    localMidnightEpochSec = 1_700_000_000.0,
                    planBucket = PlanBucket.WEEKDAY_MIDDAY,
                )
            }
        }

    @Test
    fun `a prior raises confidence for the same observations`() {
        val obs = observations(cycle = 90.0, phi = 30.0, n = 6, seed = 4)
        val blind = TimingEstimator.estimate(obs)
        val prior = IntersectionPriors.of(
            IntersectionGeometry(approaches = 4, totalLanes = 8, crossingMeters = 18.0, speedLimitMps = 15.0)
        )
        val guided = TimingEstimator.estimate(obs, prior)
        assertNotNull(blind)
        assertNotNull(guided)
        // Six passes is thin, so the estimate is checked against the uncertainty it
        // reports rather than against an arbitrary tolerance.
        assertEquals(90.0, guided!!.cycleSec, guided.sigmaSec)
        assertTrue(
            "blind=${blind!!.confidence} guided=${guided.confidence}",
            guided.confidence > blind.confidence,
        )
    }

    @Test
    fun `narrowing the search shrinks the multiple-comparisons penalty`() {
        val wide = TimingEstimator.significanceOf(0.9, 8, 10_000.0, 30.0, 200.0)
        val narrow = TimingEstimator.significanceOf(0.9, 8, 10_000.0, 70.0, 110.0)
        assertTrue("wide=$wide narrow=$narrow", narrow > wide)
    }

    @Test
    fun `the prior never lets the estimator report an impossible cycle`() {
        val prior = IntersectionPriors.of(
            IntersectionGeometry(approaches = 4, totalLanes = 8, crossingMeters = 18.0, speedLimitMps = 15.0)
        )!!
        // Feed it a genuine 45 s cycle, which is below what this junction can physically run.
        val obs = observations(cycle = 45.0, phi = 10.0, n = 12, seed = 9)
        val est = TimingEstimator.estimate(obs, prior)
        if (est != null) {
            assertTrue(
                "reported ${est.cycleSec} outside prior ${prior.minCycleSec}..${prior.maxCycleSec}",
                est.cycleSec >= prior.minCycleSec - 1e-6 && est.cycleSec <= prior.maxCycleSec + 1e-6,
            )
        }
    }
}
