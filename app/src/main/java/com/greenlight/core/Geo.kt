package com.greenlight.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS-84 mean radius in metres. */
const val EARTH_RADIUS_M = 6_371_008.8

data class LatLon(val lat: Double, val lon: Double) {
    fun isValid() = lat in -90.0..90.0 && lon in -180.0..180.0
}

fun Double.toRadians() = this * Math.PI / 180.0
fun Double.toDegrees() = this * 180.0 / Math.PI

/** Great-circle distance in metres. Accurate enough for the sub-km ranges GLOSA cares about. */
fun haversineMeters(a: LatLon, b: LatLon): Double {
    val dLat = (b.lat - a.lat).toRadians()
    val dLon = (b.lon - a.lon).toRadians()
    val lat1 = a.lat.toRadians()
    val lat2 = b.lat.toRadians()
    val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
}

/** Initial bearing from [a] to [b] in degrees, normalised to [0, 360). */
fun bearingDegrees(a: LatLon, b: LatLon): Double {
    val lat1 = a.lat.toRadians()
    val lat2 = b.lat.toRadians()
    val dLon = (b.lon - a.lon).toRadians()
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (atan2(y, x).toDegrees() + 360.0) % 360.0
}

/** Smallest signed difference between two bearings, in (-180, 180]. */
fun bearingDeltaDegrees(from: Double, to: Double): Double {
    var d = (to - from + 540.0) % 360.0 - 180.0
    if (d == -180.0) d = 180.0
    return d
}

/**
 * Local equirectangular projection around [origin], in metres.
 * Distortion is negligible over the few-km window we ever project.
 */
class LocalPlane(val origin: LatLon) {
    private val cosLat = cos(origin.lat.toRadians())

    fun toXY(p: LatLon): DoubleArray = doubleArrayOf(
        (p.lon - origin.lon).toRadians() * EARTH_RADIUS_M * cosLat,
        (p.lat - origin.lat).toRadians() * EARTH_RADIUS_M,
    )

    fun toLatLon(x: Double, y: Double): LatLon = LatLon(
        origin.lat + (y / EARTH_RADIUS_M).toDegrees(),
        origin.lon + (x / (EARTH_RADIUS_M * cosLat)).toDegrees(),
    )
}

/** Result of projecting a point onto a polyline. */
data class Projection(
    /** Index of the segment start vertex. */
    val segmentIndex: Int,
    /** Fraction along that segment, 0..1. */
    val t: Double,
    /** Perpendicular offset from the polyline in metres. */
    val lateralMeters: Double,
    /** Distance from the polyline start to the projected point, in metres. */
    val alongMeters: Double,
    val point: LatLon,
)

/** Cumulative along-path distance at each vertex; index 0 is always 0. */
fun cumulativeDistances(path: List<LatLon>): DoubleArray {
    val out = DoubleArray(path.size)
    for (i in 1 until path.size) out[i] = out[i - 1] + haversineMeters(path[i - 1], path[i])
    return out
}

/**
 * Snap [p] onto [path]. [searchFrom] lets the caller restrict matching to the part of
 * the route still ahead, which stops a loop or a hairpin from matching backwards.
 */
fun projectOntoPath(
    p: LatLon,
    path: List<LatLon>,
    cumulative: DoubleArray = cumulativeDistances(path),
    searchFrom: Int = 0,
): Projection? {
    if (path.size < 2) return null
    val plane = LocalPlane(p)
    val pxy = plane.toXY(p)
    var best: Projection? = null
    var bestLateral = Double.MAX_VALUE

    for (i in max(0, searchFrom) until path.size - 1) {
        val a = plane.toXY(path[i])
        val b = plane.toXY(path[i + 1])
        val abx = b[0] - a[0]
        val aby = b[1] - a[1]
        val segLenSq = abx * abx + aby * aby
        val t = if (segLenSq < 1e-9) 0.0 else {
            (((pxy[0] - a[0]) * abx + (pxy[1] - a[1]) * aby) / segLenSq).coerceIn(0.0, 1.0)
        }
        val cx = a[0] + t * abx
        val cy = a[1] + t * aby
        val lateral = sqrt((pxy[0] - cx) * (pxy[0] - cx) + (pxy[1] - cy) * (pxy[1] - cy))
        if (lateral < bestLateral) {
            bestLateral = lateral
            val segLen = sqrt(segLenSq)
            best = Projection(
                segmentIndex = i,
                t = t,
                lateralMeters = lateral,
                alongMeters = cumulative[i] + t * segLen,
                point = plane.toLatLon(cx, cy),
            )
        }
    }
    return best
}

/** Heading of the path at [alongMeters], in degrees. Used to reject signals for the other direction. */
fun headingAt(path: List<LatLon>, cumulative: DoubleArray, alongMeters: Double): Double? {
    if (path.size < 2) return null
    var i = cumulative.indexOfFirst { it > alongMeters } - 1
    if (i < 0) i = if (alongMeters <= 0.0) 0 else path.size - 2
    i = i.coerceIn(0, path.size - 2)
    return bearingDegrees(path[i], path[i + 1])
}

/** Decodes a Google/OSRM encoded polyline. [precision] is 5 for OSRM v5 default, 6 for `geometries=polyline6`. */
fun decodePolyline(encoded: String, precision: Int = 5): List<LatLon> {
    val factor = Math.pow(10.0, precision.toDouble())
    val out = ArrayList<LatLon>()
    var index = 0
    var lat = 0
    var lon = 0
    while (index < encoded.length) {
        var shift = 0
        var result = 0
        var b: Int
        do {
            if (index >= encoded.length) return out
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        shift = 0
        result = 0
        do {
            if (index >= encoded.length) return out
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        out.add(LatLon(lat / factor, lon / factor))
    }
    return out
}

/** Bounding box padded by [padMeters], as (south, west, north, east) — Overpass bbox order. */
fun boundingBox(points: List<LatLon>, padMeters: Double): DoubleArray {
    var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
    for (p in points) {
        minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
        minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
    }
    val dLat = (padMeters / EARTH_RADIUS_M).toDegrees()
    val midLat = (minLat + maxLat) / 2
    val dLon = (padMeters / (EARTH_RADIUS_M * max(0.01, abs(cos(midLat.toRadians()))))).toDegrees()
    return doubleArrayOf(minLat - dLat, minLon - dLon, maxLat + dLat, maxLon + dLon)
}
