package com.greenlight.glosa

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Time to cover [distance] starting at [v0], changing to [vCruise] at +/-[accel], then holding.
 *
 * A naive GLOSA divides distance by speed, which quietly assumes teleporting to the target
 * speed. Over a 200 m approach that error is several seconds — enough to put you on the wrong
 * side of an amber. This models the trapezoidal profile instead.
 *
 * Strictly decreasing in [vCruise] for positive distance, which is what lets [cruiseSpeedFor]
 * invert it by bisection.
 */
fun travelTime(distance: Double, v0: Double, vCruise: Double, accel: Double): Double {
    if (distance <= 0.0) return 0.0
    if (vCruise <= 1e-6) return Double.POSITIVE_INFINITY
    val start = v0.coerceAtLeast(0.0)
    val a = accel.coerceAtLeast(0.05)
    val dv = vCruise - start
    if (abs(dv) < 1e-6) return distance / vCruise

    val tRamp = abs(dv) / a
    val dRamp = (start + vCruise) / 2.0 * tRamp
    if (dRamp <= distance) return tRamp + (distance - dRamp) / vCruise

    // We reach the stop line mid-ramp and never get to cruise: solve
    //   distance = v0*t + 0.5*aSigned*t^2   for the positive root.
    val aSigned = if (dv > 0) a else -a
    val disc = start * start + 2.0 * aSigned * distance
    if (disc < 0.0) return Double.POSITIVE_INFINITY
    return (-start + sqrt(disc)) / aSigned
}

/**
 * Inverse of [travelTime] over speed: the cruise speed that puts arrival exactly at
 * [targetTime], searched inside [vMin, vMax]. Null when no speed in range gets there.
 */
fun cruiseSpeedFor(
    distance: Double,
    v0: Double,
    targetTime: Double,
    accel: Double,
    vMin: Double,
    vMax: Double,
    iterations: Int = 48,
): Double? {
    if (targetTime <= 0.0 || vMax <= vMin) return null
    val tAtMax = travelTime(distance, v0, vMax, accel)
    val tAtMin = travelTime(distance, v0, vMin, accel)
    if (targetTime < tAtMax || targetTime > tAtMin) return null

    var lo = vMin
    var hi = vMax
    repeat(iterations) {
        val mid = (lo + hi) / 2.0
        // travelTime decreases as speed rises, so overshooting time means we need more speed.
        if (travelTime(distance, v0, mid, accel) > targetTime) lo = mid else hi = mid
    }
    return (lo + hi) / 2.0
}

/**
 * Distance needed to change from [v0] to [v1] at [accel]. Used to reject advice that
 * would need harder braking than the driver has room for.
 */
fun rampDistance(v0: Double, v1: Double, accel: Double): Double {
    val a = accel.coerceAtLeast(0.05)
    return abs(v1 * v1 - v0 * v0) / (2.0 * a)
}
