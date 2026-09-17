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
    /** True when the cycle came from other time-of-day plans rather than this one. */
    val cycleBorrowed: Boolean = false,
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

    /**
     * A green pass is worth this fraction of a departure. It constrains the phase to a window
     * tens of seconds wide rather than to an instant, so it is genuinely weaker - but there
     * are several per drive instead of at most one.
     */
    const val GREEN_PASS_WEIGHT = 0.45

    /** Weighted evidence needed before anything is published at all. */
    const val MIN_EFFECTIVE_SAMPLES = 4.0

    /** Bar when the cycle is already known and only the offset is being fitted. */
    const val MIN_EFFECTIVE_SAMPLES_PINNED = 2.0

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
        /** Overrides the prior's range. Used to lock the sweep onto a pooled cycle. */
        cycleBounds: ClosedFloatingPointRange<Double>? = null,
    ): CycleEstimate? {
        // Two kinds of evidence, of very different quality.
        //
        // A departure is a red-to-green transition: a near-instant that must lie at the start
        // of a green window. Precise, but you only get one when you actually had to stop.
        //
        // A green pass says "the light was green at this moment" - anywhere inside a window
        // tens of seconds wide. Far weaker individually, but you collect several per drive
        // instead of one. Using only departures threw away most of every trip: a real drive
        // logged eight green passes and two stops, and nothing was learned from the eight.
        val departures = observations.mapNotNull { obs ->
            obs.departureEpochSec?.let { it - obs.localMidnightEpochSec }
        }
        val greenPasses = observations
            .filter { !it.stopped }
            .map { it.arrivalEpochSec - it.localMidnightEpochSec }

        // A single timestamp is trivially "concentrated" at every candidate cycle, so each
        // stream needs enough points to say anything at all.
        val departureWeight = if (departures.size >= 2) departures.size.toDouble() else 0.0
        val greenWeight =
            if (greenPasses.size >= 3) GREEN_PASS_WEIGHT * greenPasses.size else 0.0
        val effectiveSamples = departureWeight + greenWeight
        // With the cycle already pinned by [cycleBounds] there is essentially one parameter
        // left to fit, so the evidence bar drops accordingly. Holding the full bar here would
        // defeat the point of pooling: the thin plan would still be rejected even though its
        // only unknown is the offset.
        val required =
            if (cycleBounds != null) MIN_EFFECTIVE_SAMPLES_PINNED else MIN_EFFECTIVE_SAMPLES
        if (effectiveSamples < required) return null

        val all = departures + greenPasses
        val span = all.max() - all.min()
        // Fewer than a few cycles of coverage and the sweep has nothing to discriminate on.
        // Not a concern when the cycle is pinned, since no discrimination is being attempted.
        if (cycleBounds == null && span < 3.0 * MIN_CYCLE_SEC) return null

        // A candidate longer than half the observed span can fit the data trivially.
        val spanLimit = min(MAX_CYCLE_SEC, max(MIN_CYCLE_SEC, span / 2.0))
        val minCycle = max(
            MIN_CYCLE_SEC,
            cycleBounds?.start ?: prior?.minCycleSec ?: MIN_CYCLE_SEC,
        )
        val maxCycle = min(
            if (cycleBounds != null) MAX_CYCLE_SEC else spanLimit,
            cycleBounds?.endInclusive ?: prior?.maxCycleSec ?: MAX_CYCLE_SEC,
        )
        // A degenerate range means the caller has pinned the cycle exactly and wants only
        // the phase fitted. Re-searching even a narrow window would be actively harmful: the
        // phase is measured from local midnight, so an observation eleven hours later sits
        // some four hundred cycles out and multiplies any cycle error by that factor. A
        // 0.1 s wobble becomes forty seconds of phase error.
        val pinned = maxCycle - minCycle < CYCLE_STEP_SEC
        if (!pinned && maxCycle < minCycle + CYCLE_STEP_SEC) return null

        fun scoreAt(c: Double): Double {
            val rDeparture =
                if (departureWeight > 0) circularStats(departures, c).resultantLength else 0.0
            // Greens spread across the whole green window even at the true cycle, so their
            // concentration peaks lower than a departure's - around 0.75 for a green
            // occupying 40% of the cycle - but still far above the ~1/sqrt(n) of noise.
            val rGreen =
                if (greenWeight > 0) circularStats(greenPasses, c).resultantLength else 0.0
            return (departureWeight * rDeparture + greenWeight * rGreen) / effectiveSamples
        }

        val cycle: Double
        val bestScore: Double
        if (pinned) {
            cycle = minCycle
            bestScore = scoreAt(cycle)
        } else {
            var best = 0.0
            val scores = ArrayList<Pair<Double, Double>>() // (cycle, score)
            var c = minCycle
            while (c <= maxCycle) {
                val score = scoreAt(c)
                scores.add(c to score)
                if (score > best) best = score
                c += CYCLE_STEP_SEC
            }
            if (best <= 0.0) return null
            // Largest candidate that ties the best score, then refined to its local peak.
            val threshold = best * TIE_TOLERANCE
            val coarse = scores.lastOrNull { it.second >= threshold }?.first ?: return null
            cycle = refinePeak(coarse, ::scoreAt)
            bestScore = best
        }
        if (bestScore <= 0.0) return null

        // Phase comes from departures when we have them, because they mark the green onset
        // directly. Falling back to green passes means recovering the onset from the middle
        // of the observed cluster, which is both noisier and needs a green-duration guess.
        val greenDurationSeed = prior?.likelyGreenSec ?: (cycle * 0.42)
        val greenStart: Double
        val stats: com.greenlight.core.CircularStats
        if (departureWeight > 0) {
            stats = circularStats(departures, cycle)
            // Departures are biased late by queue discharge, so anchor on the early edge of
            // the cluster rather than its mean, then apply the start-up correction.
            val phases = departures.map { (it - stats.meanPhase).mod(cycle) }
                .map { if (it > cycle / 2) it - cycle else it }
            val earlyEdge = quantile(phases, 0.15)
            greenStart = (stats.meanPhase + earlyEdge - QUEUE_BIAS_SEC).mod(cycle)
        } else {
            stats = circularStats(greenPasses, cycle)
            greenStart = (stats.meanPhase - greenDurationSeed / 2.0).mod(cycle)
        }

        val greenDuration = estimateGreenDuration(observations, cycle, greenStart, prior)
        val sigma = max(stats.sigma(cycle), 1.0)
        val significance = significanceOf(
            r = bestScore,
            n = effectiveSamples.toInt().coerceAtLeast(2),
            span = span,
            minCycle = minCycle,
            maxCycle = maxCycle,
        )

        return CycleEstimate(
            cycleSec = cycle,
            greenStartInCycleSec = greenStart,
            greenDurationSec = greenDuration,
            resultantLength = bestScore,
            sigmaSec = sigma,
            greenStartSamples = departures.size,
            totalSamples = observations.size,
            significance = significance,
        )
    }

    /**
     * Two-stage estimate that lets one plan's driving inform another.
     *
     * Observations are filed per time-of-day plan, because a controller really does run
     * different timings at 08:00 and 17:00. Taken literally that partitions a junction's
     * evidence five ways for weekdays, and each partition cold-starts: an evening commute
     * teaches the morning nothing, which is exactly what a driver sees as "it never learns".
     *
     * The partition is too strict though. What changes between plans is mostly the offset
     * and the green splits; the cycle length is very often held constant, because adjacent
     * junctions have to stay coordinated and that requires a common cycle. So:
     *
     *  1. try the plan's own data on its own terms
     *  2. failing that, recover the cycle from every plan pooled together
     *  3. lock the sweep to that cycle and solve only the phase from this plan's data
     *
     * Step 3 needs far less evidence than a blind search, because with the cycle fixed there
     * is essentially one parameter left. The result is discounted, since the shared-cycle
     * assumption does sometimes fail.
     */
    fun estimatePooled(
        bucketObservations: List<SignalObservation>,
        pooledObservations: List<SignalObservation>,
        prior: IntersectionPrior? = null,
    ): CycleEstimate? {
        val direct = estimate(bucketObservations, prior)
        if (direct != null && direct.confidence >= DIRECT_CONFIDENCE_FLOOR) return direct

        // Nothing extra to borrow from.
        if (pooledObservations.size <= bucketObservations.size) return direct

        val pooledCycle = cycleFromWithinPlanGaps(pooledObservations, prior) ?: return direct
        val pooled = CycleEstimate(
            cycleSec = pooledCycle,
            greenStartInCycleSec = 0.0,
            greenDurationSec = 0.0,
            resultantLength = 0.0,
            sigmaSec = 1.0,
            greenStartSamples = 0,
            totalSamples = pooledObservations.size,
            significance = 0.0,
        )
        // Pin the cycle exactly. The pooled fit spans every plan, so it is far better
        // determined than anything this plan's handful of passes could re-derive.
        val bounds = pooled.cycleSec..pooled.cycleSec
        val refined = estimate(bucketObservations, prior, cycleBounds = bounds)
            ?: return direct

        val borrowed = refined.copy(
            significance = refined.significance * POOLED_CYCLE_DISCOUNT,
            cycleBorrowed = true,
        )
        // Only take the borrowed answer if it genuinely beats what this plan managed alone.
        return if (direct == null || borrowed.confidence > direct.confidence) borrowed else direct
    }

    /**
     * Recovers the cycle length from observations spanning several time-of-day plans.
     *
     * Pooling raw timestamps does not work, and fails in a way that looks plausible: each
     * plan has its own offset, so the combined set forms several clusters and the sweep
     * happily settles on something that splits the difference. Feeding a genuine 100 s
     * junction an evening plan at offset 20 and a morning plan at offset 60 produced 50 s.
     *
     * The fix is to score something the offset cannot touch. Within a single plan every pair
     * of green onsets differs by a whole number of cycles,
     *
     *     g_i - g_j = (k_i - k_j) * C
     *
     * and the offset cancels. So the concentration of *within-plan* gaps is scored instead,
     * pooled across plans. Cross-plan pairs are deliberately excluded, since those carry the
     * offset difference that makes the naive version wrong.
     */
    internal fun cycleFromWithinPlanGaps(
        observations: List<SignalObservation>,
        prior: IntersectionPrior? = null,
    ): Double? {
        val gaps = ArrayList<Double>()
        observations.groupBy { it.planBucket }.values.forEach { group ->
            val times = group.mapNotNull { obs ->
                obs.departureEpochSec?.let { it - obs.localMidnightEpochSec }
            }.sorted()
            for (i in times.indices) {
                for (j in i + 1 until times.size) {
                    val d = times[j] - times[i]
                    // Gaps shorter than a cycle carry no information, and enormous ones are
                    // dominated by clock drift between days.
                    if (d in MIN_CYCLE_SEC..MAX_GAP_SEC) gaps.add(d)
                }
            }
        }
        if (gaps.size < 3) return null

        val minCycle = max(MIN_CYCLE_SEC, prior?.minCycleSec ?: MIN_CYCLE_SEC)
        val maxCycle = min(MAX_CYCLE_SEC, prior?.maxCycleSec ?: MAX_CYCLE_SEC)
        if (maxCycle <= minCycle) return null

        fun score(c: Double) = circularStats(gaps, c).resultantLength

        var best = 0.0
        val scores = ArrayList<Pair<Double, Double>>()
        var c = minCycle
        while (c <= maxCycle) {
            val r = score(c)
            scores.add(c to r)
            if (r > best) best = r
            c += CYCLE_STEP_SEC
        }
        if (best < MIN_GAP_CONCENTRATION) return null

        // Sub-multiples alias perfectly here too, so take the largest well-scoring candidate.
        val coarse = scores.lastOrNull { it.second >= best * TIE_TOLERANCE }?.first ?: return null
        return refinePeak(coarse, ::score)
    }

    /** Gaps beyond this span too many days for clock drift to be ignorable. */
    private const val MAX_GAP_SEC = 4.0 * 86_400.0

    /** Below this the gaps are not repeating cleanly enough to trust a cycle from them. */
    private const val MIN_GAP_CONCENTRATION = 0.75

    /** Above this, a plan's own data stands on its own and no pooling is needed. */
    private const val DIRECT_CONFIDENCE_FLOOR = 0.5

    /** Penalty for assuming the cycle carries across time-of-day plans. */
    private const val POOLED_CYCLE_DISCOUNT = 0.85

    /**
     * How many stops are still wanted at a junction before it can be advised on, given what
     * has already been seen there. Surfaced in the UI, because "Learning this signal" with no
     * sense of progress is indistinguishable from "broken".
     */
    fun samplesStillNeeded(observations: List<SignalObservation>): Int {
        val departures = observations.count { it.departureEpochSec != null }
        val greens = observations.count { !it.stopped }
        val have = (if (departures >= 2) departures.toDouble() else 0.0) +
            (if (greens >= 3) GREEN_PASS_WEIGHT * greens else 0.0)
        if (have >= MIN_EFFECTIVE_SAMPLES) return 0
        // Expressed in stops, since those are what the driver can least control but which
        // move the estimate fastest.
        return kotlin.math.ceil(
            (MIN_EFFECTIVE_SAMPLES - have).coerceAtLeast(0.0)
        ).toInt().coerceAtLeast(1)
    }

    /** Golden-section-ish local refinement around the coarse grid winner. */
    /**
     * Guards against the multiple-comparisons trap.
     *
     * We score several hundred candidate cycles, so the best of them looks impressive even on
     * pure noise - 15 random timestamps reliably produce a peak around R = 0.6-0.8. Without a
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

    private fun refinePeak(coarse: Double, score: (Double) -> Double): Double {
        var lo = coarse - CYCLE_STEP_SEC
        var hi = coarse + CYCLE_STEP_SEC
        var best = coarse
        var bestScore = score(coarse)
        repeat(24) {
            val mid = (lo + hi) / 2.0
            val left = (lo + mid) / 2.0
            val right = (mid + hi) / 2.0
            val sl = score(left)
            val sr = score(right)
            if (sl > bestScore) { bestScore = sl; best = left }
            if (sr > bestScore) { bestScore = sr; best = right }
            if (sl >= sr) hi = mid else lo = mid
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
