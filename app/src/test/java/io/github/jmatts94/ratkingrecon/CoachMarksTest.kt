package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate on the home-screen walkthrough.
 *
 * The ordering against the splash is the part worth pinning down. MainActivity
 * starts onboarding without waiting for it and carries on building underneath,
 * so it reaches onResume once while the splash is still up - and the walkthrough
 * must not fire into a screen the player cannot see. That is one boolean, and
 * getting it backwards means the whole thing runs behind the splash and is
 * marked complete without ever having been read.
 */
class CoachMarksTest {

    // ---- the gate ------------------------------------------------------------

    @Test
    fun `stays shut while the splash is still unfinished`() {
        val prefs = FakePrefs()

        assertFalse(
            "a fresh save has not seen onboarding yet",
            CoachMarks.shouldShow(prefs)
        )
    }

    @Test
    fun `opens on the first resume after the splash sets its flag`() {
        val prefs = FakePrefs()
        Onboarding.markComplete(prefs)

        assertTrue(CoachMarks.shouldShow(prefs))
    }

    @Test
    fun `shuts for good once it has been seen`() {
        val prefs = FakePrefs()
        Onboarding.markComplete(prefs)
        CoachMarks.markComplete(prefs)

        assertFalse(CoachMarks.shouldShow(prefs))
    }

    @Test
    fun `finishing the walkthrough leaves the splash flag alone`() {
        val prefs = FakePrefs()
        Onboarding.markComplete(prefs)

        CoachMarks.markComplete(prefs)

        assertTrue(
            "the splash owns its own flag and this must not write it",
            Onboarding.isComplete(prefs)
        )
    }

    @Test
    fun `the two flags are separate keys`() {
        assertTrue(CoachMarks.KEY_COMPLETE != Onboarding.KEY_COMPLETE)
    }

    @Test
    fun `a reset save brings the walkthrough back`() {
        val prefs = FakePrefs()
        Onboarding.markComplete(prefs)
        CoachMarks.markComplete(prefs)

        // What Reset Save does: clears the file both flags live in.
        prefs.edit().clear().apply()
        Onboarding.markComplete(prefs)

        assertTrue(CoachMarks.shouldShow(prefs))
    }

    // ---- the stops -----------------------------------------------------------

    @Test
    fun `points at four things, each of them once`() {
        assertEquals(4, CoachMarks.steps.size)

        assertEquals(
            "two stops pointing at the same view would dim the screen twice over",
            CoachMarks.steps.size,
            CoachMarks.steps.map { it.targetId }.distinct().size
        )
        assertEquals(
            "a repeated caption means a stop was left unwritten",
            CoachMarks.steps.size,
            CoachMarks.steps.map { it.captionRes }.distinct().size
        )
    }

    @Test
    fun `runs the header left to right, then the lantern`() {
        assertEquals(
            listOf(
                R.id.playerLevelText,
                R.id.stepCountText,
                R.id.scrapText,
                R.id.streakTile
            ),
            CoachMarks.steps.map { it.targetId }
        )
    }

    @Test
    fun `every stop names a real view and a real string`() {
        CoachMarks.steps.forEach {
            assertTrue("unresolved target id", it.targetId != 0)
            assertTrue("unresolved caption", it.captionRes != 0)
        }
    }
}
