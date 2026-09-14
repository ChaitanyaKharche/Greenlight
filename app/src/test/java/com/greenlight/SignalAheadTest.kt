package com.greenlight

import com.greenlight.core.LatLon
import com.greenlight.model.Fix
import com.greenlight.model.TrafficSignal
import com.greenlight.nav.FreeDriveSignals
import com.greenlight.nav.RouteSignalIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalAheadTest {

    /** A straight eastbound road at latitude 42.34, one vertex every ~82 m. */
    private val eastbound = (0..30).map { LatLon(42.3400, -71.0900 + it * 0.001) }

    private fun signalAt(id: Long, lat: Double, lon: Double) =
        TrafficSignal(id, LatLon(lat, lon))

    private fun fix(lat: Double, lon: Double, bearing: Double, speed: Double = 14.0) =
        Fix(LatLon(lat, lon), speed, bearing, 1_700_000_000.0, 5.0)

    @Test
    fun `snaps signals onto the route and orders them by distance`() {
        val signals = listOf(
            signalAt(1, 42.3400, -71.0880), // ~165 m in
            signalAt(2, 42.3400, -71.0850), // ~412 m in
            signalAt(3, 42.3400, -71.0820), // ~660 m in
            signalAt(9, 42.3480, -71.0850), // 900 m off to the north: not on this route
        )
        val index = RouteSignalIndex(eastbound, signals)
        assertEquals(3, index.signalCount)

        val ahead = index.ahead(progress = 0.0, limit = 5, maxRangeMeters = 900.0)
        assertEquals(listOf(1L, 2L, 3L), ahead.map { it.signal.id })
        assertTrue(ahead[0].distanceMeters < ahead[1].distanceMeters)
        // Travelling east, so the approach bearing should be about 90 degrees.
        ahead.forEach { assertEquals(90.0, it.approachBearing, 2.0) }
    }

    @Test
    fun `signals already behind us are not returned`() {
        val index = RouteSignalIndex(
            eastbound,
            listOf(signalAt(1, 42.3400, -71.0880), signalAt(2, 42.3400, -71.0850)),
        )
        // Sitting past the first signal.
        val ahead = index.ahead(progress = 300.0, limit = 5, maxRangeMeters = 900.0)
        assertEquals(listOf(2L), ahead.map { it.signal.id })
    }

    @Test
    fun `range limit truncates the corridor`() {
        val index = RouteSignalIndex(
            eastbound,
            listOf(
                signalAt(1, 42.3400, -71.0880),
                signalAt(2, 42.3400, -71.0850),
                signalAt(3, 42.3400, -71.0820),
            ),
        )
        val ahead = index.ahead(progress = 0.0, limit = 5, maxRangeMeters = 450.0)
        assertEquals(listOf(1L, 2L), ahead.map { it.signal.id })
    }

    @Test
    fun `progress is null once we have left the route`() {
        val index = RouteSignalIndex(eastbound, emptyList())
        assertNull(index.progressMeters(LatLon(42.3500, -71.0850)))
        val onRoute = index.progressMeters(LatLon(42.34002, -71.0850))
        assertTrue(onRoute != null && onRoute > 0.0)
    }

    @Test
    fun `free drive picks up only signals in the cone ahead`() {
        val candidates = listOf(
            signalAt(1, 42.3400, -71.0860), // dead ahead, ~330 m east
            signalAt(2, 42.3400, -71.0940), // behind us
            signalAt(3, 42.3450, -71.0895), // hard left, off axis
        )
        val ahead = FreeDriveSignals.ahead(fix(42.3400, -71.0900, bearing = 90.0), candidates)
        assertEquals(listOf(1L), ahead.map { it.signal.id })
        assertEquals(330.0, ahead[0].distanceMeters, 30.0)
    }

    @Test
    fun `free drive stays silent when stationary because the bearing is noise`() {
        val candidates = listOf(signalAt(1, 42.3400, -71.0860))
        val ahead = FreeDriveSignals.ahead(fix(42.3400, -71.0900, 90.0, speed = 0.4), candidates)
        assertTrue(ahead.isEmpty())
    }

    @Test
    fun `free drive ignores a fix with no bearing`() {
        val candidates = listOf(signalAt(1, 42.3400, -71.0860))
        val noBearing = Fix(LatLon(42.3400, -71.0900), 14.0, 0.0, 1_700_000_000.0, 5.0, hasBearing = false)
        assertTrue(FreeDriveSignals.ahead(noBearing, candidates).isEmpty())
    }

    @Test
    fun `free drive returns nearest first`() {
        val candidates = listOf(
            signalAt(3, 42.3400, -71.0820),
            signalAt(1, 42.3400, -71.0880),
            signalAt(2, 42.3400, -71.0850),
        )
        val ahead = FreeDriveSignals.ahead(fix(42.3400, -71.0900, 90.0), candidates)
        assertEquals(listOf(1L, 2L, 3L), ahead.map { it.signal.id })
    }
}
