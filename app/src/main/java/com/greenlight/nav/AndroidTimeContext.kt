package com.greenlight.nav

import com.greenlight.spat.TimeContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * Phase offsets are anchored to local midnight because coordinated controllers lock to a
 * local master clock. Resolving the zone on every call keeps this correct when you drive
 * across a boundary, and keeps DST handled by the platform rather than by arithmetic.
 */
class AndroidTimeContext(private val zone: () -> ZoneId = { ZoneId.systemDefault() }) : TimeContext {

    override fun localMidnightEpochSec(epochSec: Double): Double {
        val z = zone()
        val instant = Instant.ofEpochSecond(epochSec.toLong())
        return instant.atZone(z).toLocalDate().atStartOfDay(z).toEpochSecond().toDouble()
    }

    override fun isWeekend(epochSec: Double): Boolean {
        val day = Instant.ofEpochSecond(epochSec.toLong()).atZone(zone()).dayOfWeek
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
    }

    override fun dayOfWeek(epochSec: Double): Int =
        Instant.ofEpochSecond(epochSec.toLong()).atZone(zone()).dayOfWeek.value

    override fun minuteOfDay(epochSec: Double): Double =
        (epochSec - localMidnightEpochSec(epochSec)) / 60.0
}
