package com.greenlight.learn

import com.greenlight.core.circularStats
import com.greenlight.core.mod
import com.greenlight.core.quantile
import com.greenlight.model.FixedPlanSchedule
import com.greenlight.model.SignalObservation
import com.greenlight.model.TimingSource
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Output of the cycle search for one (signal, approach, plan bucket). */
data class CycleEstimate(
    val cycleSec: Double,
    /** Green start as an offset within the cycle, measured from local midnight. */
    val greenStartInCycleSec: Double,
    val greenDurationSec: Double,
    /** Circular concentration R in [0,1]; how repeatable the observations are. */
    val resultantLength: Double,
    val sigmaSec: Double,
    val greenStartSamples: Int,
    val totalSamples: Int,
    /**
     * Probability that this peak is not an artefact of sweeping hundreds of candidate
     * cycles. See [TimingEstimator.significanceOf].
     */
    val significance: Double,
) {
    /**
     * Trust score, as the product of four things that must all hold before we put a
     * number in front of a driver:
     *
     *  - [significance]: the peak beats what random timings throw up by chance
     *  - [resultantLength]: the observations genuinely cluster
     *  - scatter: we can pin the green onset to within a few seconds
     *  - sample count: a tidy fit over five passes is still five passes
     */
    val confidence: Double
        get() {
            val sampleWeight = (greenStartSamples / 8.0).coerceAtMost(1.0)
            val scatter = sigmaSec / 4.0
            val sigmaFactor = 1.0 / (1.0 + scatter * scatter)
            return (significance * resultantLength * sigmaFactor * sampleWeight * 0.95)
                .coerceIn(0.0, 0.95)
        }
}

/**
 * Recovers a fixed-time signal plan from this phone's own stop/go history.
 *
 * The idea: at a fixed-time signal every red-to-green transition satisfies
 *
 *     g_i = phi + k_i * C
 *
 * so every pairwise difference is an integer multiple of the cycle length C. Recovering C is
 * therefore an approximate-GCD problem. Rather than solving AGCD directly we sweep candidate
 * cycles and score each by the circular concentration of `g_i mod C` — the same trick pitch
 * detectors use. The true cycle produces a tight cluster; a wrong one smears uniformly.
 *
 * The one trap: sub-multiples alias perfectly. If C is the true cycle then C/2, C/3 ... also
 * give a perfect cluster, because every multiple of C is also a multiple of C/m. Super-multiples
 * do not. So among all well-scoring candidates we take the LARGEST, not the smallest.
 *
 * This mirrors the published floating-car-data approach (cycle recovery as most-frequent
 * approximate GCD); with a single phone instead of a fleet it just needs more days to converge.
 */
object TimingEstimator {

    /** Urban signal cycles essentially always live in this range. */
    const val MIN_CYCLE_SEC = 30.0
    const val MAX_CYCLE_SEC = 200.0
    private const val CYCLE_STEP_SEC = 0.25

    /** Minimum red-to-green events before we will publish anything at all. */
    const val MIN_GREEN_SAMPLES = 4

    /** Candidates scoring within this fraction of the best are treated as tied. */
    private const val TIE_TOLERANCE = 0.94

    /**
     * Seconds of start-up lost time between the light going green and *our* wheels moving.
     * Being third in the queue biases every departure late; this is the standard correction.
     */
    const val QUEUE_BIAS_SEC = 2.2

