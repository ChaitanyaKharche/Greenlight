package com.greenlight.nav

import com.greenlight.core.LatLon
import com.greenlight.core.bearingDegrees
import com.greenlight.core.bearingDeltaDegrees
import com.greenlight.core.cumulativeDistances
import com.greenlight.core.haversineMeters
import com.greenlight.core.headingAt
import com.greenlight.core.projectOntoPath
import com.greenlight.model.Fix
import com.greenlight.model.TrafficSignal
import kotlin.math.abs

/** A signal ahead of the vehicle, with the distance we will actually travel to reach it. */
data class UpcomingSignal(
    val signal: TrafficSignal,
    val distanceMeters: Double,
    /** Bearing we will be travelling on when we reach it. Selects the right phase. */
    val approachBearing: Double,
    val onRoute: Boolean,
)

/**
 * Signals bound to a planned route.
 *
 * Built once per route, then queried on every fix. Snapping the signals to the polyline up
 * front means the per-fix cost is a projection plus a binary search rather than a full scan.
 */
class RouteSignalIndex(
    val geometry: List<LatLon>,
    signals: List<TrafficSignal>,
    /** How far off the line a signal may sit and still count as on this route. */
    private val corridorMeters: Double = 30.0,
) {
    private val cumulative = cumulativeDistances(geometry)

    /** Signals snapped onto the route, sorted by distance from the route start. */
    private val snapped: List<Pair<Double, TrafficSignal>> = signals.mapNotNull { s ->
        val p = projectOntoPath(s.position, geometry, cumulative) ?: return@mapNotNull null
        if (p.lateralMeters > corridorMeters) return@mapNotNull null
        val heading = headingAt(geometry, cumulative, p.alongMeters) ?: return@mapNotNull null
        p.alongMeters to s.copy(approachBearing = heading)
    }.sortedBy { it.first }

    val routeLengthMeters: Double get() = cumulative.lastOrNull() ?: 0.0
    val signalCount: Int get() = snapped.size

    /** Where we currently are along the route, in metres from the start. Null if far off-route. */
    fun progressMeters(position: LatLon, maxOffRouteMeters: Double = 60.0): Double? {
        val p = projectOntoPath(position, geometry, cumulative) ?: return null
        return if (p.lateralMeters > maxOffRouteMeters) null else p.alongMeters
    }

    /** The next [limit] signals ahead of [progress], nearest first. */
    fun ahead(progress: Double, limit: Int, maxRangeMeters: Double): List<UpcomingSignal> {
        val out = ArrayList<UpcomingSignal>(limit)
        // Small epsilon so a signal we are sitting exactly on does not keep re-triggering.
        val from = progress + 8.0
        var i = snapped.binarySearchBy(from) { it.first }
        if (i < 0) i = -i - 1
        while (i < snapped.size && out.size < limit) {
            val (along, signal) = snapped[i]
            val d = along - progress
            if (d > maxRangeMeters) break
            out.add(
                UpcomingSignal(
                    signal = signal,
                    distanceMeters = d,
                    approachBearing = signal.approachBearing ?: 0.0,
                    onRoute = true,
                )
            )
            i++
        }
        return out
    }
}

/**
 * Free-drive mode: no route, no destination, works while Google Maps owns the screen.
 *
 * We take the signals in a cone ahead of the current heading and keep the ones that are both
 * roughly straight ahead and roughly aligned with where we are pointing. Straight-line distance
 * understates the real approach slightly, which is the conservative direction to be wrong in.
 */
object FreeDriveSignals {

    fun ahead(
        fix: Fix,
        candidates: List<TrafficSignal>,
        limit: Int = 4,
        maxRangeMeters: Double = 900.0,
        /** Half-angle of the search cone. Tight, because a wrong pick gives wrong advice. */
        coneHalfAngleDeg: Double = 28.0,
        /** Below this speed the GPS bearing is noise, so we refuse to guess. */
        minSpeedMps: Double = 3.0,
    ): List<UpcomingSignal> {
        if (!fix.hasBearing || fix.speedMps < minSpeedMps) return emptyList()

        return candidates.mapNotNull { s ->
            val d = haversineMeters(fix.position, s.position)
            if (d < 15.0 || d > maxRangeMeters) return@mapNotNull null
            val toSignal = bearingDegrees(fix.position, s.position)
            val offAxis = abs(bearingDeltaDegrees(fix.bearingDeg, toSignal))
            // Allow a wider cone up close, where a few metres of GPS error swings the bearing a lot.
            val allowed = coneHalfAngleDeg + (60.0 / d.coerceAtLeast(20.0)) * 20.0
            if (offAxis > allowed.coerceAtMost(55.0)) return@mapNotNull null

            UpcomingSignal(
                signal = s,
                // Project onto our direction of travel: the along-track component is the
                // distance that actually matters for timing.
                distanceMeters = d * kotlin.math.cos(Math.toRadians(offAxis)),
                approachBearing = fix.bearingDeg,
                onRoute = false,
            )
        }.sortedBy { it.distanceMeters }.take(limit)
    }
}
