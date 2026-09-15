package com.greenlight.core

import kotlin.math.roundToInt

/** Speed display units. Everything internal stays SI; this is presentation only. */
enum class UnitSystem(val label: String) {
    METRIC("km/h"),
    IMPERIAL("mph");

    fun fromMps(mps: Double): Double = when (this) {
        METRIC -> mps * 3.6
        IMPERIAL -> mps / 0.44704
    }

    fun toMps(display: Double): Double = when (this) {
        METRIC -> display / 3.6
        IMPERIAL -> display * 0.44704
    }

    /** Rounded whole number for the big readout. */
    fun display(mps: Double): Int = fromMps(mps).roundToInt()

    fun format(mps: Double?): String = mps?.let { "${display(it)}" } ?: "—"

    companion object {
        /**
         * Default from the device locale rather than forcing one. The handful of countries
         * still posting limits in mph are the ones where showing km/h would be actively
         * confusing next to a road sign.
         */
        fun forLocale(country: String?): UnitSystem =
            if (country?.uppercase() in IMPERIAL_COUNTRIES) IMPERIAL else METRIC

        private val IMPERIAL_COUNTRIES = setOf("US", "GB", "MM", "LR", "AS", "GU", "MP", "PR", "VI")
    }
}

/** Distance in whichever unit suits the speed system. */
fun formatDistance(metres: Double, units: UnitSystem): String = when (units) {
    UnitSystem.METRIC -> "${metres.roundToInt()} m"
    // Feet up close, miles further out - the switchover is where feet stop being readable.
    UnitSystem.IMPERIAL -> if (metres < 300) "${(metres * 3.28084).roundToInt()} ft"
    else "%.1f mi".format(metres / 1609.344)
}
