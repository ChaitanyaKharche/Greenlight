package com.greenlight.spat

import com.greenlight.core.LatLon
import com.greenlight.model.SignalSchedule
import com.greenlight.model.TimingSource
import com.greenlight.model.TrafficSignal

/**
 * A source of signal timing.
 *
 * The honest position on data availability: the factory systems (Audi's Traffic Light
 * Information, and the equivalents from Porsche and BMW) work because the manufacturer buys
 * a feed from a provider such as Traffic Technology Services, which aggregates live controller
 * state from municipal traffic management centres. That feed is commercial and licensed to the
 * car, not to the phone in your pocket, so a consumer app cannot simply call it.
 *
 * What is actually reachable:
 *  1. [LearnedSpatProvider] - infer fixed-time plans from your own repeated trips. Works
 *     anywhere, needs a handful of passes per intersection, and degrades on actuated signals.
 *  2. [ManualSpatProvider] - stopwatch a light once and type the numbers in. Exact, tedious.
 *  3. [RestSpatProvider] - point it at a genuine SPaT endpoint if your city or a connected
 *     vehicle pilot publishes one. This is the path to parity with the factory systems.
 */
interface SpatProvider {
    val source: TimingSource

    /** Cheap check so the fusion layer can skip providers with nothing to say. */
    fun covers(signal: TrafficSignal): Boolean

    /**
     * Current best schedule for [signal] approached on [approachBearing].
     * Null means "no opinion", which is different from "definitely no green".
     */
    suspend fun scheduleFor(
        signal: TrafficSignal,
        approachBearing: Double,
        nowEpochSec: Double,
    ): SignalSchedule?
}

/**
 * Picks the best available schedule per signal.
 *
 * Priority is by [TimingSource] ordinal: a live feed beats a hand-entered plan, which beats
 * something we inferred. Within the same source, higher confidence wins.
 */
class SpatFusion(private val providers: List<SpatProvider>) {

    suspend fun scheduleFor(
        signal: TrafficSignal,
        approachBearing: Double,
        nowEpochSec: Double,
    ): SignalSchedule? {
        var best: SignalSchedule? = null
        for (p in providers) {
            if (!p.covers(signal)) continue
            val candidate = runCatching { p.scheduleFor(signal, approachBearing, nowEpochSec) }
                .getOrNull() ?: continue
            val incumbent = best
            best = when {
                incumbent == null -> candidate
                candidate.source.ordinal > incumbent.source.ordinal -> candidate
                candidate.source == incumbent.source &&
                    candidate.confidence > incumbent.confidence -> candidate
                else -> incumbent
            }
        }
        return best
    }
}

/** Where a live feed says a signal is, so we can match it to our OSM node. */
data class SpatIntersection(val id: String, val position: LatLon, val name: String?)
