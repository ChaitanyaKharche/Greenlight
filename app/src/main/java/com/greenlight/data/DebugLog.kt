package com.greenlight.data

import android.content.Context
import com.greenlight.learn.TimingEstimator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small on-device event log.
 *
 * The app runs where a laptop and `adb logcat` are not: in a car. Without a record of what
 * the engine actually did on a drive there is no way to tell "no signals nearby" apart from
 * "Overpass was down" apart from "the detector never fired". This writes the handful of
 * events that distinguish those to SQLite, so a drive can be reconstructed afterwards.
 *
 * Deliberately coarse. Logging every GPS fix at 1 Hz would bury the signal and churn the disk.
 */
object DebugLog {

    private const val MAX_ROWS = 1200
    private var db: GreenLightDb? = null

    /** Rate limiter for events that would otherwise fire on every fix. */
    private val lastLogged = HashMap<String, Double>()

    fun attach(context: Context) {
        if (db == null) db = GreenLightDb(context.applicationContext)
    }

    fun attach(database: GreenLightDb) {
        db = database
    }

    fun log(tag: String, message: String) {
        val now = System.currentTimeMillis() / 1000.0
        runCatching { db?.insertLog(now, tag, message, MAX_ROWS) }
    }

    /** Logs at most once per [everySec] for a given [key]. For per-fix events. */
    fun throttled(key: String, everySec: Double, tag: String, message: () -> String) {
        val now = System.currentTimeMillis() / 1000.0
        val last = lastLogged[key]
        if (last != null && now - last < everySec) return
        lastLogged[key] = now
        log(tag, message())
    }

    fun clearThrottle() = lastLogged.clear()

    /** Mirrors the estimator's own bar, so the report never claims more than it can do. */
    private fun readyToAdvise(g: GreenLightDb.GroupCount): Boolean {
        val weighted = (if (g.departures >= 2) g.departures.toDouble() else 0.0) +
            (if (g.greenPasses >= 3) TimingEstimator.GREEN_PASS_WEIGHT * g.greenPasses else 0.0)
        return weighted >= TimingEstimator.MIN_EFFECTIVE_SAMPLES
    }

    /** A shareable plain-text report. Kept small enough to paste into a chat. */
    fun report(database: GreenLightDb, maxEvents: Int = 250): String {
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.appendLine("GreenLight diagnostics")
        sb.appendLine("generated ${fmt.format(Date())}")
        sb.appendLine()
        sb.appendLine("-- totals --")
        sb.appendLine("signals cached      : ${database.cachedSignalCount()}")
        sb.appendLine("passes recorded     : ${database.observationCount()}")
        sb.appendLine("passes with a stop  : ${database.stoppedObservationCount()}")
        sb.appendLine("junctions w/ a stop : ${database.signalsWithAStop()}")
        val groups = database.observationGroups()
        sb.appendLine("approach groups     : ${groups.size}")
        sb.appendLine("ready to advise     : ${groups.count { readyToAdvise(it) }}")
        sb.appendLine()
        sb.appendLine("-- per approach (stops / green passes) --")
        if (groups.isEmpty()) sb.appendLine("(none)")
        groups.sortedByDescending { it.departures * 10 + it.greenPasses }.take(12).forEach { g ->
            val mark = if (readyToAdvise(g)) "READY" else "     "
            sb.appendLine(
                "$mark signal=${g.signalId} octant=${g.approachOctant} " +
                    "${g.planBucket}: ${g.departures} stops, ${g.greenPasses} passes"
            )
        }
        sb.appendLine()
        sb.appendLine("-- recent passes --")
        val passes = database.recentObservations(15)
        if (passes.isEmpty()) sb.appendLine("(none)")
        passes.forEach { o ->
            sb.appendLine(
                "${fmt.format(Date((o.arrivalEpochSec * 1000).toLong()))} " +
                    "signal=${o.signalId} bearing=${o.approachBearing.toInt()} " +
                    "stopped=${o.stopped} " +
                    "departure=${o.departureEpochSec?.let { "+%.0fs".format(it - o.arrivalEpochSec) } ?: "-"} " +
                    "plan=${o.planBucket}"
            )
        }
        sb.appendLine()
        sb.appendLine("-- recent events --")
        database.recentLogs(maxEvents).forEach { (ts, tag, msg) ->
            sb.appendLine("${fmt.format(Date((ts * 1000).toLong()))} [$tag] $msg")
        }
        return sb.toString()
    }
}
