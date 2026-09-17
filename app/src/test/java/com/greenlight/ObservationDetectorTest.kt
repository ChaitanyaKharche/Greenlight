package com.greenlight

import com.greenlight.core.LatLon
import com.greenlight.learn.ObservationDetector
import com.greenlight.model.Fix
import com.greenlight.model.SignalObservation
import com.greenlight.model.TrafficSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationDetectorTest {

    private val t0 = 1_700_000_000.0
    private val midnight = 1_699_977_600.0
    private val signalLon = -71.0850
    private val lat = 42.3400

    /** At this latitude 0.001 degrees of longitude is about 82 m. */
    private fun lonFor(metresEastOfSignal: Double) = signalLon + metresEastOfSignal / 82_290.0

    private fun signal(bearing: Double? = null) =
        TrafficSignal(1L, LatLon(lat, signalLon), approachBearing = bearing)

    private fun detector() = ObservationDetector(
        localMidnightProvider = { midnight },
        isWeekendProvider = { false },
    )

    private fun fix(t: Double, metresEast: Double, speed: Double, bearing: Double = 90.0) =
        Fix(LatLon(lat, lonFor(metresEast)), speed, bearing, t0 + t, 5.0)

    /** Approach from the west, stop at the line, wait, then pull away east. */
    private fun stopAndGoTrack(): List<Fix> = buildList {
        add(fix(0.0, -66.0, 10.0))
        add(fix(1.0, -37.0, 6.0))
        add(fix(2.0, -8.0, 0.5))
        for (t in 3..30) add(fix(t.toDouble(), -4.0, 0.0))
        add(fix(31.0, -3.0, 3.0))   // wheels roll: our proxy for the green onset
        add(fix(32.0, 41.0, 6.0))
        add(fix(33.0, 95.0, 10.0))  // clear of the capture radius, track resolves
    }

    private fun run(detector: ObservationDetector, track: List<Fix>, s: TrafficSignal) =
        track.flatMap { detector.onFix(it, listOf(s)) }

    @Test
    fun `records a stop and the moment we moved off`() {
        val out: List<SignalObservation> = run(detector(), stopAndGoTrack(), signal())
        assertEquals(1, out.size)
        val o = out.single()
        assertTrue(o.stopped)
        assertEquals(t0 + 31.0, o.departureEpochSec!!, 1e-9)
        assertEquals(90.0, o.approachBearing, 1e-9)
        assertEquals(midnight, o.localMidnightEpochSec, 1e-9)
    }

    @Test
    fun `rolling through on green records no departure`() {
        val track = listOf(
            fix(0.0, -66.0, 12.0),
            fix(1.0, -20.0, 12.0),
            fix(2.0, 25.0, 12.0),
            fix(3.0, 95.0, 12.0),
        )
        val o = run(detector(), track, signal()).single()
        assertFalse(o.stopped)
        assertNull(o.departureEpochSec)
    }

    @Test
    fun `a pass in the opposite direction is rejected when the approach is known`() {
        // Signal records the eastbound approach; we drive westbound through it.
        val westbound = stopAndGoTrack().map { f ->
            Fix(f.position, f.speedMps, 270.0, f.epochSec, f.accuracyMeters)
        }
        assertTrue(run(detector(), westbound, signal(bearing = 90.0)).isEmpty())
    }

    @Test
    fun `the same pass is kept when it matches the known approach`() {
        assertEquals(1, run(detector(), stopAndGoTrack(), signal(bearing = 90.0)).size)
    }

    @Test
    fun `a fix with no bearing is ignored`() {
        val track = stopAndGoTrack().map {
            Fix(it.position, it.speedMps, 0.0, it.epochSec, it.accuracyMeters, hasBearing = false)
        }
        assertTrue(run(detector(), track, signal()).isEmpty())
    }

    @Test
    fun `driving past without getting close enough records nothing`() {
        // Stays roughly 60 m off to the side: inside the capture radius but never near
        // the stop line, so it must not count as a pass.
        val offset = 60.0 / 111_320.0
        val track = (0..6).map { t ->
            Fix(
                LatLon(lat + offset, lonFor(-60.0 + t * 30.0)),
                12.0, 90.0, t0 + t, 5.0,
            )
        }
        assertTrue(run(detector(), track, signal()).isEmpty())
    }

    @Test
    fun `an abandoned track is discarded rather than logged`() {
        // Park next to the signal for 15 minutes; this is not a signal observation.
        val track = buildList {
            add(fix(0.0, -66.0, 10.0))
            add(fix(1.0, -8.0, 0.4))
            add(fix(700.0, -4.0, 0.0))
            add(fix(701.0, 95.0, 8.0))
        }
        assertTrue(run(detector(), track, signal()).isEmpty())
    }

    @Test
    fun `two separate passes produce two observations`() {
        val d = detector()
        val s = signal()
        val first = run(d, stopAndGoTrack(), s)
        val second = run(d, stopAndGoTrack().map {
            Fix(it.position, it.speedMps, it.bearingDeg, it.epochSec + 3600.0, it.accuracyMeters)
        }, s)
        assertEquals(1, first.size)
        assertEquals(1, second.size)
    }
}
