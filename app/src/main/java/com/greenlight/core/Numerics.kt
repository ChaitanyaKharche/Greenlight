package com.greenlight.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Standard normal CDF via Zelen & Severo (A&S 26.2.17). Absolute error < 7.5e-8,
 * which is far tighter than anything our timing uncertainty justifies.
 */
fun normalCdf(z: Double): Double {
    if (z.isNaN()) return Double.NaN
    if (z < -8.0) return 0.0
    if (z > 8.0) return 1.0
    val t = 1.0 / (1.0 + 0.2316419 * abs(z))
    val poly = t * (0.319381530 +
        t * (-0.356563782 +
            t * (1.781477937 +
                t * (-1.821255978 + t * 1.330274429))))
    val pdf = exp(-z * z / 2.0) / sqrt(2.0 * PI)
    val upper = pdf * poly
    return if (z >= 0) 1.0 - upper else upper
}

/** Probability that a normal(mean=[mu], sd=[sigma]) sample lands in [[lo], [hi]]. */
fun normalIntervalProbability(lo: Double, hi: Double, mu: Double, sigma: Double): Double {
    if (hi <= lo) return 0.0
    if (sigma <= 1e-6) return if (mu in lo..hi) 1.0 else 0.0
    return (normalCdf((hi - mu) / sigma) - normalCdf((lo - mu) / sigma)).coerceIn(0.0, 1.0)
}

/** A closed interval of speeds in m/s. Empty when [max] < [min]. */
data class SpeedBand(val min: Double, val max: Double) {
    val isEmpty: Boolean get() = max < min - 1e-9
    val width: Double get() = (max - min).coerceAtLeast(0.0)

    fun intersect(other: SpeedBand): SpeedBand = SpeedBand(max(min, other.min), min(max, other.max))

    fun clampInto(v: Double): Double = v.coerceIn(min, max)
}

/** Intersects two sorted, disjoint band lists. Result stays sorted and disjoint. */
fun intersectBands(a: List<SpeedBand>, b: List<SpeedBand>): List<SpeedBand> {
    val out = ArrayList<SpeedBand>()
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        val s = a[i].intersect(b[j])
        if (!s.isEmpty) out.add(s)
        if (a[i].max < b[j].max) i++ else j++
    }
    return out
}

/** Merges overlapping bands and drops ones narrower than [minWidth]. */
fun normaliseBands(bands: List<SpeedBand>, minWidth: Double = 0.0): List<SpeedBand> {
    if (bands.isEmpty()) return emptyList()
    val sorted = bands.filter { !it.isEmpty }.sortedBy { it.min }
    val out = ArrayList<SpeedBand>()
    var cur = sorted.firstOrNull() ?: return emptyList()
    for (k in 1 until sorted.size) {
        val nxt = sorted[k]
        cur = if (nxt.min <= cur.max + 1e-9) SpeedBand(cur.min, max(cur.max, nxt.max)) else {
            out.add(cur); nxt
        }
    }
    out.add(cur)
    return out.filter { it.width >= minWidth - 1e-9 }
}

/**
 * Circular statistics over phases expressed as a fraction of a cycle.
 * [resultantLength] is the classic R in [0,1]: 1 means perfectly repeatable, 0 means uniform noise.
 */
data class CircularStats(val meanPhase: Double, val resultantLength: Double, val n: Int) {
    /** Circular standard deviation in the same units as the period, per Mardia. */
    fun sigma(period: Double): Double {
        val r = resultantLength.coerceIn(1e-6, 1.0 - 1e-9)
        return sqrt(-2.0 * ln(r)) * period / (2.0 * PI)
    }
}

/** [values] are raw timestamps; they are wrapped into [period] before averaging. */
fun circularStats(values: List<Double>, period: Double): CircularStats {
    if (values.isEmpty() || period <= 0.0) return CircularStats(0.0, 0.0, 0)
    var sx = 0.0
    var sy = 0.0
    for (v in values) {
        val theta = 2.0 * PI * (v.mod(period)) / period
        sx += cos(theta)
        sy += sin(theta)
    }
    val n = values.size
    val r = sqrt(sx * sx + sy * sy) / n
    var meanTheta = atan2(sy / n, sx / n)
    if (meanTheta < 0) meanTheta += 2.0 * PI
    return CircularStats(meanTheta / (2.0 * PI) * period, r, n)
}

/** Kotlin's `%` keeps the sign of the dividend; traffic phase maths wants a non-negative result. */
fun Double.mod(m: Double): Double {
    if (m <= 0.0) return 0.0
    val r = this % m
    return if (r < 0) r + m else r
}

/** Quantile by linear interpolation on a sorted copy. [q] in [0,1]. */
fun quantile(values: List<Double>, q: Double): Double {
    if (values.isEmpty()) return Double.NaN
    val s = values.sorted()
    if (s.size == 1) return s[0]
    val pos = q.coerceIn(0.0, 1.0) * (s.size - 1)
    val lo = pos.toInt()
    val hi = min(lo + 1, s.size - 1)
    val frac = pos - lo
    return s[lo] * (1 - frac) + s[hi] * frac
}
