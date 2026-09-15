package com.greenlight

import com.greenlight.core.LatLon
import com.greenlight.model.Fix
import com.greenlight.nav.SpeedFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpeedFilterTest {

    private val t0 = 1_700_000_000.0
    private val lat = 33.4255

    private fun fix(t: Double, metresEast: Double, reportedSpeed: Double) = Fix(
        position = LatLon(lat, -111.94 + metresEast / 92_900.0),
        speedMps = reportedSpeed,
        bearingDeg = 90.0,
        epochSec = t0 + t,
        accuracyMeters = 6.0,
    )

    @Test
    fun `a parked phone reporting doppler noise is reported as stopped`() {
        // Reproduces the field case: stationary at a light, receiver claiming 2.8 m/s.
        val filter = SpeedFilter()
        val rng = Random(1)
        var last = 0.0
        for (t in 0..15) {
            // Position wanders a few metres; the car has not moved.
            val jitter = rng.nextDouble(-3.0, 3.0)
            last = filter.filter(fix(t.toDouble(), jitter, 2.8)).speedMps
        }
        assertEquals(0.0, last, 1e-9)
        assertTrue(filter.isStationary)
    }

    @Test
    fun `a stopped fix no longer carries a bearing`() {
        val filter = SpeedFilter()
        var out = fix(0.0, 0.0, 2.5)
        for (t in 0..10) out = filter.filter(fix(t.toDouble(), 0.0, 2.5))
        assertFalse("a parked receiver's bearing is meaningless", out.hasBearing)
    }

    @Test
    fun `genuine motion passes through`() {
        val filter = SpeedFilter()
        var out = 0.0
        // 18 m/s for 20 s: 360 m of travel.
        for (t in 0..20) out = filter.filter(fix(t.toDouble(), t * 18.0, 18.0)).speedMps
        assertEquals(18.0, out, 0.6)
        assertFalse(filter.isStationary)
    }

    @Test
    fun `a slow crawl is not mistaken for being parked`() {
        val filter = SpeedFilter()
        var out = 0.0
        // 3 m/s covers 18 m in 6 s, well past the stationary threshold.
        for (t in 0..15) out = filter.filter(fix(t.toDouble(), t * 3.0, 3.0)).speedMps
        assertFalse(filter.isStationary)
        assertTrue("reported $out", out > 2.0)
    }

    @Test
    fun `smoothing rejects a single spurious spike`() {
        val filter = SpeedFilter()
        var out = 0.0
        for (t in 0..10) out = filter.filter(fix(t.toDouble(), t * 14.0, 14.0)).speedMps
        // One fix claims 40 m/s. The filter should barely flinch.
        out = filter.filter(fix(11.0, 11 * 14.0, 40.0)).speedMps
        assertTrue("spike leaked through as $out", out < 24.0)
    }

    @Test
    fun `pulling away from a stop is detected promptly`() {
        val filter = SpeedFilter()
        for (t in 0..12) filter.filter(fix(t.toDouble(), 0.0, 2.0))
        assertTrue(filter.isStationary)
        // Accelerate away: 2, 6, 10, 14 m/s over four seconds.
        var out = 0.0
        var x = 0.0
        for ((i, v) in listOf(2.0, 6.0, 10.0, 14.0).withIndex()) {
            x += v
            out = filter.filter(fix(13.0 + i, x, v)).speedMps
        }
        assertFalse("still thinks it is parked after pulling away", filter.isStationary)
        assertTrue("reported $out", out > 1.0)
    }

    @Test
    fun `early fixes are not judged before there is enough history`() {
        val filter = SpeedFilter()
        // A single fix cannot establish stationarity, so the reported speed stands.
        val out = filter.filter(fix(0.0, 0.0, 12.0))
        assertFalse(filter.isStationary)
        assertTrue(out.speedMps > 0.0)
    }
}
