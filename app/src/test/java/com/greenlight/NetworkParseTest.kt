package com.greenlight

import com.greenlight.data.parseMaxspeedMps
import com.greenlight.data.parseOverpass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkParseTest {

    @Test
    fun `parses maxspeed in all the shapes OSM uses`() {
        assertEquals(50 / 3.6, parseMaxspeedMps("50")!!, 1e-6)
        assertEquals(50 / 3.6, parseMaxspeedMps("50 km/h")!!, 1e-6)
        assertEquals(30 * 0.44704, parseMaxspeedMps("30 mph")!!, 1e-6)
        assertEquals(25 * 0.44704, parseMaxspeedMps("25mph")!!, 1e-6)
        assertNull(parseMaxspeedMps("walk"))
        assertNull(parseMaxspeedMps("none"))
    }

    @Test
    fun `parses Overpass output and attaches way speed limits to signal nodes`() {
        // Shape matches a real overpass-api.de response for a traffic_signals query.
        val body = """
        {
          "version": 0.6,
          "elements": [
            {"type":"node","id":61340456,"lat":42.3400691,"lon":-71.0894013,
             "tags":{"highway":"traffic_signals"}},
            {"type":"node","id":61340999,"lat":42.3410000,"lon":-71.0880000,
             "tags":{"highway":"traffic_signals;crossing","name":"Huntington Ave"}},
            {"type":"node","id":7777,"lat":42.3,"lon":-71.0,"tags":{"highway":"crossing"}},
            {"type":"way","id":900,"nodes":[61340456,123],
             "tags":{"highway":"primary","maxspeed":"25 mph"}}
          ]
        }
        """.trimIndent()

        val signals = parseOverpass(body)
        assertEquals(2, signals.size)

        val first = signals.first { it.id == 61340456L }
        assertEquals(42.3400691, first.position.lat, 1e-7)
        assertEquals(25 * 0.44704, first.maxspeedMps!!, 1e-6)

        val second = signals.first { it.id == 61340999L }
        assertEquals("Huntington Ave", second.name)
        // No way carried this node, so we simply do not know its limit.
        assertNull(second.maxspeedMps)
    }

    @Test
    fun `ignores non-signal nodes`() {
        val body = """{"elements":[
          {"type":"node","id":1,"lat":1.0,"lon":2.0,"tags":{"highway":"crossing"}},
          {"type":"node","id":2,"lat":1.0,"lon":2.0,"tags":{"amenity":"cafe"}}
        ]}"""
        assertTrue(parseOverpass(body).isEmpty())
    }

    @Test
    fun `empty response is not an error`() {
        assertTrue(parseOverpass("""{"elements":[]}""").isEmpty())
    }
}
