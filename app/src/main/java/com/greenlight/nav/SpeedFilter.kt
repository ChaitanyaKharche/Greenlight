package com.greenlight.nav

import com.greenlight.core.LatLon
import com.greenlight.core.haversineMeters
import com.greenlight.model.Fix
import kotlin.math.exp

/**
 * Cleans up GNSS speed, which is the difference between this app learning anything and not.
 *
 * A receiver derives velocity from carrier Doppler, and at rest that estimate does not settle
 * to zero - it wanders, typically by 1-3 m/s. Observed in the field: a phone sitting at a red
 * light reporting 2.8 m/s. Since the observation detector only registers a stop below 1 m/s,
 * that noise meant no stop was ever recorded, no red-to-green transition was ever captured,
 * and no cycle was ever learned. The advisory silently could not work.
 *
 * Position is far better behaved than Doppler velocity at rest, so stationarity is decided by
 * net displacement over a short window rather than by the reported speed. While stationary the
 * bearing is meaningless too, so it is withdrawn rather than passed on to the cone search.
 */
class SpeedFilter(
    /** How much history stationarity is judged over. */
    private val windowSec: Double = 6.0,
    /** Minimum history before a stationarity call is trusted. */
    private val minWindowSec: Double = 3.5,
    /**
     * Net displacement below which we call it stopped. Comfortably above still-GPS scatter
     * and below what even a crawl covers in [minWindowSec].
     */
    private val stationaryDisplacementMeters: Double = 7.0,
    /** Exponential smoothing time constant for the moving case, seconds. */
    private val smoothingTauSec: Double = 1.6,
    /** Speeds under this are reported as zero once stationarity is established. */
    private val crawlFloorMps: Double = 0.7,
    /**
     * Slew limits, m/s^2. A car cannot gain speed faster than this, so a reading that implies
     * it is a receiver glitch rather than a manoeuvre. Rejecting on physics beats tuning the
     * smoothing constant, which would trade away the responsiveness needed when pulling away
     * from a stop. Braking is allowed to be harsher than acceleration because it is.
     */
    private val maxAccelMps2: Double = 5.0,
    private val maxDecelMps2: Double = 9.0,
) {
    private data class Sample(val t: Double, val p: LatLon)

    private val history = ArrayDeque<Sample>()
    private var smoothed = 0.0
    private var lastT = Double.NaN

    /** True when the last call decided the vehicle was parked. Exposed for diagnostics. */
    var isStationary: Boolean = false
        private set

    fun filter(fix: Fix): Fix {
        history.addLast(Sample(fix.epochSec, fix.position))
        while (history.size > 2 && fix.epochSec - history.first().t > windowSec) {
            history.removeFirst()
        }

        val span = fix.epochSec - history.first().t
        val displacement = haversineMeters(history.first().p, fix.position)
        val enoughHistory = span >= minWindowSec

        // Doppler noise moves the speed but not the vehicle, so displacement is the tell.
        isStationary = enoughHistory && displacement < stationaryDisplacementMeters

        if (isStationary) {
            smoothed = 0.0
            return fix.copy(speedMps = 0.0, hasBearing = false)
        }

        val dt = if (lastT.isNaN()) 1.0 else (fix.epochSec - lastT).coerceIn(0.05, 5.0)
        lastT = fix.epochSec
        // Standard exponential smoothing, with the weight derived from the actual gap between
        // fixes so a dropped fix does not distort the filter.
        // Discard the physically impossible before smoothing, otherwise a single 40 m/s
        // glitch drags the estimate halfway there no matter how gentle the filter is.
        val plausible = fix.speedMps.coerceIn(
            (smoothed - maxDecelMps2 * dt).coerceAtLeast(0.0),
            smoothed + maxAccelMps2 * dt,
        )
        val alpha = 1.0 - exp(-dt / smoothingTauSec)
        smoothed += alpha * (plausible - smoothed)

        val out = if (smoothed < crawlFloorMps) 0.0 else smoothed
        return fix.copy(speedMps = out)
    }

    fun reset() {
        history.clear()
        smoothed = 0.0
        lastT = Double.NaN
        isStationary = false
    }
}
