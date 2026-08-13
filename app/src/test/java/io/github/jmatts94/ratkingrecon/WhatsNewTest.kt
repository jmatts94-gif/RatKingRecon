package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the release notes are owed.
 *
 * The rule is small and the ways of getting it wrong are all quiet: notes that
 * open on a fresh install, notes that open again on every resume because the
 * version was never stamped, or notes that never open at all. None of those
 * announce themselves, so each one is pinned here.
 */
class WhatsNewTest {

    private fun lastSeen(prefs: FakePrefs) = prefs.getInt(WhatsNew.KEY_LAST_SEEN, -1)

    @Test
    fun `a first run is not an update and shows nothing`() {
        val prefs = FakePrefs()

        assertFalse("a fresh install has nothing to catch up on", WhatsNew.consume(prefs, 3))
        assertEquals("the version should still be stamped", 3, lastSeen(prefs))
    }

    @Test
    fun `a higher version shows the notes once`() {
        val prefs = FakePrefs()
        WhatsNew.consume(prefs, 3)

        assertTrue("an update should show the notes", WhatsNew.consume(prefs, 4))
        assertFalse("but only once", WhatsNew.consume(prefs, 4))
        assertEquals(4, lastSeen(prefs))
    }

    @Test
    fun `reopening on the same version shows nothing`() {
        val prefs = FakePrefs()
        WhatsNew.consume(prefs, 3)

        repeat(5) {
            assertFalse("no update, no notes", WhatsNew.consume(prefs, 3))
        }
    }

    @Test
    fun `skipping several versions still shows once`() {
        val prefs = FakePrefs()
        WhatsNew.consume(prefs, 3)

        assertTrue("three releases missed is still one set of notes", WhatsNew.consume(prefs, 6))
        assertFalse(WhatsNew.consume(prefs, 6))
    }

    /**
     * Sideloading an older build is a developer's problem, not a player's, but
     * it must not leave the notes permanently owed or permanently spent.
     */
    @Test
    fun `a downgrade shows nothing and re-arms the notes`() {
        val prefs = FakePrefs()
        WhatsNew.consume(prefs, 5)

        assertFalse("going back a version is not news", WhatsNew.consume(prefs, 4))
        assertEquals("and the stamp follows it down", 4, lastSeen(prefs))

        assertTrue("so upgrading again shows them", WhatsNew.consume(prefs, 5))
    }
}
