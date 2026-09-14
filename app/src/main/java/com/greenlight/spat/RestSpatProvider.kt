package com.greenlight.spat

import com.greenlight.core.LatLon
import com.greenlight.core.haversineMeters
import com.greenlight.data.Endpoints
import com.greenlight.data.httpClient
import com.greenlight.model.LiveSpatSchedule
import com.greenlight.model.SignalSchedule
import com.greenlight.model.TimingSource
import com.greenlight.model.TrafficSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * Talks to a real SPaT endpoint, when you have one.
 *
 * There is no single national feed to point this at. Coverage is a patchwork of connected
 * vehicle pilots and individual traffic management centres, and most published SAE J2735 SPaT
 * streams sit behind an agency agreement. Rather than hard-code one city, this provider takes a
 * URL template and a small, obvious JSON shape, and leaves the adapter as the integration point:
 *
 *     GET <baseUrl>?lat={lat}&lon={lon}&radius={radius}
 *
 *     {
 *       "intersections": [
 *         {
 *           "id": "1234",
 *           "lat": 42.3601, "lon": -71.0589,
 *           "approaches": [
 *             { "bearing": 90, "state": "GREEN", "secondsToChange": 12.5,
 *               "cycleSec": 90, "greenSec": 32 }
 *           ]
 *         }
 *       ]
 *     }
 *
 * If your feed speaks raw J2735 over MQTT instead, subclass and translate into
 * [LiveSpatSchedule]; everything downstream is agnostic to how the bytes arrived.
 */
class RestSpatProvider(
    private val baseUrl: String,
    private val apiKeyHeader: String? = null,
    private val apiKey: String? = null,
    /** How far a feed intersection may sit from the OSM node before we refuse to match them. */
    private val matchRadiusMeters: Double = 40.0,
    private val pollIntervalSec: Double = 2.0,
    private val queryRadiusMeters: Int = 1500,
) : SpatProvider {

    override val source = TimingSource.LIVE_SPAT

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mutex = Mutex()
    @Volatile
    private var cache: List<FeedIntersection> = emptyList()
    private var cachedAt = 0.0
    private var cacheCentre: LatLon? = null

    internal data class FeedApproach(
        val bearing: Double,
        val isGreen: Boolean,
        val secondsToChange: Double,
        val cycleSec: Double,
        val greenSec: Double,
    )

    internal data class FeedIntersection(
        val id: String,
        val position: LatLon,
        val approaches: List<FeedApproach>,
    )

    /** Only claims coverage once a poll has actually seen this intersection. */
    override fun covers(signal: TrafficSignal): Boolean =
        cache.any { haversineMeters(it.position, signal.position) <= matchRadiusMeters }

    /** Call periodically from the engine with the vehicle's current position. */
    suspend fun refresh(near: LatLon, nowEpochSec: Double) {
        mutex.withLock {
            val moved = cacheCentre?.let { haversineMeters(it, near) > 600.0 } ?: true
            if (!moved && nowEpochSec - cachedAt < pollIntervalSec) return
        }
        val fetched = runCatching { fetch(near) }.getOrNull() ?: return
        mutex.withLock {
            cache = fetched
            cachedAt = nowEpochSec
            cacheCentre = near
        }
    }

    override suspend fun scheduleFor(
        signal: TrafficSignal,
        approachBearing: Double,
        nowEpochSec: Double,
    ): SignalSchedule? {
        val snapshot = mutex.withLock { cache to cachedAt }
        val (intersections, observedAt) = snapshot
        // A SPaT reading more than a few seconds old is worse than useless near a change.
        if (nowEpochSec - observedAt > 8.0) return null

        val match = intersections
            .filter { haversineMeters(it.position, signal.position) <= matchRadiusMeters }
            .minByOrNull { haversineMeters(it.position, signal.position) } ?: return null

        val approach = match.approaches.minByOrNull {
            kotlin.math.abs(com.greenlight.core.bearingDeltaDegrees(it.bearing, approachBearing))
        } ?: return null
        if (kotlin.math.abs(
                com.greenlight.core.bearingDeltaDegrees(approach.bearing, approachBearing)
            ) > 55.0
        ) return null

        // Age the countdown forward to now, rather than pretending the poll just happened.
        val age = nowEpochSec - observedAt
        val remaining = approach.secondsToChange - age
        if (remaining <= 0.0) return null

        return LiveSpatSchedule(
            isGreenNow = approach.isGreen,
            secondsToChange = remaining,
            cycleSec = approach.cycleSec,
            greenDurationSec = approach.greenSec,
            observedAtEpochSec = nowEpochSec,
            sigmaSec = 0.6 + age * 0.15,
            confidence = 0.95,
        )
    }

    private suspend fun fetch(near: LatLon): List<FeedIntersection> = withContext(Dispatchers.IO) {
        val url = baseUrl
            .replace("{lat}", near.lat.toString())
            .replace("{lon}", near.lon.toString())
            .replace("{radius}", queryRadiusMeters.toString())
            .let { if ("{lat}" in baseUrl) it else "$it?lat=${near.lat}&lon=${near.lon}&radius=$queryRadiusMeters" }

        val builder = Request.Builder().url(url).header("User-Agent", Endpoints.USER_AGENT)
        if (apiKeyHeader != null && apiKey != null) builder.header(apiKeyHeader, apiKey)

        httpClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) error("SPaT feed returned HTTP ${resp.code}")
            parse(resp.body?.string().orEmpty())
        }
    }

    internal fun parse(body: String): List<FeedIntersection> {
        val root = json.parseToJsonElement(body).jsonObject
        val list = root["intersections"]?.jsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el.jsonObject
            val lat = o["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = o["lon"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            val approaches = o["approaches"]?.jsonArray.orEmpty().mapNotNull { a ->
                val ao = a.jsonObject
                val state = ao["state"]?.jsonPrimitive?.content?.uppercase() ?: return@mapNotNull null
                FeedApproach(
                    bearing = ao["bearing"]?.jsonPrimitive?.content?.toDoubleOrNull()
                        ?: return@mapNotNull null,
                    // Anything that is not a protected or permissive green is treated as red;
                    // advising a driver into an amber is not a trade-off worth making.
                    isGreen = state.contains("GREEN"),
                    secondsToChange = ao["secondsToChange"]?.jsonPrimitive?.content?.toDoubleOrNull()
                        ?: return@mapNotNull null,
                    cycleSec = ao["cycleSec"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 90.0,
                    greenSec = ao["greenSec"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 35.0,
                )
            }
            if (approaches.isEmpty()) return@mapNotNull null
            FeedIntersection(
                id = o["id"]?.jsonPrimitive?.content ?: "$lat,$lon",
                position = LatLon(lat, lon),
                approaches = approaches,
            )
        }
    }
}
