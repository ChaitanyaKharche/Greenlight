package com.greenlight.glosa

import com.greenlight.core.SpeedBand
import com.greenlight.core.intersectBands
import com.greenlight.core.normalIntervalProbability
import com.greenlight.core.normaliseBands
import com.greenlight.model.GlosaAction
import com.greenlight.model.GlosaAdvice
import com.greenlight.model.SignalSchedule
import com.greenlight.model.TimingSource
import com.greenlight.model.TrafficSignal
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Tunables for the advisory. Defaults are deliberately conservative. */
data class GlosaConfig(
    /** Comfortable longitudinal acceleration, m/s^2. */
    val accelMps2: Double = 1.2,

    /** Never advise above the posted limit. Non-negotiable; there is no setting to disable it. */
    val speedLimitMarginMps: Double = 0.0,

    /** Floor for advice, m/s. Below this you are obstructing traffic, not optimising. */
    val minAdvisableMps: Double = 6.94, // 25 km/h

    /** Ignore signals beyond this range; GPS and timing error swamp the answer. */
    val maxRangeMeters: Double = 900.0,

    /** Too close to usefully change anything. */
    val minRangeMeters: Double = 25.0,

    /** How many downstream signals the corridor solver tries to chain. */
    val corridorSignals: Int = 4,

    /** Corridor chaining stops past this distance. */
    val corridorHorizonMeters: Double = 1500.0,

    /** Shave this much off each end of a green window as a safety buffer, in seconds. */
    val windowGuardSec: Double = 1.0,

    /** Assumed 1-sigma error in how well a human holds a target speed, m/s. */
    val speedHoldSigmaMps: Double = 0.6,

    /** Assumed 1-sigma along-track position error, m. */
    val positionSigmaMeters: Double = 8.0,

    /** Below this probability we say "you'll stop" rather than dangle false hope. */
    val minConfidenceToAdvise: Double = 0.55,

    /** Bands narrower than this are too twitchy to chase. */
    val minBandWidthMps: Double = 0.55,

    /** Advisory is suppressed entirely below this schedule confidence. */
    val minScheduleConfidence: Double = 0.35,

    /** Deadband around current speed inside which we just say HOLD. */
    val holdDeadbandMps: Double = 0.85,
)

/** One signal as the solver sees it. */
data class SolverTarget(
    val signal: TrafficSignal,
    /** Along-route distance from the vehicle to this stop line, in metres. */
    val distanceMeters: Double,
    val schedule: SignalSchedule,
)

/** Internal: the feasible speed bands for a single signal, plus the window each came from. */
private data class TargetBands(val target: SolverTarget, val bands: List<SpeedBand>)

object GlosaSolver {

    /**
     * Speed bands that clear [target], given the current speed and legal ceiling.
     *
     * For each upcoming green window [s, e], the arrival time must land inside it. Because
     * travel time is monotonically decreasing in cruise speed, each window maps to one
     * contiguous speed interval: arriving at `e` sets the lower bound, arriving at `s` the upper.
     */
    fun bandsFor(
        target: SolverTarget,
        nowEpochSec: Double,
        currentMps: Double,
        vMin: Double,
        vMax: Double,
        cfg: GlosaConfig,
    ): List<SpeedBand> {
        val d = target.distanceMeters
        if (d <= 0.0 || vMax <= vMin) return emptyList()

        // Only look as far ahead as the slowest legal speed could take us.
        val horizon = min(travelTime(d, currentMps, vMin, cfg.accelMps2) + 5.0, 900.0)
        val windows = target.schedule.greenWindows(nowEpochSec, horizon)
        if (windows.isEmpty()) return emptyList()

        val guard = cfg.windowGuardSec + target.schedule.sigmaSec * 0.5
        val tAtMax = travelTime(d, currentMps, vMax, cfg.accelMps2)
        val tAtMin = travelTime(d, currentMps, vMin, cfg.accelMps2)

        val out = ArrayList<SpeedBand>()
        for (w in windows) {
            val tStart = w.startEpochSec - nowEpochSec + guard
            val tEnd = w.endEpochSec - nowEpochSec - guard
            if (tEnd <= 0.0 || tEnd <= tStart) continue
            // No overlap between what we can achieve and when the window is open.
            if (tAtMax >= tEnd || tAtMin <= tStart) continue

            val vLower = if (tAtMin <= tEnd) vMin
            else cruiseSpeedFor(d, currentMps, tEnd, cfg.accelMps2, vMin, vMax) ?: continue
            val vUpper = if (tAtMax >= tStart) vMax
            else cruiseSpeedFor(d, currentMps, tStart, cfg.accelMps2, vMin, vMax) ?: continue

            val band = SpeedBand(vLower, vUpper)
            if (!band.isEmpty) out.add(band)
        }
        return normaliseBands(out)
    }

