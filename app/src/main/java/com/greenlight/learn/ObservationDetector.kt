package com.greenlight.learn

import com.greenlight.core.bearingDeltaDegrees
import com.greenlight.core.haversineMeters
import com.greenlight.model.Fix
import com.greenlight.model.PlanBucket
import com.greenlight.model.SignalObservation
import com.greenlight.model.TrafficSignal
import kotlin.math.abs

/**
 * Turns a raw GPS stream into red-to-green observations.
 *
 * Every pass through a signal is one datum:
 *  - rolled through without dropping below the stop threshold -> the light was green on arrival
 *  - came to rest, then moved off -> the moment we moved off approximates the green onset,
 *    biased late by however many cars were ahead of us (see [TimingEstimator.QUEUE_BIAS_SEC])
 *
 * The detector is a small state machine per signal so it survives the jitter you get when a
 * phone is sitting stationary and the fix wanders.
 */
class ObservationDetector(
    private val captureRadiusMeters: Double = 70.0,
    private val stoppedMps: Double = 1.0,
    private val movingMps: Double = 2.2,
    private val approachConeDeg: Double = 60.0,
    private val localMidnightProvider: (Double) -> Double,
    private val isWeekendProvider: (Double) -> Boolean,
) {

    private enum class Phase { APPROACHING, STOPPED, DEPARTED }

    private data class Track(
        var phase: Phase,
        var approachBearing: Double,
        var enteredAt: Double,
        var stoppedAt: Double? = null,
        var departedAt: Double? = null,
        var minDistance: Double,
        var lastDistance: Double,
        var everStopped: Boolean = false,
        var arrivalAt: Double,
    )

    private val tracks = HashMap<Long, Track>()

    /**
     * Feed one fix plus the signals currently nearby. Returns any observations completed
     * by this fix — normally empty, occasionally one.
     */
    fun onFix(fix: Fix, nearby: List<TrafficSignal>): List<SignalObservation> {
        val completed = ArrayList<SignalObservation>()

        for (signal in nearby) {
            val d = haversineMeters(fix.position, signal.position)
            if (d > captureRadiusMeters) continue

            // Only count approaches roughly aligned with the direction we are travelling,
            // otherwise the opposite carriageway pollutes the phase estimate.
            val approach = if (fix.hasBearing) fix.bearingDeg else continue
            signal.approachBearing?.let {
                if (abs(bearingDeltaDegrees(it, approach)) > approachConeDeg) return@let
            }

            val t = tracks.getOrPut(signal.id) {
                Track(
                    phase = Phase.APPROACHING,
                    approachBearing = approach,
                    enteredAt = fix.epochSec,
                    minDistance = d,
                    lastDistance = d,
                    arrivalAt = fix.epochSec,
                )
            }

            if (d < t.minDistance) {
                t.minDistance = d
                t.arrivalAt = fix.epochSec
            }

            when (t.phase) {
                Phase.APPROACHING -> if (fix.speedMps < stoppedMps) {
                    t.phase = Phase.STOPPED
                    t.everStopped = true
                    t.stoppedAt = fix.epochSec
                }

                Phase.STOPPED -> if (fix.speedMps > movingMps) {
                    t.phase = Phase.DEPARTED
                    t.departedAt = fix.epochSec
                }

                Phase.DEPARTED -> Unit
            }
            t.lastDistance = d
        }

        // A track resolves once we are clearly past and pulling away.
        val iterator = tracks.entries.iterator()
        while (iterator.hasNext()) {
            val (id, t) = iterator.next()
            val signal = nearby.firstOrNull { it.id == id }
            val distance = signal?.let { haversineMeters(fix.position, it.position) }
                ?: Double.MAX_VALUE
            val leaving = distance > captureRadiusMeters * 1.15 && distance > t.lastDistance
            val stale = fix.epochSec - t.enteredAt > 600.0

            if (leaving || stale) {
                if (!stale && t.minDistance < captureRadiusMeters * 0.6) {
                    val midnight = localMidnightProvider(t.arrivalAt)
                    completed.add(
                        SignalObservation(
                            signalId = id,
                            approachBearing = t.approachBearing,
                            arrivalEpochSec = t.stoppedAt ?: t.arrivalAt,
                            stopped = t.everStopped,
                            departureEpochSec = t.departedAt,
                            localMidnightEpochSec = midnight,
                            planBucket = PlanBucket.of(
                                (t.arrivalAt - midnight),
                                isWeekendProvider(t.arrivalAt),
                            ),
                        )
                    )
                }
                iterator.remove()
            }
        }
        return completed
    }

    fun reset() = tracks.clear()
}
