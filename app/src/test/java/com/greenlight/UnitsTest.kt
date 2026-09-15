package com.greenlight

import com.greenlight.core.UnitSystem
import com.greenlight.core.formatDistance
import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {

    @Test
    fun `mph conversion is exact against the statute mile`() {
        // 1 mph is exactly 0.44704 m/s by definition.
        assertEquals(1.0, UnitSystem.IMPERIAL.fromMps(0.44704), 1e-9)
        assertEquals(65.0, UnitSystem.IMPERIAL.fromMps(65 * 0.44704), 1e-9)
        assertEquals(40, UnitSystem.IMPERIAL.display(40 * 0.44704))
    }

    @Test
    fun `kmh conversion round-trips`() {
        assertEquals(50.0, UnitSystem.METRIC.fromMps(50 / 3.6), 1e-9)
        assertEquals(13.9, UnitSystem.METRIC.toMps(50.04), 0.01)
    }

    @Test
    fun `the two systems disagree by the expected factor`() {
        val mps = 20.0
        val kmh = UnitSystem.METRIC.fromMps(mps)
        val mph = UnitSystem.IMPERIAL.fromMps(mps)
        assertEquals(1.609344, kmh / mph, 1e-6)
    }

    @Test
    fun `unknown speeds render as a dash rather than zero`() {
        assertEquals("—", UnitSystem.IMPERIAL.format(null))
        assertEquals("30", UnitSystem.IMPERIAL.format(30 * 0.44704))
    }

    @Test
    fun `locale picks the system that matches the road signs`() {
        assertEquals(UnitSystem.IMPERIAL, UnitSystem.forLocale("US"))
        assertEquals(UnitSystem.IMPERIAL, UnitSystem.forLocale("GB"))
        assertEquals(UnitSystem.METRIC, UnitSystem.forLocale("DE"))
        assertEquals(UnitSystem.METRIC, UnitSystem.forLocale("IN"))
        assertEquals(UnitSystem.METRIC, UnitSystem.forLocale(null))
    }

    @Test
    fun `distance switches from feet to miles where feet stop being readable`() {
        assertEquals("283 m", formatDistance(283.0, UnitSystem.METRIC))
        assertEquals("928 ft", formatDistance(283.0, UnitSystem.IMPERIAL))
        assertEquals("0.6 mi", formatDistance(966.0, UnitSystem.IMPERIAL))
    }
}
