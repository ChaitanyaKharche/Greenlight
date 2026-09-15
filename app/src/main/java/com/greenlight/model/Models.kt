package com.greenlight.model

import com.greenlight.core.LatLon
import com.greenlight.core.mod
import kotlin.math.floor

/** Where a signal's timing came from. Ordered worst-to-best; higher ordinal wins in fusion. */
enum class TimingSource {
    /** Nothing but a guessed default cycle. Advisory is suppressed. */
    NONE,

    /** Hand-entered by the user with a stopwatch. */
    MANUAL,

    /** Inferred on-device from this phone's own stop/go history. */
    LEARNED,

    /** Live SPaT from a real V2I feed (city API, CV pilot, commercial provider). */
    LIVE_SPAT,
}

/** Coarse signal-plan buckets. Real controllers swap timing plans by time of day. */
enum class PlanBucket {
    WEEKDAY_AM_PEAK, WEEKDAY_MIDDAY, WEEKDAY_PM_PEAK, WEEKDAY_EVENING, WEEKDAY_NIGHT,
    WEEKEND_DAY, WEEKEND_NIGHT;

    companion object {
        /** [secondsSinceLocalMidnight] plus a weekend flag maps onto a plan bucket. */
        fun of(secondsSinceLocalMidnight: Double, isWeekend: Boolean): PlanBucket {
            val h = secondsSinceLocalMidnight / 3600.0
            return if (isWeekend) {
                if (h in 7.0..22.0) WEEKEND_DAY else WEEKEND_NIGHT
            } else when {
                h in 6.0..9.5 -> WEEKDAY_AM_PEAK
                h in 9.5..15.5 -> WEEKDAY_MIDDAY
                h in 15.5..19.0 -> WEEKDAY_PM_PEAK
                h in 19.0..22.0 -> WEEKDAY_EVENING
                else -> WEEKDAY_NIGHT
            }
        }
    }
}

/** A signalised intersection, keyed by its OpenStreetMap node id where we have one. */
data class TrafficSignal(
    val id: Long,
    val position: LatLon,
    /**
     * Approach bearing this record describes, in degrees. A crossroads has a different
     * phase per approach, so timing is always stored per (signal, approach).
     */
    val approachBearing: Double? = null,
    val speedLimitMps: Double? = null,
    val name: String? = null,
    /** Junction legs meeting here; 4 for a crossroads, 3 for a T. 0 means unknown. */
    val approaches: Int = 0,
    val totalLanes: Int = 0,
    /** Widest carriageway crossing here, in metres. Drives the pedestrian-clearance floor. */
    val crossingMeters: Double = 0.0,
)

/** An absolute green interval on the wall clock. */
data class GreenWindow(val startEpochSec: Double, val endEpochSec: Double) {
    val duration: Double get() = endEpochSec - startEpochSec
    fun contains(t: Double) = t >= startEpochSec && t < endEpochSec
}

/**
 * Everything the solver needs to know about when a signal is green.
 * Implementations cover both a learned fixed-time plan and a live SPaT feed.
 */
interface SignalSchedule {
    val source: TimingSource

    /** 1-sigma uncertainty on the window edges, in seconds. */
    val sigmaSec: Double

    /** 0..1 self-assessed trust. The UI refuses to advise below a threshold. */
    val confidence: Double

    /** Green intervals overlapping [fromEpochSec, fromEpochSec + horizonSec], soonest first. */
    fun greenWindows(fromEpochSec: Double, horizonSec: Double): List<GreenWindow>
}

/**
 * A repeating fixed-time plan. Phase is measured from local midnight because coordinated
 * controllers lock their offsets to a local master clock, not to the Unix epoch.
 */
data class FixedPlanSchedule(
    val cycleSec: Double,
    /** Offset of the green start within the cycle, relative to local midnight. */
    val greenStartInCycleSec: Double,
    val greenDurationSec: Double,
    /** Epoch second of the most recent local midnight, used to anchor the phase. */
    val localMidnightEpochSec: Double,
    override val sigmaSec: Double,
    override val confidence: Double,
    override val source: TimingSource,
) : SignalSchedule {

    override fun greenWindows(fromEpochSec: Double, horizonSec: Double): List<GreenWindow> {
        if (cycleSec <= 1.0 || greenDurationSec <= 0.0) return emptyList()
        val relStart = fromEpochSec - localMidnightEpochSec
        val relEnd = relStart + horizonSec
        // First cycle index whose green window could still be open at relStart.
        var k = floor((relStart - greenStartInCycleSec) / cycleSec).toLong()
        val out = ArrayList<GreenWindow>()
        var guard = 0
        while (guard++ < 512) {
            val s = greenStartInCycleSec + k * cycleSec
            val e = s + greenDurationSec
            if (e > relStart && s < relEnd) {
                out.add(GreenWindow(localMidnightEpochSec + s, localMidnightEpochSec + e))
            }
            if (s > relEnd) break
            k++
        }
        return out
    }

    /** Seconds until the next green starts, or 0 if green right now. */
    fun timeToGreen(nowEpochSec: Double): Double {
        val phase = (nowEpochSec - localMidnightEpochSec - greenStartInCycleSec).mod(cycleSec)
        return if (phase < greenDurationSec) 0.0 else cycleSec - phase
    }
}

