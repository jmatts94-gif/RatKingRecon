package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingPromptTest {

    @Test
    fun `a win without a new badge does not ask`() {
        val prefs = FakePrefs()
        assertFalse(RatingPrompt.claim(prefs, due = false))
        assertTrue("the first good moment is still to come", RatingPrompt.claim(prefs, due = true))
    }

    @Test
    fun `the player is only ever asked once`() {
        val prefs = FakePrefs()
        assertTrue(RatingPrompt.claim(prefs, due = true))
        assertFalse(RatingPrompt.claim(prefs, due = true))
    }

    private fun week(vararg steps: Int) = steps.mapIndexed { i, s -> StepHistory.Day(i.toLong(), i, s) }

    @Test
    fun `the chart asks once most of a week has been walked`() {
        assertFalse(RatingPrompt.chartIsDue(week(0, 0, 0, 900, 4000, 3000, 2000)))
        assertTrue(RatingPrompt.chartIsDue(week(0, 0, 1200, 900, 4000, 3000, 2000)))
    }

    @Test
    fun `a badge and the chart share one ask`() {
        val prefs = FakePrefs()
        assertTrue(RatingPrompt.claim(prefs, due = true))
        assertFalse(RatingPrompt.claim(prefs, RatingPrompt.chartIsDue(week(1, 1, 1, 1, 1, 1, 1))))
    }
}
