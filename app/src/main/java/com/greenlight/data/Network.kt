package com.greenlight.data

import com.greenlight.core.LatLon
import com.greenlight.core.boundingBox
import com.greenlight.core.decodePolyline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * All three services below are free community infrastructure with published fair-use
 * policies. They require an identifying User-Agent and they rate-limit; if you run this
 * for more than personal use, self-host or move to a paid endpoint.
 *
 *  - Overpass API  (OpenStreetMap query)  https://overpass-api.de
 *  - Nominatim     (geocoding)            https://nominatim.openstreetmap.org
 *  - OSRM demo     (routing)              https://router.project-osrm.org
 */
object Endpoints {
    /**
     * Overpass mirrors, tried in order. The main instance returns a 504 under load often
     * enough that a single endpoint makes signal fetching unreliable in practice.
     */
    var overpassMirrors = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    /**
     * `way(bn.set)` selects the parent ways of the nodes in `.set`, which is how we pick up
     * the speed limit of the road each signal sits on in the same round trip.
     */
    var nominatim = "https://nominatim.openstreetmap.org"
    var osrm = "https://router.project-osrm.org"

    const val USER_AGENT = "GreenLight-GLOSA/0.1 (personal use; github.com/ChaitanyaKharche/Greenlight)"
}

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()

val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
}

private suspend fun getJson(url: String): JsonObject = withContext(Dispatchers.IO) {
    val req = Request.Builder().url(url).header("User-Agent", Endpoints.USER_AGENT).build()
    httpClient.newCall(req).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) error("HTTP ${resp.code} from $url")
        json.parseToJsonElement(body).jsonObject
    }
}

data class GeocodeResult(val label: String, val position: LatLon)

/** Forward geocoding: "north station boston" -> a coordinate. */
suspend fun geocode(query: String, near: LatLon? = null, limit: Int = 6): List<GeocodeResult> =
    withContext(Dispatchers.IO) {
        val viewbox = near?.let {
            val d = 0.45 // roughly 50 km; biases results towards where the driver is
            "&viewbox=${it.lon - d},${it.lat + d},${it.lon + d},${it.lat - d}&bounded=0"
        } ?: ""
        val url = "${Endpoints.nominatim}/search?format=jsonv2&limit=$limit" +
            "&q=${java.net.URLEncoder.encode(query, "UTF-8")}$viewbox"
        val req = Request.Builder().url(url).header("User-Agent", Endpoints.USER_AGENT).build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Geocoder returned HTTP ${resp.code}")
            json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val lat = o["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = o["lon"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                GeocodeResult(
                    label = o["display_name"]?.jsonPrimitive?.content ?: "Unnamed",
                    position = LatLon(lat, lon),
                )
            }
        }
    }

data class Route(
    val geometry: List<LatLon>,
    val distanceMeters: Double,
    val durationSeconds: Double,
)

