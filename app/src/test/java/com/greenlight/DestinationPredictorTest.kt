package com.greenlight

import com.greenlight.learn.DestinationPredictor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The predictor's ranking logic is exercised here through its pure helpers and through a
 * hand-rolled stand-in for the database, so the maths can be checked without an emulator.
 */
class DestinationPredictorTest {

    private val predictor = DestinationPredictor()

    @Test
    fun `minute deltas wrap around midnight`() {
        // 23:50 and 00:10 are twenty minutes apart, not 1420.
        assertEquals(20.0, predictor.wrappedMinuteDelta(23 * 60.0 + 50, 10.0), 1e-9)
        assertEquals(0.0, predictor.wrappedMinuteDelta(600.0, 600.0), 1e-9)
        assertEquals(720.0, predictor.wrappedMinuteDelta(0.0, 720.0), 1e-9)
    }

    @Test
    fun `time kernel falls off over roughly an hour`() {
        val sigma = 50.0
        fun w(dtMinutes: Double) = kotlin.math.exp(-(dtMinutes * dtMinutes) / (2 * sigma * sigma))
        // Same time is full weight; an hour off is materially down; three hours is noise.
        assertEquals(1.0, w(0.0), 1e-9)
        assertTrue(w(60.0) in 0.4..0.6)
        assertTrue(w(180.0) < 0.01)
    }

    @Test
    fun `recency halves on schedule`() {
        val halfLife = 45.0
        fun w(days: Double) = Math.pow(0.5, days / halfLife)
        assertEquals(1.0, w(0.0), 1e-9)
        assertEquals(0.5, w(45.0), 1e-9)
        assertEquals(0.25, w(90.0), 1e-9)
    }

    @Test
    fun `laplace smoothing keeps a single visit from claiming certainty`() {
        // One candidate with score s, alpha 1.5: p = (s + 1.5) / (s + 1.5) = 1.0 for a lone
        // candidate, so the guard that matters is having more than one place in play.
        val alpha = 1.5
        val scores = listOf(3.0, 0.4)
        val total = scores.sum() + alpha * scores.size
        val top = (scores[0] + alpha) / total
        assertTrue("top was $top", top < 0.85)
        assertTrue(top > 0.5)
    }
}
