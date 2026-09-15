package com.greenlight.data

import android.content.Context
import com.greenlight.core.UnitSystem
import java.util.Locale

/** Small settings store. SharedPreferences is the right size of tool for four values. */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("greenlight", Context.MODE_PRIVATE)

    var units: UnitSystem
        get() {
            val stored = sp.getString(KEY_UNITS, null)
            return stored?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() }
                ?: UnitSystem.forLocale(Locale.getDefault().country)
        }
        set(value) = sp.edit().putString(KEY_UNITS, value.name).apply()

    /** Spoken prompts. Some drivers want the bubble only. */
    var voiceEnabled: Boolean
        get() = sp.getBoolean(KEY_VOICE, true)
        set(value) = sp.edit().putBoolean(KEY_VOICE, value).apply()

    /**
     * Fallback limit for roads OSM has no maxspeed for, in m/s. Better than guessing high:
     * the advisory is clamped to the limit, so an over-generous default is the one error
     * that could put a number above the posted sign.
     */
    var defaultSpeedLimitMps: Double
        get() = sp.getFloat(KEY_DEFAULT_LIMIT, 13.4f).toDouble() // ~30 mph / 48 km/h
        set(value) = sp.edit().putFloat(KEY_DEFAULT_LIMIT, value.toFloat()).apply()

    companion object {
        private const val KEY_UNITS = "units"
        private const val KEY_VOICE = "voice"
        private const val KEY_DEFAULT_LIMIT = "default_limit_mps"
    }
}
