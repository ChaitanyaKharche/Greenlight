package com.greenlight

import com.greenlight.core.LatLon
import com.greenlight.data.parseGeocode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are trimmed copies of real Nominatim responses for the query "volta" from Tempe,
 * Arizona - the search that sent a driver looking for a cafe on Broadway to Volta Region,
 * Ghana.
 */
class GeocodeTest {

    private val tempe = LatLon(33.4255, -111.9400)

    /** What an unbounded search actually returned. */
    private val globalBody = """
    [
      {"lat":"7.0","lon":"0.5","name":"Volta Region",
       "display_name":"Volta Region, Ghana"},
      {"lat":"6.5","lon":"0.4","name":"Volta",
       "display_name":"Volta, Ghana"}
    ]
    """.trimIndent()

    /** What the bounded search returns, in Nominatim's own importance order. */
    private val boundedBody = """
    [
      {"lat":"33.6823","lon":"-111.9728","name":"Volta",
       "display_name":"Volta, East Mayo Boulevard, Desert View, Phoenix, Maricopa County, Arizona, United States"},
      {"lat":"33.6178","lon":"-111.8908","name":"Volta",
       "display_name":"Volta, North Promenade Lane, The Promenade, Scottsdale, Maricopa County, Arizona, United States"},
      {"lat":"33.3931","lon":"-111.9264","name":"Volta",
       "display_name":"Volta, East Southern Avenue, Tempe, Maricopa County, Arizona, 85282, United States"},
      {"lat":"33.3062","lon":"-111.8886","name":"Volta",
       "display_name":"Volta, South Price Road, Chandler, Maricopa County, Arizona, 85224, United States"},
      {"lat":"33.4069","lon":"-111.9397","name":"Volta on Broadway",
       "display_name":"Volta on Broadway, 1221, Tempe, Maricopa County, Arizona, 85282, United States"}
    ]
    """.trimIndent()

    @Test
    fun `the place actually wanted ranks first once sorted by distance`() {
        val ranked = parseGeocode(boundedBody, tempe)
            .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
        // Nominatim had this fifth of five, behind a charger 16 miles away.
        assertEquals("Volta on Broadway", ranked.first().name)
        assertTrue("distance ${ranked.first().distanceMeters}", ranked.first().distanceMeters!! < 3_000.0)
    }

    @Test
    fun `distances are ascending after ranking`() {
        val ranked = parseGeocode(boundedBody, tempe)
            .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
        val distances = ranked.map { it.distanceMeters!! }
        assertEquals(distances.sorted(), distances)
    }

    @Test
    fun `context locates a result without the country and postcode tail`() {
        val onBroadway = parseGeocode(boundedBody, tempe).first { it.name == "Volta on Broadway" }
        // Purely numeric components like a house number carry no meaning on their own.
        assertEquals("Tempe, Maricopa County", onBroadway.context)
        assertTrue(onBroadway.label.startsWith("Volta on Broadway, "))
    }

    @Test
    fun `two results sharing a name are told apart by their context`() {
        val results = parseGeocode(boundedBody, tempe).filter { it.name == "Volta" }
        val contexts = results.map { it.context }
        assertEquals(contexts.size, contexts.distinct().size)
        assertTrue(contexts.any { it.contains("Tempe") })
        assertTrue(contexts.any { it.contains("Chandler") })
    }

    @Test
    fun `without an origin there is no distance to report`() {
        val results = parseGeocode(globalBody, near = null)
        assertEquals(2, results.size)
        results.forEach { assertNull(it.distanceMeters) }
    }

    @Test
    fun `the unbounded response is still parsed, just far away`() {
        // The widened fallback must keep working for genuinely distant searches.
        val results = parseGeocode(globalBody, tempe)
        assertEquals("Volta Region", results.first().name)
        assertTrue("should be thousands of km away", results.first().distanceMeters!! > 10_000_000.0)
    }

    @Test
    fun `malformed entries are skipped rather than crashing`() {
        val body = """
        [
          {"display_name":"No coordinates here"},
          {"lat":"not-a-number","lon":"-111.9","name":"Bad"},
          {"lat":"33.4069","lon":"-111.9397","name":"Good","display_name":"Good, Tempe"}
        ]
        """.trimIndent()
        val results = parseGeocode(body, tempe)
        assertEquals(1, results.size)
        assertEquals("Good", results.single().name)
    }

    @Test
    fun `an empty response is not an error`() {
        assertTrue(parseGeocode("[]", tempe).isEmpty())
    }
}