    /**
     * Full solve over the corridor.
     *
     * [targets] must be ordered by increasing distance. The first is always solved on its own;
     * downstream signals are then intersected in one at a time, and we keep the deepest chain
     * that still leaves a usable band. That is the "green wave" behaviour — one speed that
     * clears several lights rather than a fresh number at every intersection.
     */
    fun solve(
        targets: List<SolverTarget>,
        nowEpochSec: Double,
        currentMps: Double,
        speedLimitMps: Double?,
        cfg: GlosaConfig = GlosaConfig(),
    ): GlosaAdvice {
        val usable = targets
            .filter { it.distanceMeters in cfg.minRangeMeters..cfg.maxRangeMeters }
            .filter { it.schedule.confidence >= cfg.minScheduleConfidence }
            .sortedBy { it.distanceMeters }
        val primary = usable.firstOrNull()
            ?: return GlosaAdvice.noAdvice(noTargetNote(targets, cfg))

        val vMax = (speedLimitMps ?: primary.signal.speedLimitMps ?: 13.9) + cfg.speedLimitMarginMps
        val vMin = min(cfg.minAdvisableMps, vMax * 0.6)
        if (vMax <= vMin) return GlosaAdvice.noAdvice("Speed limit too low to advise")

        val chain = usable
            .filter { it.distanceMeters <= cfg.corridorHorizonMeters }
            .take(cfg.corridorSignals)
            .map { TargetBands(it, bandsFor(it, nowEpochSec, currentMps, vMin, vMax, cfg)) }

        val primaryBands = chain.first().bands
        if (primaryBands.isEmpty()) {
            return stopAdvice(primary, nowEpochSec, currentMps, vMax, cfg)
        }

        // Greedily deepen the chain for as long as a shared speed survives.
        var best = primaryBands
        var cleared = 1
        for (k in 1 until chain.size) {
            if (chain[k].bands.isEmpty()) break
            val merged = intersectBands(best, chain[k].bands)
                .filter { it.width >= cfg.minBandWidthMps }
            if (merged.isEmpty()) break
            best = merged
            cleared = k + 1
        }

        val wide = best.filter { it.width >= cfg.minBandWidthMps }.ifEmpty { best }
        val band = pickBand(wide, currentMps, vMax)
        val targetSpeed = pickSpeed(band, currentMps, vMax, cfg)

        val eta = travelTime(primary.distanceMeters, currentMps, targetSpeed, cfg.accelMps2)
        val confidence = arrivalConfidence(primary, nowEpochSec, currentMps, targetSpeed, eta, cfg)

        if (confidence < cfg.minConfidenceToAdvise) {
            return stopAdvice(primary, nowEpochSec, currentMps, vMax, cfg)
        }

        val delta = targetSpeed - currentMps
        val action = when {
            abs(delta) <= cfg.holdDeadbandMps -> GlosaAction.HOLD
            delta > 0 -> GlosaAction.SPEED_UP
            else -> GlosaAction.EASE_OFF
        }

        return GlosaAdvice(
            action = action,
            targetMps = targetSpeed,
            bandMps = band,
            confidence = confidence,
            signal = primary.signal,
            distanceMeters = primary.distanceMeters,
            etaSeconds = eta,
            timeToGreenSec = null,
            speedLimitMps = vMax,
            source = primary.schedule.source,
            signalsCleared = cleared,
        )
    }

    /** Picks the band whose achievable speed sits closest to the legal ceiling. */
    private fun pickBand(bands: List<SpeedBand>, currentMps: Double, vMax: Double): SpeedBand {
        return bands.maxByOrNull { min(it.max, vMax) - penalty(it, currentMps) } ?: bands.first()
    }

