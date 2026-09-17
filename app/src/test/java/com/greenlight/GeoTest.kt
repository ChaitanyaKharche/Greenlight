package com.greenlight

import com.greenlight.core.LatLon
import com.greenlight.core.bearingDegrees
import com.greenlight.core.bearingDeltaDegrees
import com.greenlight.core.cumulativeDistances
import com.greenlight.core.decodePolyline
import com.greenlight.core.haversineMeters
import com.greenlight.core.headingAt
import com.greenlight.core.projectOntoPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    @Test
    fun `haversine matches a known distance`() {
        // Boston Common to MIT, about 3.4 km.
        val d = haversineMeters(LatLon(42.3550, -71.0656), LatLon(42.3601, -71.0942))
        assertEquals(2410.0, d, 60.0)
    }

    @Test
    fun `bearing north is zero and east is ninety`() {
        assertEquals(0.0, bearingDegrees(LatLon(42.0, -71.0), LatLon(42.01, -71.0)), 0.2)
        assertEquals(90.0, bearingDegrees(LatLon(42.0, -71.0), LatLon(42.0, -70.99)), 0.2)
    }

    @Test
    fun `bearing delta wraps the short way round`() {
        assertEquals(-20.0, bearingDeltaDegrees(10.0, 350.0), 1e-9)
        assertEquals(20.0, bearingDeltaDegrees(350.0, 10.0), 1e-9)
        assertEquals(180.0, bearingDeltaDegrees(0.0, 180.0), 1e-9)
    }

    @Test
    fun `decodes a real OSRM polyline6 fragment`() {
        // Captured from router.project-osrm.org for a Boston route.
        val encoded = "imfwoApn}qfCw@gCcJcZc@uAQq@kJyZ"
        val points = decodePolyline(encoded, precision = 6)
        assertEquals(6, points.size)
        assertEquals(42.340069, points[0].lat, 1e-6)
        assertEquals(-71.089401, points[0].lon, 1e-6)
        assertEquals(42.340484, points[5].lat, 1e-6)
        assertEquals(-71.088386, points[5].lon, 1e-6)
    }

    @Test
    fun `truncated polyline does not throw`() {
        val truncated = "imfwoApn}qfCw@gCcJcZc@uAQq@kJy"
        // Bad input should degrade to a shorter path rather than crash mid-drive.
        assertTrue(decodePolyline(truncated, precision = 6).isNotEmpty())
    }

    @Test
    fun `projection finds the nearest point on a path`() {
        val path = listOf(
            LatLon(42.3400, -71.0900),
            LatLon(42.3400, -71.0800),
            LatLon(42.3500, -71.0800),
        )
        val cumulative = cumulativeDistances(path)
        // A point just north of the middle of the first leg.
        val p = projectOntoPath(LatLon(42.34045, -71.0850), path, cumulative)
        assertNotNull(p)
        assertEquals(0, p!!.segmentIndex)
        assertEquals(50.0, p.lateralMeters, 8.0)
        assertTrue(p.alongMeters in 350.0..500.0)
    }

    @Test
    fun `heading follows the segment we are on`() {
        val path = listOf(
            LatLon(42.3400, -71.0900),
            LatLon(42.3400, -71.0800),
            LatLon(42.3500, -71.0800),
        )
        val cumulative = cumulativeDistances(path)
        // First leg runs east, second runs north.
        assertEquals(90.0, headingAt(path, cumulative, 100.0)!!, 1.0)
        assertEquals(0.0, headingAt(path, cumulative, cumulative[1] + 100.0)!!, 1.0)
    }

    @Test
    fun `cumulative distance is monotonic and ends at the total length`() {
        val path = (0..20).map { LatLon(42.34 + it * 0.001, -71.09) }
        val c = cumulativeDistances(path)
        assertEquals(0.0, c[0], 1e-9)
        c.toList().zipWithNext().forEach { (a, b) -> assertTrue(b > a) }
        assertEquals(haversineMeters(path.first(), path.last()), c.last(), 1.0)
    }
}
