package com.greenlight

import com.greenlight.core.LatLon
import com.greenlight.core.SpeedBand
import com.greenlight.core.intersectBands
import com.greenlight.glosa.GlosaConfig
import com.greenlight.glosa.GlosaSolver
import com.greenlight.glosa.SolverTarget
import com.greenlight.model.FixedPlanSchedule
import com.greenlight.model.GlosaAction
import com.greenlight.model.TimingSource
import com.greenlight.model.TrafficSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlosaSolverTest {

    private val t0 = 1_700_000_000.0
    private val cfg = GlosaConfig()

    private fun signal(id: Long) = TrafficSignal(id, LatLon(42.34, -71.09))

    /** Green for [green] s out of every [cycle] s, starting [startsIn] s from now. */
    private fun schedule(
        cycle: Double,
        green: Double,
        startsIn: Double,
        sigma: Double = 1.0,
        confidence: Double = 0.9,
        source: TimingSource = TimingSource.LEARNED,
    ) = FixedPlanSchedule(
        cycleSec = cycle,
        greenStartInCycleSec = ((startsIn) % cycle + cycle) % cycle,
        greenDurationSec = green,
        localMidnightEpochSec = t0,
        sigmaSec = sigma,
        confidence = confidence,
        source = source,
    )

    @Test
    fun `holds current speed when it already clears the green`() {
        // 400 m away at 15 m/s arrives in ~26.7 s; green runs 20..50 s.
        val target = SolverTarget(signal(1), 400.0, schedule(90.0, 30.0, 20.0))
        val advice = GlosaSolver.solve(listOf(target), t0, currentMps = 15.0, speedLimitMps = 18.0, cfg = cfg)
        assertEquals(GlosaAction.HOLD, advice.action)
        assertTrue(advice.confidence > 0.7)
        assertEquals(15.0, advice.targetMps!!, 0.9)
    }

    @Test
    fun `eases off when arriving too early`() {
        // 400 m at 20 m/s arrives in 20 s, but green does not open until 35 s.
        val target = SolverTarget(signal(1), 400.0, schedule(90.0, 25.0, 35.0))
        val advice = GlosaSolver.solve(listOf(target), t0, currentMps = 20.0, speedLimitMps = 22.0, cfg = cfg)
        assertEquals(GlosaAction.EASE_OFF, advice.action)
        assertTrue("target ${advice.targetMps}", advice.targetMps!! < 20.0)
    }

    @Test
    fun `never advises above the speed limit`() {
        // Making this green would need well over the limit.
        val target = SolverTarget(signal(1), 600.0, schedule(90.0, 8.0, 12.0))
        val advice = GlosaSolver.solve(listOf(target), t0, currentMps = 12.0, speedLimitMps = 13.9, cfg = cfg)
        advice.targetMps?.let { assertTrue("advised $it", it <= 13.9 + 1e-6) }
    }

    @Test
    fun `reports a stop when no legal speed clears the light`() {
        // Green already closed; next one is a full cycle away and unreachable slowly enough.
        val target = SolverTarget(signal(1), 150.0, schedule(120.0, 10.0, 95.0))
        val advice = GlosaSolver.solve(listOf(target), t0, currentMps = 14.0, speedLimitMps = 14.0, cfg = cfg)
        assertEquals(GlosaAction.STOP_EXPECTED, advice.action)
        assertTrue(advice.timeToGreenSec!! > 0.0)
        // Coast advice should be slower than just barrelling up to the line.
        assertTrue(advice.targetMps!! < 14.0)
    }

    @Test
    fun `suppresses advice while a signal is still being learned`() {
        val target = SolverTarget(signal(1), 300.0, schedule(90.0, 30.0, 10.0, confidence = 0.1))
        val advice = GlosaSolver.solve(listOf(target), t0, 14.0, 16.0, cfg)
        assertEquals(GlosaAction.NO_ADVICE, advice.action)
        assertEquals("Still learning this signal", advice.note)
    }

    @Test
    fun `ignores signals beyond the usable range`() {
        val target = SolverTarget(signal(1), 5_000.0, schedule(90.0, 30.0, 10.0))
        val advice = GlosaSolver.solve(listOf(target), t0, 14.0, 16.0, cfg)
        assertEquals(GlosaAction.NO_ADVICE, advice.action)
    }

    @Test
    fun `chains a green wave across several signals`() {
        // Three lights on a progression band: offsets spaced so ~13.5 m/s clears all three.
        val v = 13.5
        val cycle = 80.0
        val targets = listOf(200.0, 500.0, 820.0).mapIndexed { i, d ->
            val arrival = d / v
            SolverTarget(signal(i.toLong()), d, schedule(cycle, 26.0, arrival - 9.0))
        }
        val advice = GlosaSolver.solve(targets, t0, currentMps = v, speedLimitMps = 16.0, cfg = cfg)
        assertTrue("cleared ${advice.signalsCleared}", advice.signalsCleared >= 3)
        assertTrue(advice.targetMps!! in 12.0..15.0)
    }

    @Test
    fun `falls back to the nearest signal when the corridor cannot be chained`() {
        val targets = listOf(
            SolverTarget(signal(1), 250.0, schedule(90.0, 30.0, 14.0)),
            // Second light is deliberately incompatible with any speed that clears the first.
            SolverTarget(signal(2), 520.0, schedule(90.0, 6.0, 78.0)),
        )
        val advice = GlosaSolver.solve(targets, t0, currentMps = 14.0, speedLimitMps = 16.0, cfg = cfg)
        assertEquals(1, advice.signalsCleared)
        assertTrue(advice.action != GlosaAction.NO_ADVICE)
    }

    @Test
    fun `wide timing uncertainty drags confidence down`() {
        val tight = SolverTarget(signal(1), 350.0, schedule(90.0, 25.0, 22.0, sigma = 0.5))
        val loose = SolverTarget(signal(1), 350.0, schedule(90.0, 25.0, 22.0, sigma = 9.0))
        val a = GlosaSolver.solve(listOf(tight), t0, 14.0, 16.0, cfg)
        val b = GlosaSolver.solve(listOf(loose), t0, 14.0, 16.0, cfg)
        assertTrue("${a.confidence} vs ${b.confidence}", a.confidence > b.confidence)
    }

    @Test
    fun `band intersection keeps only the overlap`() {
        val a = listOf(SpeedBand(8.0, 12.0), SpeedBand(16.0, 20.0))
        val b = listOf(SpeedBand(10.0, 17.0))
        val out = intersectBands(a, b)
        assertEquals(2, out.size)
        assertEquals(10.0, out[0].min, 1e-9)
        assertEquals(12.0, out[0].max, 1e-9)
        assertEquals(16.0, out[1].min, 1e-9)
        assertEquals(17.0, out[1].max, 1e-9)
    }

    @Test
    fun `every advised speed actually lands inside a green window`() {
        // Property check: sweep distances and offsets, and verify the advice is self-consistent.
        var checked = 0
        for (d in 60..800 step 37) {
            for (off in 0..80 step 7) {
                val target = SolverTarget(signal(1), d.toDouble(), schedule(90.0, 28.0, off.toDouble()))
                val advice = GlosaSolver.solve(listOf(target), t0, 13.0, 16.0, cfg)
                val v = advice.targetMps ?: continue
                if (advice.action == GlosaAction.STOP_EXPECTED) continue
                val eta = com.greenlight.glosa.travelTime(d.toDouble(), 13.0, v, cfg.accelMps2)
                val arrival = t0 + eta
                val windows = target.schedule.greenWindows(t0, eta + 5.0)
                assertTrue(
                    "d=$d off=$off v=$v eta=$eta landed outside green",
                    windows.any { arrival >= it.startEpochSec - 1e-6 && arrival <= it.endEpochSec + 1e-6 },
                )
                checked++
            }
        }
        assertTrue("property test never exercised the solver", checked > 40)
    }
}
