package com.greenlight.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.greenlight.core.LatLon
import com.greenlight.model.PlanBucket
import com.greenlight.model.SignalObservation
import com.greenlight.model.TrafficSignal

/**
 * Local store for cached signal geometry and our own observation history.
 *
 * Deliberately hand-rolled rather than Room: the schema is two tables, and skipping the
 * annotation processor keeps the build free of a KSP-to-Kotlin version pin.
 */
class GreenLightDb(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE signals (
              id INTEGER PRIMARY KEY,
              lat REAL NOT NULL,
              lon REAL NOT NULL,
              speed_limit_mps REAL,
              name TEXT,
              fetched_at REAL NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_signals_pos ON signals(lat, lon)")
        db.execSQL(
            """
            CREATE TABLE observations (
              rowid_ INTEGER PRIMARY KEY AUTOINCREMENT,
              signal_id INTEGER NOT NULL,
              approach_bearing REAL NOT NULL,
              arrival_epoch REAL NOT NULL,
              stopped INTEGER NOT NULL,
              departure_epoch REAL,
              local_midnight REAL NOT NULL,
              plan_bucket TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_obs_signal ON observations(signal_id, plan_bucket)")
        db.execSQL(
            """
            CREATE TABLE manual_timing (
              signal_id INTEGER NOT NULL,
              approach_octant INTEGER NOT NULL,
              cycle_sec REAL NOT NULL,
              green_start_in_cycle REAL NOT NULL,
              green_duration_sec REAL NOT NULL,
              local_midnight REAL NOT NULL,
              PRIMARY KEY (signal_id, approach_octant)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Observations are cheap to re-gather and the schema is young; just start over.
        db.execSQL("DROP TABLE IF EXISTS signals")
        db.execSQL("DROP TABLE IF EXISTS observations")
        db.execSQL("DROP TABLE IF EXISTS manual_timing")
        onCreate(db)
    }

    fun upsertSignals(signals: List<TrafficSignal>, fetchedAt: Double) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (s in signals) {
                db.insertWithOnConflict(
                    "signals", null,
                    ContentValues().apply {
                        put("id", s.id)
                        put("lat", s.position.lat)
                        put("lon", s.position.lon)
                        s.speedLimitMps?.let { put("speed_limit_mps", it) }
                        s.name?.let { put("name", it) }
                        put("fetched_at", fetchedAt)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Signals inside a lat/lon box. Cheap enough to call on every route rebuild. */
    fun signalsInBox(south: Double, west: Double, north: Double, east: Double): List<TrafficSignal> {
        val out = ArrayList<TrafficSignal>()
        readableDatabase.rawQuery(
            "SELECT id, lat, lon, speed_limit_mps, name FROM signals " +
                "WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
            arrayOf("$south", "$north", "$west", "$east"),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    TrafficSignal(
                        id = c.getLong(0),
                        position = LatLon(c.getDouble(1), c.getDouble(2)),
                        speedLimitMps = if (c.isNull(3)) null else c.getDouble(3),
                        name = if (c.isNull(4)) null else c.getString(4),
                    )
                )
            }
        }
        return out
    }

    fun insertObservation(o: SignalObservation) {
        writableDatabase.insert(
            "observations", null,
            ContentValues().apply {
                put("signal_id", o.signalId)
                put("approach_bearing", o.approachBearing)
                put("arrival_epoch", o.arrivalEpochSec)
                put("stopped", if (o.stopped) 1 else 0)
                o.departureEpochSec?.let { put("departure_epoch", it) }
                put("local_midnight", o.localMidnightEpochSec)
                put("plan_bucket", o.planBucket.name)
            },
        )
    }

    /**
     * Observations for one signal, restricted to a plan bucket and an approach octant.
     * A crossroads runs different phases per approach, so mixing them would be meaningless.
     */
    fun observationsFor(
        signalId: Long,
        bucket: PlanBucket,
        approachOctant: Int,
        limit: Int = 400,
    ): List<SignalObservation> {
        val out = ArrayList<SignalObservation>()
        readableDatabase.rawQuery(
            "SELECT signal_id, approach_bearing, arrival_epoch, stopped, departure_epoch, " +
                "local_midnight, plan_bucket FROM observations " +
                "WHERE signal_id = ? AND plan_bucket = ? " +
                "ORDER BY arrival_epoch DESC LIMIT ?",
            arrayOf("$signalId", bucket.name, "$limit"),
        ).use { c ->
            while (c.moveToNext()) {
                val bearing = c.getDouble(1)
                if (octantOf(bearing) != approachOctant) continue
                out.add(
                    SignalObservation(
                        signalId = c.getLong(0),
                        approachBearing = bearing,
                        arrivalEpochSec = c.getDouble(2),
                        stopped = c.getInt(3) == 1,
                        departureEpochSec = if (c.isNull(4)) null else c.getDouble(4),
                        localMidnightEpochSec = c.getDouble(5),
                        planBucket = PlanBucket.valueOf(c.getString(6)),
                    )
                )
            }
        }
        return out
    }

    fun observationCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM observations", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun learnedSignalCount(): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(DISTINCT signal_id) FROM observations WHERE departure_epoch IS NOT NULL",
            null,
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun saveManualTiming(
        signalId: Long,
        approachBearing: Double,
        cycleSec: Double,
        greenStartInCycle: Double,
        greenDurationSec: Double,
        localMidnight: Double,
    ) {
        writableDatabase.insertWithOnConflict(
            "manual_timing", null,
            ContentValues().apply {
                put("signal_id", signalId)
                put("approach_octant", octantOf(approachBearing))
                put("cycle_sec", cycleSec)
                put("green_start_in_cycle", greenStartInCycle)
                put("green_duration_sec", greenDurationSec)
                put("local_midnight", localMidnight)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    data class ManualTiming(
        val cycleSec: Double,
        val greenStartInCycle: Double,
        val greenDurationSec: Double,
        val localMidnight: Double,
    )

    fun manualTiming(signalId: Long, approachBearing: Double): ManualTiming? =
        readableDatabase.rawQuery(
            "SELECT cycle_sec, green_start_in_cycle, green_duration_sec, local_midnight " +
                "FROM manual_timing WHERE signal_id = ? AND approach_octant = ?",
            arrayOf("$signalId", "${octantOf(approachBearing)}"),
        ).use { c ->
            if (c.moveToFirst()) {
                ManualTiming(c.getDouble(0), c.getDouble(1), c.getDouble(2), c.getDouble(3))
            } else null
        }

    fun clearObservations() {
        writableDatabase.execSQL("DELETE FROM observations")
    }

    companion object {
        private const val NAME = "greenlight.db"
        private const val VERSION = 1

        /** Buckets a bearing into one of 8 compass octants so opposing approaches never mix. */
        fun octantOf(bearingDeg: Double): Int {
            val b = ((bearingDeg % 360.0) + 360.0) % 360.0
            return (((b + 22.5) / 45.0).toInt()) % 8
        }
    }
}
