package com.greenlight.learn

import kotlin.math.max
import kotlin.math.min

/**
 * Geometry of a signalised intersection, as far as OpenStreetMap knows it.
 *
 * [approaches] counts legs, not ways: a road passing straight through a node contributes two,
 * a road terminating there contributes one. So a normal crossroads is 4 and a T is 3.
 */
data class IntersectionGeometry(
    val approaches: Int,
    val totalLanes: Int,
    /** Widest carriageway a pedestrian has to cross here, in metres. */
    val crossingMeters: Double,
    val speedLimitMps: Double?,
) {
    /** Do we actually know enough for the derived numbers to mean anything? */
    val isUsable: Boolean get() = approaches >= 2 && crossingMeters > 3.0
}

/**
 * Timing bounds derived from geometry alone, before a single observation.
 *
 * Signal timing is not arbitrary: large parts of it are dictated by physics and by design
 * standards that every agency follows. Yellow follows from approach speed, all-red from how
 * far a car must travel to clear the box, and minimum phase length from how long a pedestrian
 * needs to walk across. Those are hard constraints, not guesses.
 *
 * What geometry cannot give you is the traffic-volume-dependent part: how the green is split
 * between phases, and whether an actuated controller extends or truncates it. So this produces
 * a *prior*, not an answer - a range to search inside rather than a number to trust.
 *
 * The payoff is convergence speed. The estimator's confidence is penalised by the number of
 * candidate cycles it had to sweep, so narrowing the range from 30-200 s to, say, 64-116 s
 * cuts the effective independent tries roughly fourfold and reaches significance a couple of
 * passes sooner. The bigger win is on green duration, which observations alone constrain
 * poorly because a green-to-red transition is never directly seen.
 */
data class IntersectionPrior(
    val minCycleSec: Double,
    val maxCycleSec: Double,
    val likelyCycleSec: Double,
    val minGreenSec: Double,
    val maxGreenSec: Double,
    val likelyGreenSec: Double,
    val yellowSec: Double,
    val allRedSec: Double,
    val phases: Int,
) {
    fun describe() = "phases=$phases cycle=%.0f..%.0f s (likely %.0f) green~%.0f s y=%.1f ar=%.1f"
        .format(minCycleSec, maxCycleSec, likelyCycleSec, likelyGreenSec, yellowSec, allRedSec)
}

object IntersectionPriors {

    /** Driver perception-reaction time used in the ITE change-interval formula, seconds. */
    private const val PERCEPTION_SEC = 1.0

    /** Comfortable deceleration, m/s^2. The ITE standard value is 10 ft/s^2. */
    private const val DECEL_MPS2 = 3.05

    /** Design vehicle length for clearing the intersection box, metres. */
    private const val VEHICLE_LENGTH_M = 6.0

    /** MUTCD walking speed for pedestrian clearance, m/s (3.5 ft/s). */
    private const val WALK_SPEED_MPS = 1.1

    /** MUTCD minimum WALK indication, seconds. */
    private const val WALK_INTERVAL_SEC = 7.0

    /** Start-up plus clearance lost time per phase, seconds. */
    private const val LOST_TIME_PER_PHASE_SEC = 4.0

    /** Typical lane width, metres. */
    const val LANE_WIDTH_M = 3.5

    /** Plausible range for Webster's sum of critical flow ratios. */
    private const val FLOW_RATIO_LOW = 0.55
    private const val FLOW_RATIO_HIGH = 0.75

    private const val ABSOLUTE_MIN_CYCLE = 30.0
    private const val ABSOLUTE_MAX_CYCLE = 200.0

    /**
     * Yellow change interval, ITE kinematic equation:
     *
     *     y = t + v / (2a + 2gG)
     *
     * Grade is assumed flat because OSM does not carry it. Clamped to the 3-6 s band that
     * the MUTCD permits.
     */
    fun yellowInterval(speedMps: Double): Double =
        (PERCEPTION_SEC + speedMps / (2 * DECEL_MPS2)).coerceIn(3.0, 6.0)

    /** All-red clearance: time to cross the box plus a vehicle length, at approach speed. */
    fun allRedClearance(crossingMeters: Double, speedMps: Double): Double =
        ((crossingMeters + VEHICLE_LENGTH_M) / max(speedMps, 4.0)).coerceIn(0.0, 6.0)

