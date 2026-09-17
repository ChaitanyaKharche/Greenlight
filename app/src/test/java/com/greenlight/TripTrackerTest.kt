package com.greenlight

import com.greenlight.core.LatLon
import com.greenlight.learn.TripTracker
import com.greenlight.model.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripTrackerTest {

    private val t0 = 1_700_000_000.0
    private val lat = 33.4255 // Tempe

    private fun lon(metresEast: Double) = -111.9400 + metresEast / 92_900.0

    private fun fix(t: Double, metresEast: Double, speed: Double) =
        Fix(LatLon(lat, lon(metresEast)), speed, 90.0, t0 + t, 5.0)

    /** Drive [metres] east, then sit still for [dwell] seconds. */
    private fun driveThenStop(
        tracker: TripTracker,
        metres: Double,
        dwell: Double,
        startT: Double = 0.0,
        startX: Double = 0.0,
    ): Pair<com.greenlight.learn.Trip?, Double> {
        var trip: com.greenlight.learn.Trip? = null
        var t = startT
        val steps = 20
        for (i in 0..steps) {
            val x = startX + metres * i / steps
            tracker.onFix(fix(t, x, 15.0))?.let { trip = it }
            t += 10.0
        }
        var dwelt = 0.0
        while (dwelt <= dwell) {
            tracker.onFix(fix(t, startX + metres, 0.0))?.let { trip = it }
            t += 10.0
            dwelt += 10.0
        }
        return trip to t
    }

    @Test
    fun `a drive followed by a long stop is one trip`() {
        val tracker = TripTracker()
        val (trip, _) = driveThenStop(tracker, metres = 3000.0, dwell = 200.0)
        assertNotNull(trip)
        assertEquals(3000.0, trip!!.distanceMeters, 60.0)
        assertTrue(trip.origin != null)
    }

    @Test
    fun `a red light does not end the trip`() {
        val tracker = TripTracker()
        var trip: com.greenlight.learn.Trip? = null
        var t = 0.0
        // Drive, sit at a 90 s red, drive on. That is one journey, not two.
        for (i in 0..20) { tracker.onFix(fix(t, i * 100.0, 15.0))?.let { trip = it }; t += 10.0 }
        for (i in 0..9) { tracker.onFix(fix(t, 2000.0, 0.0))?.let { trip = it }; t += 10.0 }
        for (i in 0..20) { tracker.onFix(fix(t, 2000.0 + i * 100.0, 15.0))?.let { trip = it }; t += 10.0 }
        assertNull("a signal stop was mistaken for an arrival", trip)
    }

    @Test
    fun `a short shuffle is not a trip`() {
        val tracker = TripTracker()
        val (trip, _) = driveThenStop(tracker, metres = 150.0, dwell = 220.0)
        assertNull(trip)
    }

    @Test
    fun `two journeys produce two trips`() {
        val tracker = TripTracker()
        val (first, t1) = driveThenStop(tracker, 3000.0, 220.0)
        val (second, _) = driveThenStop(tracker, 2000.0, 220.0, startT = t1, startX = 3000.0)
        assertNotNull(first)
        assertNotNull(second)
        assertTrue(second!!.distanceMeters < first!!.distanceMeters)
    }

    @Test
    fun `flush salvages a trip interrupted by shutdown`() {
        val tracker = TripTracker()
        var t = 0.0
        for (i in 0..20) { tracker.onFix(fix(t, i * 150.0, 15.0)); t += 10.0 }
        val trip = tracker.flush(t0 + t)
        assertNotNull("in-progress trip was lost on shutdown", trip)
        assertTrue(trip!!.distanceMeters > 2000.0)
    }
}