    /** Slightly disfavours bands that demand a big speed change for the same payoff. */
    private fun penalty(band: SpeedBand, currentMps: Double): Double {
        val nearest = currentMps.coerceIn(band.min, band.max)
        return abs(nearest - currentMps) * 0.25
    }

    /**
     * Inside the band, aim as high as the limit allows but stay off the edges: the edges are
     * exactly where a one-second timing error costs you the light.
     */
    private fun pickSpeed(band: SpeedBand, currentMps: Double, vMax: Double, cfg: GlosaConfig): Double {
        val inset = min(band.width / 2.0, 0.35)
        val lo = band.min + inset
        val hi = min(band.max - inset, vMax)
        if (hi <= lo) return ((band.min + band.max) / 2.0).coerceAtMost(vMax)
        // Holding current speed is always the nicest outcome, so prefer it when it is feasible.
        if (currentMps in lo..hi) return currentMps
        return if (currentMps > hi) hi else lo
    }

    /**
     * Probability of landing inside the green window, propagating the three error sources:
     * schedule uncertainty, how well the driver holds the number, and along-track GPS error.
     */
    private fun arrivalConfidence(
        target: SolverTarget,
        nowEpochSec: Double,
        currentMps: Double,
        targetSpeed: Double,
        eta: Double,
        cfg: GlosaConfig,
    ): Double {
        val v = max(targetSpeed, 1.0)
        val d = target.distanceMeters
        // t = d/v  =>  dt/dv = -d/v^2,  dt/dd = 1/v
        val sigmaSpeed = d * cfg.speedHoldSigmaMps / (v * v)
        val sigmaPos = cfg.positionSigmaMeters / v
        val sigmaSched = target.schedule.sigmaSec
        val sigma = kotlin.math.sqrt(
            sigmaSched * sigmaSched + sigmaSpeed * sigmaSpeed + sigmaPos * sigmaPos
        ).coerceAtLeast(0.4)

        val arrival = nowEpochSec + eta
        val windows = target.schedule.greenWindows(nowEpochSec, eta + 4.0 * sigma + 10.0)
        val p = windows.sumOf { w ->
            normalIntervalProbability(w.startEpochSec, w.endEpochSec, arrival, sigma)
        }
        return (p * target.schedule.confidence).coerceIn(0.0, 1.0)
    }

    /** No legal speed clears the light: tell the driver to come off the throttle and why. */
    private fun stopAdvice(
        target: SolverTarget,
        nowEpochSec: Double,
        currentMps: Double,
        vMax: Double,
        cfg: GlosaConfig,
    ): GlosaAdvice {
        val windows = target.schedule.greenWindows(nowEpochSec, 400.0)
        val next = windows.firstOrNull { it.startEpochSec > nowEpochSec }
        val timeToGreen = next?.let { it.startEpochSec - nowEpochSec }

        // Coasting so you reach the line as it goes green beats braking then launching.
        val coast = timeToGreen
            ?.takeIf { it > 1.0 }
            ?.let { (target.distanceMeters / it).coerceIn(0.0, vMax) }

        return GlosaAdvice(
            action = GlosaAction.STOP_EXPECTED,
            targetMps = coast,
            bandMps = null,
            confidence = target.schedule.confidence,
            signal = target.signal,
            distanceMeters = target.distanceMeters,
            etaSeconds = travelTime(target.distanceMeters, currentMps, max(currentMps, 1.0), cfg.accelMps2),
            timeToGreenSec = timeToGreen,
            speedLimitMps = vMax,
            source = target.schedule.source,
            signalsCleared = 0,
            note = "Red on arrival",
        )
    }

    private fun noTargetNote(targets: List<SolverTarget>, cfg: GlosaConfig): String = when {
        targets.isEmpty() -> "No signal ahead"
        targets.none { it.distanceMeters <= cfg.maxRangeMeters } -> "Next signal out of range"
        targets.none { it.schedule.confidence >= cfg.minScheduleConfidence } ->
            "Still learning this signal"
        else -> "No advice"
    }
}