    /**
     * Shortest a phase can legally be, set by the pedestrian crossing it conflicts with.
     * This is the hardest bound geometry gives us, and it scales directly with road width.
     */
    fun minimumPhaseSec(crossingMeters: Double, speedMps: Double): Double {
        val ped = WALK_INTERVAL_SEC + crossingMeters / WALK_SPEED_MPS
        return ped + yellowInterval(speedMps) + allRedClearance(crossingMeters, speedMps)
    }

    /**
     * Phase count from leg count and width. Four-legged junctions that are wide enough to
     * carry turn pockets almost always run protected lefts, which doubles the phases.
     */
    fun estimatePhases(approaches: Int, totalLanes: Int): Int {
        val base = when {
            approaches <= 2 -> 2
            approaches == 3 -> 3
            else -> 4
        }
        val lanesPerApproach = totalLanes.toDouble() / approaches.coerceAtLeast(1)
        return if (approaches >= 4 && lanesPerApproach >= 2.5) base * 2 else base
    }

    /**
     * Webster's optimal cycle:
     *
     *     C = (1.5 L + 5) / (1 - Y)
     *
     * with L the total lost time and Y the sum of critical flow ratios. Y depends on traffic
     * volume, which a map cannot tell us, so we evaluate across a plausible band and return
     * the resulting range rather than a single figure.
     */
    fun websterCycle(phases: Int, flowRatio: Double): Double {
        val lost = phases * LOST_TIME_PER_PHASE_SEC
        val denominator = (1.0 - flowRatio).coerceAtLeast(0.15)
        return (1.5 * lost + 5.0) / denominator
    }

    /** Builds the full prior. Returns null when OSM does not describe the junction well enough. */
    fun of(geometry: IntersectionGeometry, fallbackSpeedMps: Double = 13.9): IntersectionPrior? {
        if (!geometry.isUsable) return null
        val v = geometry.speedLimitMps ?: fallbackSpeedMps
        val phases = estimatePhases(geometry.approaches, geometry.totalLanes)

        val yellow = yellowInterval(v)
        val allRed = allRedClearance(geometry.crossingMeters, v)

        // Every phase must at least clear the pedestrians crossing it, so the cycle cannot be
        // shorter than the sum of those minima. This is frequently tighter than Webster's floor.
        val perPhaseFloor = minimumPhaseSec(geometry.crossingMeters, v)
        val pedestrianFloor = perPhaseFloor * min(phases, 4).toDouble() *
            // Opposing phases in the same barrier run concurrently, so not every phase adds.
            if (phases >= 4) 0.55 else 1.0

        val websterLow = websterCycle(phases, FLOW_RATIO_LOW)
        val websterHigh = websterCycle(phases, FLOW_RATIO_HIGH)

        val minCycle = max(ABSOLUTE_MIN_CYCLE, max(websterLow * 0.8, pedestrianFloor))
        val maxCycle = min(ABSOLUTE_MAX_CYCLE, max(websterHigh * 1.2, minCycle + 25.0))
        val likelyCycle = ((websterLow + websterHigh) / 2.0).coerceIn(minCycle, maxCycle)

        // Our approach's share of the usable green. A two-phase junction gives the main road
        // roughly half; every extra phase dilutes it.
        val usable = likelyCycle - phases * LOST_TIME_PER_PHASE_SEC
        val share = when {
            phases <= 2 -> 0.55
            phases == 3 -> 0.42
            phases == 4 -> 0.38
            else -> 0.28
        }
        val likelyGreen = (usable * share).coerceAtLeast(8.0)

        return IntersectionPrior(
            minCycleSec = minCycle,
            maxCycleSec = maxCycle,
            likelyCycleSec = likelyCycle,
            // A green cannot be shorter than its own pedestrian crossing, nor can it occupy
            // the whole cycle: the conflicting phases need their minima too.
            minGreenSec = max(8.0, perPhaseFloor * 0.5),
            maxGreenSec = min(likelyCycle * 0.8, likelyCycle - perPhaseFloor * 0.5),
            likelyGreenSec = likelyGreen,
            yellowSec = yellow,
            allRedSec = allRed,
            phases = phases,
        )
    }
}
