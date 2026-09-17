package com.greenlight

import com.greenlight.core.LatLon
import com.greenlight.model.TrafficSignal
import com.greenlight.nav.SignalCluster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalClusterTest {

    private val lat = 33.4149

    private fun at(id: Long, metresEast: Double, lanes: Int = 4, limit: Double? = null) =
        TrafficSignal(
            id = id,
            position = LatLon(lat, -111.9209 + metresEast / 92_900.0),
            speedLimitMps = limit,
            totalLanes = lanes,
            approaches = 4,
            crossingMeters = lanes * 3.5,
        )

    @Test
    fun `nodes describing one junction collapse to a single signal`() {
        // Taken from a real drive: three nodes triggering in the same second.
        val merged = SignalCluster.merge(
            listOf(at(330689965, 0.0), at(330683938, 18.0), at(330683970, 33.0))
        )
        assertEquals(1, merged.size)
        // The lowest node id is the stable key, so previously filed observations still match.
        assertEquals(330683938L, merged.single().id)
    }

    @Test
    fun `genuinely separate signals stay separate`() {
        val merged = SignalCluster.merge(listOf(at(1, 0.0), at(2, 200.0), at(3, 420.0)))
        assertEquals(3, merged.size)
    }

    @Test
    fun `a chain merges transitively but does not run away`() {
        // 0, 30, 60 m: each hop is inside the radius, so all three are one junction.
        assertEquals(1, SignalCluster.merge(listOf(at(1, 0.0), at(2, 30.0), at(3, 60.0))).size)
        // A 120 m gap breaks the chain.
        val split = SignalCluster.merge(listOf(at(1, 0.0), at(2, 30.0), at(3, 150.0)))
        assertEquals(2, split.size)
    }

    @Test
    fun `the merged junction takes the widest member's speed limit`() {
        // An arterial crossing a residential street carries both limits in OSM. The advisory
        // is for the arterial, so the wider road's limit is the one that must survive.
        val arterial = at(10, 0.0, lanes = 6, limit = 20.1)   // ~45 mph
        val side = at(11, 20.0, lanes = 2, limit = 11.2)      // ~25 mph
        val merged = SignalCluster.merge(listOf(side, arterial)).single()
        assertEquals(20.1, merged.speedLimitMps!!, 1e-9)
        assertEquals(6, merged.totalLanes)
    }

    @Test
    fun `geometry of the merged junction is the largest seen`() {
        val merged = SignalCluster.merge(
            listOf(at(1, 0.0, lanes = 2), at(2, 15.0, lanes = 8))
        ).single()
        assertEquals(8, merged.totalLanes)
        assertEquals(28.0, merged.crossingMeters, 1e-9)
    }

    @Test
    fun `clustering is stable under reordering`() {
        val a = listOf(at(5, 0.0), at(3, 20.0), at(9, 40.0))
        val b = a.reversed()
        assertEquals(SignalCluster.merge(a).map { it.id }, SignalCluster.merge(b).map { it.id })
    }

    @Test
    fun `empty and single inputs are passed through`() {
        assertTrue(SignalCluster.merge(emptyList()).isEmpty())
        assertEquals(1, SignalCluster.merge(listOf(at(1, 0.0))).size)
    }
}
