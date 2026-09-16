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
              fetched_at REAL NOT NULL,
              approaches INTEGER NOT NULL DEFAULT 0,
              total_lanes INTEGER NOT NULL DEFAULT 0,
              crossing_m REAL NOT NULL DEFAULT 0
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
        createLogTable(db)
        createPlaceTables(db)
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
        // Observations take days of driving to gather, so upgrades are additive.
        if (oldVersion < 2) createLogTable(db)
        if (oldVersion < 3) createPlaceTables(db)
        if (oldVersion < 4) {
            // Older rows simply report unknown geometry, and the prior is skipped for them
            // until the next refetch fills it in.
            db.execSQL("ALTER TABLE signals ADD COLUMN approaches INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE signals ADD COLUMN total_lanes INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE signals ADD COLUMN crossing_m REAL NOT NULL DEFAULT 0")
        }
    }

    private fun createLogTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS debug_log (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              ts REAL NOT NULL,
              tag TEXT NOT NULL,
              msg TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createPlaceTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS places (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              lat REAL NOT NULL,
              lon REAL NOT NULL,
              label TEXT,
              visits INTEGER NOT NULL DEFAULT 0,
              last_visit REAL NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS visits (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              place_id INTEGER NOT NULL,
              arrival_epoch REAL NOT NULL,
              day_of_week INTEGER NOT NULL,
              minute_of_day REAL NOT NULL,
              origin_place_id INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_visits_place ON visits(place_id)")
    }

    /** Finds an existing place within [radiusMeters], else creates one. Returns its id. */
    fun upsertPlace(lat: Double, lon: Double, radiusMeters: Double, nowEpoch: Double): Long {
        // Cheap bounding-box prefilter, then exact distance on the handful that survive.
        val dLat = radiusMeters / 111_320.0
        val dLon = radiusMeters / (111_320.0 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        var best: Long = -1
        var bestDist = Double.MAX_VALUE
        readableDatabase.rawQuery(
            "SELECT id, lat, lon FROM places WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
            arrayOf("${lat - dLat}", "${lat + dLat}", "${lon - dLon}", "${lon + dLon}"),
        ).use { c ->
            while (c.moveToNext()) {
                val d = com.greenlight.core.haversineMeters(
                    com.greenlight.core.LatLon(lat, lon),
                    com.greenlight.core.LatLon(c.getDouble(1), c.getDouble(2)),
                )
                if (d < bestDist && d <= radiusMeters) {
                    bestDist = d; best = c.getLong(0)
                }
            }
        }
        if (best >= 0) {
            writableDatabase.execSQL(
                "UPDATE places SET visits = visits + 1, last_visit = ? WHERE id = ?",
                arrayOf<Any>(nowEpoch, best),
            )
            return best
        }
        return writableDatabase.insert(
            "places", null,
            ContentValues().apply {
                put("lat", lat); put("lon", lon); put("visits", 1); put("last_visit", nowEpoch)
            },
        )
    }

    fun insertVisit(
        placeId: Long,
        arrivalEpoch: Double,
        dayOfWeek: Int,
        minuteOfDay: Double,
        originPlaceId: Long?,
    ) {
        writableDatabase.insert(
            "visits", null,
            ContentValues().apply {
                put("place_id", placeId)
                put("arrival_epoch", arrivalEpoch)
                put("day_of_week", dayOfWeek)
                put("minute_of_day", minuteOfDay)
                originPlaceId?.let { put("origin_place_id", it) }
            },
        )
    }

    fun renamePlace(placeId: Long, label: String) {
        writableDatabase.execSQL(
            "UPDATE places SET label = ? WHERE id = ?", arrayOf<Any>(label, placeId),
        )
    }

    fun deletePlace(placeId: Long) {
        writableDatabase.execSQL("DELETE FROM visits WHERE place_id = ?", arrayOf(placeId))
        writableDatabase.execSQL("DELETE FROM places WHERE id = ?", arrayOf(placeId))
    }

    data class PlaceRow(
        val id: Long,
        val position: com.greenlight.core.LatLon,
        val label: String?,
        val visits: Int,
        val lastVisit: Double,
    )

    fun allPlaces(): List<PlaceRow> {
        val out = ArrayList<PlaceRow>()
        readableDatabase.rawQuery(
            "SELECT id, lat, lon, label, visits, last_visit FROM places ORDER BY visits DESC", null,
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    PlaceRow(
                        id = c.getLong(0),
                        position = com.greenlight.core.LatLon(c.getDouble(1), c.getDouble(2)),
                        label = if (c.isNull(3)) null else c.getString(3),
                        visits = c.getInt(4),
                        lastVisit = c.getDouble(5),
                    )
                )
            }
        }
        return out
    }

    data class VisitRow(
        val placeId: Long,
        val dayOfWeek: Int,
        val minuteOfDay: Double,
        val originPlaceId: Long?,
        val arrivalEpoch: Double,
    )

    fun allVisits(limit: Int = 4000): List<VisitRow> {
        val out = ArrayList<VisitRow>()
        readableDatabase.rawQuery(
            "SELECT place_id, day_of_week, minute_of_day, origin_place_id, arrival_epoch " +
                "FROM visits ORDER BY arrival_epoch DESC LIMIT ?",
            arrayOf("$limit"),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    VisitRow(
                        placeId = c.getLong(0),
                        dayOfWeek = c.getInt(1),
                        minuteOfDay = c.getDouble(2),
                        originPlaceId = if (c.isNull(3)) null else c.getLong(3),
                        arrivalEpoch = c.getDouble(4),
                    )
                )
            }
        }
        return out
    }

    fun insertLog(ts: Double, tag: String, msg: String, maxRows: Int) {
        val db = writableDatabase
        db.insert(
            "debug_log", null,
            ContentValues().apply {
                put("ts", ts); put("tag", tag); put("msg", msg)
            },
        )
        // Trim occasionally rather than on every insert; the log is a ring buffer, not a ledger.
        if ((ts * 10).toLong() % 25L == 0L) {
            db.execSQL(
                "DELETE FROM debug_log WHERE id NOT IN " +
                    "(SELECT id FROM debug_log ORDER BY id DESC LIMIT ?)",
                arrayOf(maxRows),
            )
        }
    }

    fun recentLogs(limit: Int): List<Triple<Double, String, String>> {
        val out = ArrayList<Triple<Double, String, String>>()
        readableDatabase.rawQuery(
            "SELECT ts, tag, msg FROM debug_log ORDER BY id DESC LIMIT ?",
            arrayOf("$limit"),
        ).use { c -> while (c.moveToNext()) out.add(Triple(c.getDouble(0), c.getString(1), c.getString(2))) }
        return out.reversed()
    }

    fun clearLogs() = writableDatabase.execSQL("DELETE FROM debug_log")

    fun cachedSignalCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM signals", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun stoppedObservationCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM observations WHERE stopped = 1", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    /** Most recent passes across all signals, newest first. For the diagnostics report. */
    fun recentObservations(limit: Int): List<SignalObservation> {
        val out = ArrayList<SignalObservation>()
        readableDatabase.rawQuery(
            "SELECT signal_id, approach_bearing, arrival_epoch, stopped, departure_epoch, " +
                "local_midnight, plan_bucket FROM observations ORDER BY arrival_epoch DESC LIMIT ?",
            arrayOf("$limit"),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    SignalObservation(
                        signalId = c.getLong(0),
                        approachBearing = c.getDouble(1),
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
                        put("approaches", s.approaches)
                        put("total_lanes", s.totalLanes)
                        put("crossing_m", s.crossingMeters)
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
            "SELECT id, lat, lon, speed_limit_mps, name, approaches, total_lanes, crossing_m " +
                "FROM signals WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
            arrayOf("$south", "$north", "$west", "$east"),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    TrafficSignal(
                        id = c.getLong(0),
                        position = LatLon(c.getDouble(1), c.getDouble(2)),
                        speedLimitMps = if (c.isNull(3)) null else c.getDouble(3),
                        name = if (c.isNull(4)) null else c.getString(4),
                        approaches = c.getInt(5),
                        totalLanes = c.getInt(6),
                        crossingMeters = c.getDouble(7),
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

    /**
     * Junctions where at least one stop has been captured. Note this is NOT the same as
     * junctions the app can advise on: it previously carried that label and badly overstated
     * progress, reporting "2 signals with timing" when neither had enough samples to estimate
     * anything. Use [signalsReadyToAdvise] for the number that actually matters.
     */
    fun signalsWithAStop(): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(DISTINCT signal_id) FROM observations WHERE departure_epoch IS NOT NULL",
            null,
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Every (signal, plan bucket, approach octant) group, with its observation counts. */
    fun observationGroups(): List<GroupCount> {
        val out = ArrayList<GroupCount>()
        readableDatabase.rawQuery(
            "SELECT signal_id, plan_bucket, approach_bearing, " +
                "SUM(CASE WHEN departure_epoch IS NOT NULL THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN stopped = 0 THEN 1 ELSE 0 END) " +
                "FROM observations GROUP BY signal_id, plan_bucket, " +
                "CAST(((approach_bearing + 22.5) % 360) / 45 AS INTEGER)",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    GroupCount(
                        signalId = c.getLong(0),
                        planBucket = c.getString(1),
                        approachOctant = octantOf(c.getDouble(2)),
                        departures = c.getInt(3),
                        greenPasses = c.getInt(4),
                    )
                )
            }
        }
        return out
    }

    data class GroupCount(
        val signalId: Long,
        val planBucket: String,
        val approachOctant: Int,
        val departures: Int,
        val greenPasses: Int,
    )

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
        private const val VERSION = 4

        /** Buckets a bearing into one of 8 compass octants so opposing approaches never mix. */
        fun octantOf(bearingDeg: Double): Int {
            val b = ((bearingDeg % 360.0) + 360.0) % 360.0
            return (((b + 22.5) / 45.0).toInt()) % 8
        }
    }
}
