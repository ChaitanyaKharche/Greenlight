package com.greenlight

import com.greenlight.core.LatLon
import com.greenlight.learn.ObservationDetector
import com.greenlight.learn.DestinationPrediction
import com.greenlight.model.Fix
import com.greenlight.model.TrafficSignal
import com.greenlight.nav.SpeedFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Reproduces a real city drive that logged eighteen passes and only two stops, despite the
 * GPS trace being full of zero-speed fixes.
 *
 * The cause was an interaction between two pieces that were each correct alone: the speed
 * filter withdraws the bearing while stationary, because a parked receiver's heading is
 * noise, and the detector skipped any fix without a bearing. A stop consists entirely of
 * stationary fixes, so the detector discarded exactly the evidence it existed to collect.
 */
class StopDetectionRegressionTest {

    private val t0 = 1_700_000_000.0
    private val lat = 33.4141
    private val signalLon = -111.9262

    private fun lonAt(metresNorthOffset: Double) = signalLon
    private fun latAt(metres: Double) = lat + metres / 111_320.0

    private fun signal() = TrafficSignal(330683940L, LatLon(lat, signalLon))

    /** Northbound approach, stop at the line, wait, pull away. Mirrors the logged trace. */
    private fun trace(): List<Fix> = buildList {
        var t = 0.0
        // Approaching from the south at 50 km/h.
        for (m in listOf(-200.0, -160.0, -120.0, -80.0, -45.0, -20.0)) {
            add(Fix(LatLon(latAt(m), signalLon), 13.9, 0.0, t0 + t, 4.0)); t += 2.0
        }
        // Stationary at the stop line for 40 s. A real receiver keeps claiming motion.
        val rng = Random(4)
        while (t < 90.0) {
            add(
                Fix(
                    LatLon(latAt(-8.0 + rng.nextDouble(-2.5, 2.5)), signalLon),
                    rng.nextDouble(1.5, 3.0), // doppler noise, as observed in the field
                    rng.nextDouble(0.0, 360.0),
                    t0 + t, 4.0,
                )
            )
            t += 2.0
        }
        // Green: pull away and clear the junction.
        for (m in listOf(5.0, 30.0, 70.0, 120.0, 190.0)) {
            add(Fix(LatLon(latAt(m), signalLon), 11.0, 0.0, t0 + t, 4.0)); t += 2.0
        }
    }

    @Test
    fun `a stop behind the speed filter is recorded as a stop`() {
        val filter = SpeedFilter()
        val detector = ObservationDetector(
            localMidnightProvider = { t0 - 40_000.0 },
            isWeekendProvider = { false },
        )
        val s = signal()
        val out = trace().flatMap { detector.onFix(filter.filter(it), listOf(s)) }

        assertEquals(1, out.size)
        val o = out.single()
        assertTrue("the stop was logged as a roll-through", o.stopped)
        assertNotNull("no departure captured, so nothing to learn from", o.departureEpochSec)
        // Northbound: the approach must survive the stationary period, not be lost with it.
        assertEquals(0.0, o.approachBearing, 1.0)
    }

    @Test
    fun `the filter really does withdraw the bearing while stopped`() {
        // Guards the premise of the test above: if this ever stops being true, the
        // regression it covers can no longer occur and the coupling has changed.
        val filter = SpeedFilter()
        var last = trace().first()
        trace().take(25).forEach { last = filter.filter(it) }
        assertEquals(0.0, last.speedMps, 1e-9)
        assertFalse(last.hasBearing)
    }

    @Test
    fun `rolling through on green is still recorded as no stop`() {
        val filter = SpeedFilter()
        val detector = ObservationDetector(
            localMidnightProvider = { t0 - 40_000.0 },
            isWeekendProvider = { false },
        )
        var t = 0.0
        val moving = buildList {
            for (m in -200..200 step 25) {
                add(Fix(LatLon(latAt(m.toDouble()), signalLon), 13.9, 0.0, t0 + t, 4.0))
                t += 2.0
            }
        }
        val out = moving.flatMap { detector.onFix(filter.filter(it), listOf(signal())) }
        assertEquals(1, out.size)
        assertFalse(out.single().stopped)
    }
}

/**
 * A single recorded trip produced "auto-routing to Place 1 p=1.00" - certainty with nothing
 * to be certain against.
 */
class AutoRouteGuardTest {

    private fun prediction(p: Double, visits: Int, candidates: Int) = DestinationPrediction(
        placeId = 1L,
        label = "Place 1",
        position = LatLon(33.4, -111.9),
        probability = p,
        visits = visits,
        because = "test",
        candidateCount = candidates,
    )

    @Test
    fun `a lone place is never auto-routed to however certain it looks`() {
        assertFalse(prediction(1.0, visits = 1, candidates = 1).isSafeToAutoRoute(0.55))
        assertFalse(prediction(1.0, visits = 9, candidates = 1).isSafeToAutoRoute(0.55))
    }

    @Test
    fun `a place seen once or twice is a coincidence`() {
        assertFalse(prediction(0.9, visits = 1, candidates = 4).isSafeToAutoRoute(0.55))
        assertFalse(prediction(0.9, visits = 2, candidates = 4).isSafeToAutoRoute(0.55))
    }

    @Test
    fun `a genuine pattern is acted on`() {
        assertTrue(prediction(0.72, visits = 6, candidates = 4).isSafeToAutoRoute(0.55))
    }

    @Test
    fun `low probability still blocks it`() {
        assertFalse(prediction(0.4, visits = 8, candidates = 5).isSafeToAutoRoute(0.55))
    }
}