/**
 * A live SPaT reading: "the current state is X and it changes in Y seconds".
 * We project it forward with the known cycle so the corridor solver still has windows to chew on.
 */
data class LiveSpatSchedule(
    val isGreenNow: Boolean,
    val secondsToChange: Double,
    val cycleSec: Double,
    val greenDurationSec: Double,
    val observedAtEpochSec: Double,
    override val sigmaSec: Double,
    override val confidence: Double,
) : SignalSchedule {
    override val source = TimingSource.LIVE_SPAT

    /** Collapses the live reading into the equivalent fixed plan, then reuses its window maths. */
    fun asFixedPlan(): FixedPlanSchedule {
        val nextGreenStart = if (isGreenNow) {
            observedAtEpochSec + secondsToChange - greenDurationSec
        } else {
            observedAtEpochSec + secondsToChange
        }
        return FixedPlanSchedule(
            cycleSec = cycleSec,
            greenStartInCycleSec = (nextGreenStart).mod(cycleSec),
            greenDurationSec = greenDurationSec,
            localMidnightEpochSec = 0.0,
            sigmaSec = sigmaSec,
            confidence = confidence,
            source = TimingSource.LIVE_SPAT,
        )
    }

    override fun greenWindows(fromEpochSec: Double, horizonSec: Double) =
        asFixedPlan().greenWindows(fromEpochSec, horizonSec)
}

/** One pass through a signal, recorded from the phone's own GPS track. */
data class SignalObservation(
    val signalId: Long,
    val approachBearing: Double,
    /** When we crossed (or came to rest at) the stop line. */
    val arrivalEpochSec: Double,
    val stopped: Boolean,
    /**
     * When the queue in front of us started moving — our best proxy for the red-to-green
     * transition. Null when we sailed through on green.
     */
    val departureEpochSec: Double?,
    val localMidnightEpochSec: Double,
    val planBucket: PlanBucket,
)

/** What the driver should actually do. */
enum class GlosaAction {
    /** Stay where you are; you clear the next green. */
    HOLD,

    /** Ease off — you are too early for the window. */
    EASE_OFF,

    /** Safe and legal to pick it up a little. */
    SPEED_UP,

    /** No feasible legal speed clears it. Coast to the line. */
    STOP_EXPECTED,

    /** Not enough data to say anything honest. */
    NO_ADVICE,
}

/** The advisory handed to the UI and the overlay. */
data class GlosaAdvice(
    val action: GlosaAction,
    /** Recommended cruise speed in m/s, null when no speed clears the signal. */
    val targetMps: Double?,
    /** Full feasible speed range, for drawing the band on the dial. */
    val bandMps: com.greenlight.core.SpeedBand?,
    /** Probability of actually clearing the window, given timing and speed-hold uncertainty. */
    val confidence: Double,
    val signal: TrafficSignal?,
    val distanceMeters: Double,
    val etaSeconds: Double?,
    /** Seconds until the light turns green, when we expect to arrive on red. */
    val timeToGreenSec: Double?,
    val speedLimitMps: Double?,
    val source: TimingSource,
    /** How many downstream signals the same speed also clears. */
    val signalsCleared: Int = 0,
    val note: String? = null,
    /**
     * Speed at the moment this advice was produced. Carried so a no-advice state can still
     * show a speedometer without the UI having to reach for the engine's last fix.
     */
    val currentForDisplay: Double = 0.0,
) {
    companion object {
        /**
         * Even with nothing to advise, the driver still benefits from the posted limit and
         * how far the next signal is, so those are carried through rather than blanked.
         */
        fun noAdvice(
            note: String? = null,
            speedLimitMps: Double? = null,
            distanceMeters: Double = Double.NaN,
            signal: TrafficSignal? = null,
        ) = GlosaAdvice(
            action = GlosaAction.NO_ADVICE,
            targetMps = null,
            bandMps = null,
            confidence = 0.0,
            signal = signal,
            distanceMeters = distanceMeters,
            etaSeconds = null,
            timeToGreenSec = null,
            speedLimitMps = speedLimitMps,
            source = TimingSource.NONE,
            note = note,
        )
    }
}

/** A single GPS fix, normalised away from Android's Location class so the core stays testable. */
data class Fix(
    val position: LatLon,
    val speedMps: Double,
    val bearingDeg: Double,
    val epochSec: Double,
    val accuracyMeters: Double,
    val hasBearing: Boolean = true,
)
