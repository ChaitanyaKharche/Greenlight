package com.greenlight.learn

import com.greenlight.core.LatLon
import com.greenlight.data.GreenLightDb
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/** A ranked guess at where this trip is heading. */
data class DestinationPrediction(
    val placeId: Long,
    val label: String,
    val position: LatLon,
    val probability: Double,
    val visits: Int,
    /** Human-readable reason, so the ranking is auditable rather than magic. */
    val because: String,
)

/**
 * Predicts where you are going from when you set off and where from.
 *
 * The model is deliberately simple and interpretable: a kernel-weighted vote over past
 * visits. For a candidate place p,
 *
 *     score(p) = sum over past visits v of p   w_time(v) * w_day(v) * w_origin(v) * w_recency(v)
 *
 * Time of day uses a Gaussian kernel, wrapped over 24 h so 23:50 and 00:10 are close. Day of
 * week is an exact match with a weekday/weekend fallback, because "Thursdays I drive to
 * campus" and "weekdays I commute" are both real patterns and the second should not be
 * drowned out by the first. Recency decays with a half-life so a place you stopped visiting
 * six months ago fades instead of competing forever.
 *
 * Scores are normalised into probabilities with a Laplace prior, so a single visit never
 * yields 100% confidence.
 */
class DestinationPredictor(
    private val timeSigmaMinutes: Double = 50.0,
    private val recencyHalfLifeDays: Double = 45.0,
    /** Weight for a visit on a different day that shares the weekday/weekend class. */
    private val sameClassWeight: Double = 0.35,
    /** Weight applied when the trip started from the same place as the past visit did. */
    private val originMatchBonus: Double = 2.0,
    /** Pseudo-count spread across candidates, so thin evidence stays humble. */
    private val laplaceAlpha: Double = 1.5,
) {

    fun predict(
        db: GreenLightDb,
        nowEpochSec: Double,
        dayOfWeek: Int,
        minuteOfDay: Double,
        originPlaceId: Long?,
        currentPosition: LatLon?,
        limit: Int = 4,
    ): List<DestinationPrediction> {
        val places = db.allPlaces().associateBy { it.id }
        if (places.isEmpty()) return emptyList()
        val visits = db.allVisits()
        if (visits.isEmpty()) return emptyList()

        val scores = HashMap<Long, Double>()
        val reasons = HashMap<Long, MutableList<String>>()

        for (v in visits) {
            val place = places[v.placeId] ?: continue

            val dt = wrappedMinuteDelta(v.minuteOfDay, minuteOfDay)
            val wTime = exp(-(dt * dt) / (2 * timeSigmaMinutes * timeSigmaMinutes))
            if (wTime < 0.02) continue

            val wDay = when {
                v.dayOfWeek == dayOfWeek -> 1.0
                isWeekend(v.dayOfWeek) == isWeekend(dayOfWeek) -> sameClassWeight
                else -> 0.05
            }

            val ageDays = (nowEpochSec - v.arrivalEpoch) / 86_400.0
            val wRecency = Math.pow(0.5, ageDays / recencyHalfLifeDays)

            val wOrigin = if (originPlaceId != null && v.originPlaceId == originPlaceId) {
                originMatchBonus
            } else 1.0

            val contribution = wTime * wDay * wRecency * wOrigin
            scores[v.placeId] = (scores[v.placeId] ?: 0.0) + contribution

            if (contribution > 0.4) {
                val list = reasons.getOrPut(v.placeId) { mutableListOf() }
                if (list.size < 3) {
                    list.add(
                        "${dayName(v.dayOfWeek)} ${clock(v.minuteOfDay)}" +
                            if (wOrigin > 1.0) " from here" else ""
                    )
                }
            }

            // Never let a place currently under our wheels rank as a destination.
            if (currentPosition != null &&
                com.greenlight.core.haversineMeters(currentPosition, place.position) < 200.0
            ) {
                scores[v.placeId] = 0.0
            }
        }

        val positive = scores.filterValues { it > 0.0 }
        if (positive.isEmpty()) return emptyList()

        val total = positive.values.sum() + laplaceAlpha * positive.size
        return positive.entries
            .sortedByDescending { it.value }
            .take(limit)
            .mapNotNull { (id, score) ->
                val place = places[id] ?: return@mapNotNull null
                DestinationPrediction(
                    placeId = id,
                    label = place.label ?: "Place ${id}",
                    position = place.position,
                    probability = ((score + laplaceAlpha) / total).coerceIn(0.0, 1.0),
                    visits = place.visits,
                    because = reasons[id]?.joinToString(", ")
                        ?: "${place.visits} past visits",
                )
            }
    }

    /** Minute-of-day difference, wrapped so 23:50 and 00:10 are 20 minutes apart. */
    internal fun wrappedMinuteDelta(a: Double, b: Double): Double {
        val raw = abs(a - b)
        return min(raw, 1440.0 - raw)
    }

    /** java.time uses 1=Monday .. 7=Sunday. */
    private fun isWeekend(dow: Int) = dow == 6 || dow == 7

    private fun dayName(dow: Int) = when (dow) {
        1 -> "Mon"; 2 -> "Tue"; 3 -> "Wed"; 4 -> "Thu"
        5 -> "Fri"; 6 -> "Sat"; else -> "Sun"
    }

    private fun clock(minuteOfDay: Double): String {
        val m = minuteOfDay.toInt().coerceIn(0, 1439)
        return "%02d:%02d".format(m / 60, m % 60)
    }
}
