package com.greenlight.nav

import com.greenlight.core.haversineMeters
import com.greenlight.model.TrafficSignal

/**
 * Merges OpenStreetMap signal nodes that describe one physical junction.
 *
 * A large intersection is rarely a single node. Divided carriageways, separate stop lines per
 * approach and mapped crossings routinely give three or four `highway=traffic_signals` nodes
 * within a few tens of metres. Observed on a real drive: three nodes triggering in the same
 * second on the same bearing, which meant one junction's evidence was being filed under three
 * different keys - so nothing ever accumulated enough samples to learn, and the corridor
 * solver saw three phantom signals metres apart.
 *
 * Clustering is single-linkage on distance, which suits the geometry: nodes belonging to one
 * junction form a tight chain, and the next real signal is a block away.
 */
object SignalCluster {

    /**
     * Nodes within this distance are treated as one junction. Wide enough to span a divided
     * arterial's separate stop lines, narrow enough not to swallow a genuinely adjacent
     * signal - those sit at least a short block apart.
     */
    const val DEFAULT_RADIUS_METERS = 45.0

    fun merge(
        signals: List<TrafficSignal>,
        radiusMeters: Double = DEFAULT_RADIUS_METERS,
    ): List<TrafficSignal> {
        if (signals.size < 2) return signals

        // Sorting by longitude bounds the neighbour scan: once a candidate is further east
        // than the radius allows, nothing after it can join either.
        val sorted = signals.sortedBy { it.position.lon }
        val parent = IntArray(sorted.size) { it }

        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var c = i
            while (parent[c] != c) { val next = parent[c]; parent[c] = r; c = next }
            return r
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        // A degree of longitude shrinks with latitude, so derive the window from the actual
        // latitude rather than assuming a constant.
        val metresPerDegreeLon = 111_320.0 *
            kotlin.math.cos(Math.toRadians(sorted.first().position.lat)).coerceAtLeast(0.05)
        val lonWindow = radiusMeters / metresPerDegreeLon

        for (i in sorted.indices) {
            var j = i + 1
            while (j < sorted.size && sorted[j].position.lon - sorted[i].position.lon <= lonWindow) {
                if (haversineMeters(sorted[i].position, sorted[j].position) <= radiusMeters) {
                    union(i, j)
                }
                j++
            }
        }

        return sorted.indices.groupBy { find(it) }.values.map { members ->
            representative(members.map { sorted[it] })
        }.sortedBy { it.id }
    }

    /** Collapses a cluster's members into the single signal the rest of the app sees. */
    private fun representative(members: List<TrafficSignal>): TrafficSignal {
        if (members.size == 1) return members.single()

        val centroidLat = members.sumOf { it.position.lat } / members.size
        val centroidLon = members.sumOf { it.position.lon } / members.size

        // The lowest node id is a stable, deterministic key: the same set of nodes always
        // yields the same cluster id, so observations filed earlier keep matching.
        val key = members.minOf { it.id }

        // Speed limit comes from the widest member rather than the min or max of all. OSM
        // tags maxspeed per way, so an arterial crossing a residential street produces both
        // a 45 and a 25 at the same junction. Taking the min would refuse to advise on the
        // arterial; taking the max would show a side-street driver the arterial's limit. The
        // widest road is the one a green-wave advisory is actually for.
        val widest = members.maxByOrNull { it.totalLanes }

        return TrafficSignal(
            id = key,
            position = com.greenlight.core.LatLon(centroidLat, centroidLon),
            approachBearing = null,
            speedLimitMps = widest?.speedLimitMps ?: members.firstNotNullOfOrNull { it.speedLimitMps },
            name = members.firstNotNullOfOrNull { it.name },
            // Legs and width describe the whole junction, so take the largest seen.
            approaches = members.maxOf { it.approaches },
            totalLanes = members.maxOf { it.totalLanes },
            crossingMeters = members.maxOf { it.crossingMeters },
        )
    }
}
