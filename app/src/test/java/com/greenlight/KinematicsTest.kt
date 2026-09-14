package com.greenlight

import com.greenlight.glosa.cruiseSpeedFor
import com.greenlight.glosa.travelTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KinematicsTest {

    @Test
    fun `constant speed reduces to distance over speed`() {
        assertEquals(10.0, travelTime(100.0, 10.0, 10.0, 1.2), 1e-6)
    }

    @Test
    fun `accelerating arrives sooner than holding the slower speed`() {
        val hold = travelTime(300.0, 10.0, 10.0, 1.2)
        val faster = travelTime(300.0, 10.0, 15.0, 1.2)
        assertTrue(faster < hold)
    }

    @Test
    fun `ramp phase is accounted for, not teleported`() {
        // 0 -> 20 m/s at 2 m/s^2 covers 100 m in 10 s, then 100 m at 20 m/s takes 5 s.
        assertEquals(15.0, travelTime(200.0, 0.0, 20.0, 2.0), 1e-6)
    }

    @Test
    fun `short distance never reaches cruise speed`() {
        // From rest at 2 m/s^2 over 4 m: t = sqrt(2d/a) = 2 s, regardless of a high target.
        assertEquals(2.0, travelTime(4.0, 0.0, 50.0, 2.0), 1e-6)
    }

    @Test
    fun `travel time is monotonically decreasing in cruise speed`() {
        var prev = Double.MAX_VALUE
        var v = 5.0
        while (v <= 30.0) {
            val t = travelTime(400.0, 12.0, v, 1.2)
            assertTrue("not monotonic at v=$v", t < prev)
            prev = t
            v += 0.5
        }
    }

    @Test
    fun `cruiseSpeedFor inverts travelTime`() {
        val target = travelTime(400.0, 12.0, 17.0, 1.2)
        val v = cruiseSpeedFor(400.0, 12.0, target, 1.2, 5.0, 30.0)
        assertEquals(17.0, v!!, 1e-3)
    }

    @Test
    fun `cruiseSpeedFor returns null when the time is unreachable`() {
        // Far too quick even at the ceiling.
        assertNull(cruiseSpeedFor(400.0, 12.0, 5.0, 1.2, 5.0, 25.0))
        // Far too slow even at the floor.
        assertNull(cruiseSpeedFor(400.0, 12.0, 500.0, 1.2, 5.0, 25.0))
    }
}