    /**
     * @param observations all passes for one (signal, approach, plan bucket).
     * @param nowEpochSec used only to anchor the returned schedule to today's midnight.
     */
    /**
     * @param prior optional geometry-derived bounds. Narrowing the sweep does two things:
     *   it stops the estimator proposing a cycle the junction physically cannot run, and it
     *   shrinks the multiple-comparisons penalty, which is what makes confidence rise a
     *   couple of passes sooner.
     */
    fun estimate(
        observations: List<SignalObservation>,
        prior: IntersectionPrior? = null,
    ): CycleEstimate? {
        val departures = observations.mapNotNull { obs ->
            obs.departureEpochSec?.let { it - obs.localMidnightEpochSec }
        }
        if (departures.size < MIN_GREEN_SAMPLES) return null

        val span = (departures.max() - departures.min())
        // Fewer than a few cycles of coverage and the sweep has nothing to discriminate on.
        if (span < 3.0 * MIN_CYCLE_SEC) return null

        // A candidate longer than half the observed span can fit the data trivially.
        val spanLimit = min(MAX_CYCLE_SEC, max(MIN_CYCLE_SEC, span / 2.0))
        val minCycle = max(MIN_CYCLE_SEC, prior?.minCycleSec ?: MIN_CYCLE_SEC)
        val maxCycle = min(spanLimit, prior?.maxCycleSec ?: MAX_CYCLE_SEC)
        if (maxCycle < minCycle + CYCLE_STEP_SEC) return null

        var bestR = 0.0
        val scores = ArrayList<Pair<Double, Double>>() // (cycle, R)
        var c = minCycle
        while (c <= maxCycle) {
            val r = circularStats(departures, c).resultantLength
            scores.add(c to r)
            if (r > bestR) bestR = r
            c += CYCLE_STEP_SEC
        }
        if (bestR <= 0.0) return null

        // Largest candidate that ties the best score, then refined to its local peak.
        val threshold = bestR * TIE_TOLERANCE
        val coarse = scores.lastOrNull { it.second >= threshold }?.first ?: return null
        val cycle = refinePeak(departures, coarse)

        val stats = circularStats(departures, cycle)

        // Departures are biased late by queue discharge, so anchor on the early edge of the
        // cluster rather than its mean, then apply the standard start-up correction.
        val phases = departures.map { (it - stats.meanPhase).mod(cycle) }
            .map { if (it > cycle / 2) it - cycle else it }
        val earlyEdge = quantile(phases, 0.15)
        val greenStart = (stats.meanPhase + earlyEdge - QUEUE_BIAS_SEC).mod(cycle)

        val greenDuration = estimateGreenDuration(observations, cycle, greenStart, prior)
        val sigma = max(stats.sigma(cycle), 1.0)
        val significance =
            significanceOf(stats.resultantLength, departures.size, span, minCycle, maxCycle)

        return CycleEstimate(
            cycleSec = cycle,
            greenStartInCycleSec = greenStart,
            greenDurationSec = greenDuration,
            resultantLength = stats.resultantLength,
            sigmaSec = sigma,
            greenStartSamples = departures.size,
            totalSamples = observations.size,
            significance = significance,
        )
    }

    /**
     * Guards against the multiple-comparisons trap.
     *
     * We score several hundred candidate cycles, so the best of them looks impressive even on
     * pure noise — 15 random timestamps reliably produce a peak around R = 0.6-0.8. Without a
     * correction the estimator would confidently invent a cycle for an intersection it has
     * never understood, which is the single worst thing this app could do.
     *
     * Under a uniform null the resultant length satisfies P(R > r) ~ exp(-n r^2) (Rayleigh).
     * Adjacent candidates are not independent: two cycles are only distinguishable once the
     * furthest observation's phase shifts by half a turn, i.e. dC ~ C^2 / (2 * span). Integrating
     * that resolution across the search range gives the effective number of independent tries
     *
     *     M = 2 * span * (1/Cmin - 1/Cmax)
     *
     * and the chance that none of M tries beats r by luck is exp(-M * exp(-n r^2)).
     */
    fun significanceOf(
        r: Double,
        n: Int,
        span: Double,
        minCycle: Double = MIN_CYCLE_SEC,
        maxCycle: Double = MAX_CYCLE_SEC,
    ): Double {
        if (n < 2 || span <= 0.0 || maxCycle <= minCycle) return 0.0
        val effectiveTries = (2.0 * span * (1.0 / minCycle - 1.0 / maxCycle))
            .coerceAtLeast(1.0)
        val pSingle = exp(-n * r * r)
        return exp(-effectiveTries * pSingle).coerceIn(0.0, 1.0)
    }

