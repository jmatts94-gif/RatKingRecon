package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingPromptTest {

    @Test
    fun `a win without a new badge does not ask`() {
        val prefs = FakePrefs()
        assertFalse(RatingPrompt.claim(prefs, badgeEarned = false))
        assertTrue("the first badge is still to come", RatingPrompt.claim(prefs, badgeEarned = true))
    }

    @Test
    fun `the player is only ever asked once`() {
        val prefs = FakePrefs()
        assertTrue(RatingPrompt.claim(prefs, badgeEarned = true))
        assertFalse(RatingPrompt.claim(prefs, badgeEarned = true))
    }
}
