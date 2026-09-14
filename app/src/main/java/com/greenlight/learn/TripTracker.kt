package com.greenlight.learn

import com.greenlight.core.LatLon
import com.greenlight.core.haversineMeters
import com.greenlight.model.Fix

/** A completed journey: where it started, where it ended, when it arrived. */
data class Trip(
    val origin: LatLon?,
    val destination: LatLon,
    val startEpochSec: Double,
    val arrivalEpochSec: Double,
    val distanceMeters: Double,
)

/**
 * Splits a GPS stream into trips.
 *
 * A trip ends when the vehicle has been stationary for [dwellToEndSec] and starts again once
 * it is properly moving. The dwell threshold is the whole trick: too short and every red light
 * becomes a "destination", too long and a coffee stop is swallowed into the commute. Three
 * minutes sits comfortably above the longest signal cycle we ever model.
 */
class TripTracker(
    private val dwellToEndSec: Double = 180.0,
    private val movingMps: Double = 4.0,
    private val stoppedMps: Double = 1.0,
    /** Trips shorter than this are parking manoeuvres, not journeys. */
    private val minTripMeters: Double = 400.0,
) {
    private enum class State { IDLE, MOVING, DWELLING }

    private var state = State.IDLE
    private var tripStart: LatLon? = null
    private var tripStartAt = 0.0
    private var lastOrigin: LatLon? = null
    private var dwellSince = 0.0
    private var dwellAt: LatLon? = null
    private var lastFix: LatLon? = null
    private var distance = 0.0

    /** Feed each fix. Returns a Trip on the fix that completes one. */
    fun onFix(fix: Fix): Trip? {
        lastFix?.let { distance += haversineMeters(it, fix.position) }
        lastFix = fix.position

        when (state) {
            State.IDLE -> if (fix.speedMps > movingMps) {
                state = State.MOVING
                tripStart = fix.position
                tripStartAt = fix.epochSec
                distance = 0.0
            }

            State.MOVING -> if (fix.speedMps < stoppedMps) {
                state = State.DWELLING
                dwellSince = fix.epochSec
                dwellAt = fix.position
            }

            State.DWELLING -> {
                if (fix.speedMps > movingMps) {
                    // False alarm - a long red, not an arrival.
                    state = State.MOVING
                } else if (fix.epochSec - dwellSince >= dwellToEndSec) {
                    val destination = dwellAt ?: fix.position
                    val trip = if (distance >= minTripMeters) {
                        Trip(
                            origin = tripStart,
                            destination = destination,
                            startEpochSec = tripStartAt,
                            arrivalEpochSec = dwellSince,
                            distanceMeters = distance,
                        )
                    } else null
                    lastOrigin = destination
                    state = State.IDLE
                    tripStart = null
                    distance = 0.0
                    return trip
                }
            }
        }
        return null
    }

    /** Called when the service shuts down mid-trip, so the journey is not lost. */
    fun flush(atEpochSec: Double): Trip? {
        if (state == State.IDLE) return null
        val destination = lastFix ?: return null
        val trip = if (distance >= minTripMeters) {
            Trip(tripStart, destination, tripStartAt, atEpochSec, distance)
        } else null
        state = State.IDLE
        tripStart = null
        distance = 0.0
        return trip
    }

    fun reset() {
        state = State.IDLE
        tripStart = null
        lastFix = null
        distance = 0.0
    }
}
