package com.greenlight.spat

import com.greenlight.data.GreenLightDb
import com.greenlight.model.FixedPlanSchedule
import com.greenlight.model.SignalSchedule
import com.greenlight.model.TimingSource
import com.greenlight.model.TrafficSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Timing the user measured by hand.
 *
 * Crude, but for the three lights on your daily commute it beats waiting a fortnight for the
 * learner to converge, and it is exact for fixed-time controllers.
 */
class ManualSpatProvider(
    private val db: GreenLightDb,
    private val clock: TimeContext,
) : SpatProvider {

    override val source = TimingSource.MANUAL

    override fun covers(signal: TrafficSignal) = true

    override suspend fun scheduleFor(
        signal: TrafficSignal,
        approachBearing: Double,
        nowEpochSec: Double,
    ): SignalSchedule? = withContext(Dispatchers.IO) {
        val t = db.manualTiming(signal.id, approachBearing) ?: return@withContext null
        FixedPlanSchedule(
            cycleSec = t.cycleSec,
            greenStartInCycleSec = t.greenStartInCycle,
            greenDurationSec = t.greenDurationSec,
            localMidnightEpochSec = clock.localMidnightEpochSec(nowEpochSec),
            // Controller clocks drift and the user's thumb is not a chronometer.
            sigmaSec = 2.0,
            confidence = 0.8,
            source = TimingSource.MANUAL,
        )
    }
}