/** Driving route from OSRM. Uses polyline6 so the geometry is precise enough to snap signals to. */
suspend fun route(from: LatLon, to: LatLon): Route? {
    val url = "${Endpoints.osrm}/route/v1/driving/" +
        "${from.lon},${from.lat};${to.lon},${to.lat}" +
        "?overview=full&geometries=polyline6&alternatives=false&steps=false"
    val obj = getJson(url)
    val r = obj["routes"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
    val encoded = r["geometry"]?.jsonPrimitive?.content ?: return null
    return Route(
        geometry = decodePolyline(encoded, precision = 6),
        distanceMeters = r["distance"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
        durationSeconds = r["duration"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
    )
}

/** A signal node plus what OSM knows about the junction it sits in. */
data class OverpassSignal(
    val id: Long,
    val position: LatLon,
    val maxspeedMps: Double?,
    val name: String?,
    /**
     * Legs, not ways. A road passing straight through the node contributes two, one that
     * terminates there contributes one, so a normal crossroads comes out as 4 and a T as 3.
     */
    val approaches: Int = 0,
    val totalLanes: Int = 0,
    /** Width of the widest carriageway meeting here, in metres. */
    val crossingMeters: Double = 0.0,
)

/**
 * Fetches `highway=traffic_signals` nodes in a bounding box, together with the maxspeed of
 * the ways they belong to. OSM's signal coverage in built-up areas is genuinely good; the
 * timing is what it does not have.
 */
suspend fun fetchSignals(bbox: DoubleArray): List<OverpassSignal> = withContext(Dispatchers.IO) {
    val (s, w, n, e) = listOf(bbox[0], bbox[1], bbox[2], bbox[3])
    // The regex catches both "traffic_signals" and compound values like
    // "traffic_signals;crossing". The second half pulls every way those nodes sit on, which
    // gives us speed limit, lane counts and leg counts - enough to derive timing bounds from
    // physics before a single observation has been recorded.
    val query = """
        [out:json][timeout:40];
        node["highway"~"^traffic_signals"]($s,$w,$n,$e)->.sig;
        .sig out body;
        way(bn.sig)["highway"];
        out body;
    """.trimIndent()

    var lastError: Exception? = null
    // Two passes over the mirrors: overload is usually transient, so a short backoff and a
    // second lap beats failing the fetch and leaving the driver with no signals at all.
    repeat(2) { attempt ->
        if (attempt > 0) kotlinx.coroutines.delay(1_500)
        for (endpoint in Endpoints.overpassMirrors) {
            try {
                return@withContext postOverpass(endpoint, query)
            } catch (e: Exception) {
                lastError = e
            }
        }
    }
    throw lastError ?: IllegalStateException("No Overpass mirror configured")
}

private fun postOverpass(endpoint: String, query: String): List<OverpassSignal> {
    val req = Request.Builder()
        .url(endpoint)
        .header("User-Agent", Endpoints.USER_AGENT)
        .post(("data=" + java.net.URLEncoder.encode(query, "UTF-8")).toRequestBody(FORM_MEDIA_TYPE))
        .build()
    return httpClient.newCall(req).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) error("HTTP ${resp.code}")
        // Overload comes back as an HTML error page, sometimes even with a 200.
        if (!body.trimStart().startsWith("{")) error("non-JSON response (server busy)")
        parseOverpass(body)
    }
}

/** OSM `traffic_signals=*` values that are not cycle-controlled. */
private val NON_CYCLING_SIGNALS = setOf("blinker", "emergency", "ramp_meter")

internal fun parseOverpass(body: String): List<OverpassSignal> {
    val root = json.parseToJsonElement(body).jsonObject
    val elements = root["elements"]?.jsonArray ?: return emptyList()

    // Pass 1: walk the parent ways and accumulate per-node junction geometry.
    val speedByNode = HashMap<Long, Double>()
    val approachesByNode = HashMap<Long, Int>()
    val lanesByNode = HashMap<Long, Int>()
    val widthByNode = HashMap<Long, Double>()

    for (el in elements) {
        val o = el.jsonObject
        if (o["type"]?.jsonPrimitive?.content != "way") continue
        val tags = o["tags"]?.jsonObject ?: continue
        val highway = tags["highway"]?.jsonPrimitive?.content ?: continue
        val nodeIds = o["nodes"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content.toLongOrNull() } ?: continue
        if (nodeIds.isEmpty()) continue

        val speed = tags["maxspeed"]?.jsonPrimitive?.content?.let(::parseMaxspeedMps)
        val lanes = tags["lanes"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: defaultLanesFor(highway)
        val width = lanes * com.greenlight.learn.IntersectionPriors.LANE_WIDTH_M

        for ((index, nid) in nodeIds.withIndex()) {
            if (speed != null) speedByNode[nid] = speed
            // A way that merely passes through the junction presents two legs; one that
            // starts or ends there presents a single leg.
            val isEndpoint = index == 0 || index == nodeIds.lastIndex
            approachesByNode[nid] = (approachesByNode[nid] ?: 0) + if (isEndpoint) 1 else 2
            lanesByNode[nid] = (lanesByNode[nid] ?: 0) + lanes
            widthByNode[nid] = maxOf(widthByNode[nid] ?: 0.0, width)
        }
    }

    // Pass 2: the signal nodes themselves.
    val out = ArrayList<OverpassSignal>()
    for (el in elements) {
        val o = el.jsonObject
        if (o["type"]?.jsonPrimitive?.content != "node") continue
        val tags = o["tags"]?.jsonObject
        val highway = tags?.get("highway")?.jsonPrimitive?.content ?: continue
        if (!highway.startsWith("traffic_signals")) continue

        // A flashing amber or red has no cycle to catch, so it is not a GLOSA target.
        // The learner would never converge on one anyway; skipping keeps it out of the
        // corridor solve and stops it masking a real signal behind it.
        val kind = tags["traffic_signals"]?.jsonPrimitive?.content
        if (kind in NON_CYCLING_SIGNALS) continue

        val id = o["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue
        val lat = o["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: continue
        val lon = o["lon"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: continue
        out.add(
            OverpassSignal(
                id = id,
                position = LatLon(lat, lon),
                maxspeedMps = speedByNode[id],
                name = tags["name"]?.jsonPrimitive?.content,
                approaches = approachesByNode[id] ?: 0,
                totalLanes = lanesByNode[id] ?: 0,
                crossingMeters = widthByNode[id] ?: 0.0,
            )
        )
    }
    return out
}

/**
 * OSM tags `lanes` inconsistently, so fall back on the road class. These are conservative
 * totals for both directions, which is what the crossing-width maths needs.
 */
internal fun defaultLanesFor(highway: String): Int = when (highway) {
    "motorway", "motorway_link", "trunk", "trunk_link" -> 4
    "primary", "primary_link" -> 4
    "secondary", "secondary_link" -> 3
    "tertiary", "tertiary_link" -> 2
    "residential", "unclassified", "living_street", "service" -> 2
    else -> 2
}

/** OSM `maxspeed` is free text: "50", "30 mph", "RU:urban". Returns m/s, or null. */
internal fun parseMaxspeedMps(raw: String): Double? {
    val t = raw.trim().lowercase()
    val number = Regex("""\d+(\.\d+)?""").find(t)?.value?.toDoubleOrNull() ?: return null
    return when {
        "mph" in t -> number * 0.44704
        "knots" in t -> number * 0.514444
        else -> number / 3.6 // OSM defaults to km/h
    }
}

/** Convenience: bounding box around a route, padded so nearby cross-street signals come too. */
fun routeBoundingBox(geometry: List<LatLon>, padMeters: Double = 120.0) =
    boundingBox(geometry, padMeters)