    /** Golden-section-ish local refinement around the coarse grid winner. */
    private fun refinePeak(departures: List<Double>, coarse: Double): Double {
        var lo = coarse - CYCLE_STEP_SEC
        var hi = coarse + CYCLE_STEP_SEC
        var best = coarse
        var bestR = circularStats(departures, coarse).resultantLength
        repeat(24) {
            val mid = (lo + hi) / 2.0
            val left = (lo + mid) / 2.0
            val right = (mid + hi) / 2.0
            val rl = circularStats(departures, left).resultantLength
            val rr = circularStats(departures, right).resultantLength
            if (rl > bestR) { bestR = rl; best = left }
            if (rr > bestR) { bestR = rr; best = right }
            if (rl >= rr) hi = mid else lo = mid
        }
        return best
    }

    /**
     * Green duration is the weak part: we never observe green-to-red directly. What we do have
     * is every pass that sailed through without stopping — each is a point known to be inside
     * green. The far edge of that cluster is a lower bound on the green interval.
     */
    private fun estimateGreenDuration(
        observations: List<SignalObservation>,
        cycle: Double,
        greenStart: Double,
        prior: IntersectionPrior?,
    ): Double {
        val greenPhases = observations
            .filter { !it.stopped }
            .map { (it.arrivalEpochSec - it.localMidnightEpochSec - greenStart).mod(cycle) }

        // Green duration is the weakest thing we infer, because a green-to-red transition is
        // never directly observed. Geometry helps most here: pedestrian clearance on the
        // conflicting phases bounds how much of the cycle this approach can possibly hold.
        val fallback = prior?.likelyGreenSec ?: (cycle * 0.42)
        if (greenPhases.size < 3) {
            return prior?.let { fallback.coerceIn(it.minGreenSec, max(it.minGreenSec, it.maxGreenSec)) }
                ?: fallback
        }

        // Arrivals late in the cycle are more likely mis-assigned than genuinely green,
        // so take a high quantile rather than the raw maximum.
        val observed = quantile(greenPhases, 0.88) + 2.0

        // Reds give an upper bound: the earliest phase at which somebody was stopped.
        val redPhases = observations
            .filter { it.stopped }
            .map { (it.arrivalEpochSec - it.localMidnightEpochSec - greenStart).mod(cycle) }
        val redBound = if (redPhases.size >= 3) quantile(redPhases, 0.12) else cycle * 0.8

        val observedBound = observed.coerceIn(6.0, min(cycle * 0.85, max(8.0, redBound)))
        return prior?.let {
            observedBound.coerceIn(it.minGreenSec, max(it.minGreenSec, it.maxGreenSec))
        } ?: observedBound
    }

    /** Wraps an estimate into the schedule the solver consumes. */
    fun toSchedule(
        estimate: CycleEstimate,
        localMidnightEpochSec: Double,
        source: TimingSource = TimingSource.LEARNED,
    ) = FixedPlanSchedule(
        cycleSec = estimate.cycleSec,
        greenStartInCycleSec = estimate.greenStartInCycleSec,
        greenDurationSec = estimate.greenDurationSec,
        localMidnightEpochSec = localMidnightEpochSec,
        sigmaSec = estimate.sigmaSec,
        confidence = estimate.confidence,
        source = source,
    )

    /**
     * A freshly observed red-to-green resets the phase without re-running the whole sweep.
     * Cheap enough to call on every stop, and it keeps the plan locked to the controller
     * even when its clock drifts.
     */
    fun realign(schedule: FixedPlanSchedule, greenStartEpochSec: Double): FixedPlanSchedule {
        val rel = greenStartEpochSec - schedule.localMidnightEpochSec - QUEUE_BIAS_SEC
        val observedPhase = rel.mod(schedule.cycleSec)
        val drift = run {
            val raw = observedPhase - schedule.greenStartInCycleSec
            val half = schedule.cycleSec / 2
            when {
                raw > half -> raw - schedule.cycleSec
                raw < -half -> raw + schedule.cycleSec
                else -> raw
            }
        }
        // Only nudge; a single noisy sample should not yank a well-supported plan.
        val corrected = (schedule.greenStartInCycleSec + drift * 0.35).mod(schedule.cycleSec)
        val agreement = (1.0 - abs(drift) / (schedule.cycleSec / 2)).coerceIn(0.0, 1.0)
        return schedule.copy(
            greenStartInCycleSec = corrected,
            confidence = (schedule.confidence * 0.85 + agreement * 0.15).coerceIn(0.0, 0.95),
        )
    }
}
