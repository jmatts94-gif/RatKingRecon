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

    // ---- the revision --------------------------------------------------------

    /**
     * The case the revision exists for: somebody who finished the walkthrough
     * when it was a boolean and four stops long.
     */
    @Test
    fun `a save carrying the old flag is owed only what changed`() {
        val prefs = FakePrefs()
        Onboarding.markComplete(prefs)
        // What the old build wrote, and all it wrote.
        prefs.edit().putBoolean(CoachMarks.KEY_COMPLETE, true).apply()

        assertTrue("there is something new to show", CoachMarks.shouldShow(prefs))

        val owed = CoachMarks.stepsFor(prefs)
        assertEquals(
            "only the stops written or rewritten since should be owed",
            CoachMarks.steps.filter { it.revisedIn > 1 },
            owed
        )
        assertTrue(
            "the stops that have not changed should not be shown again",
            owed.none { it.captionRes == R.string.coach_level }
        )
    }

    /**
     * The case revision 3 exists for: somebody who finished the walkthrough
     * at revision 2, before the Arena tile or its button existed.
     */
    @Test
    fun `a save already at revision 2 is owed only the Arena stops`() {
        val prefs = FakePrefs()
        Onboarding.markComplete(prefs)
        CoachMarks.markComplete(prefs) // markComplete always writes CoachMarks.REVISION...
        prefs.edit().putInt(CoachMarks.KEY_REVISION, 2).apply() // ...so set the real target explicitly.

        assertTrue("there is something new to show", CoachMarks.shouldShow(prefs))

        val owed = CoachMarks.stepsFor(prefs)
        assertEquals(
            listOf(R.id.arenaTile, R.id.battleArenaButton),
            owed.map { it.targetId }
        )
    }

    @Test
    fun `a new save is owed the whole walkthrough`() {
        val prefs = FakePrefs()
        Onboarding.markComplete(prefs)

        assertEquals(CoachMarks.steps, CoachMarks.stepsFor(prefs))
    }

    @Test
    fun `finishing leaves nothing owed until the revision moves`() {
        val prefs = FakePrefs()
        Onboarding.markComplete(prefs)
        CoachMarks.markComplete(prefs)

        assertTrue(CoachMarks.stepsFor(prefs).isEmpty())
        assertEquals(CoachMarks.REVISION, CoachMarks.seenRevision(prefs))
    }

    /**
     * The stamp has to be an integer of its own rather than the old key reused:
     * SharedPreferences throws on reading a boolean back as an int, so a save
     * written by the previous build would crash on open.
     */
    @Test
    fun `the revision is stored apart from the old boolean`() {
        assertTrue(CoachMarks.KEY_REVISION != CoachMarks.KEY_COMPLETE)

        val prefs = FakePrefs()
        prefs.edit().putBoolean(CoachMarks.KEY_COMPLETE, true).apply()
        CoachMarks.markComplete(prefs)

        assertEquals(CoachMarks.REVISION, CoachMarks.seenRevision(prefs))
    }

    /** Every stop must be reachable by somebody: a revision past the current one is not. */
    @Test
    fun `no stop is written for a revision that will never arrive`() {
        CoachMarks.steps.forEach {
            assertTrue(
                "a stop revised in ${it.revisedIn} can never show at REVISION ${CoachMarks.REVISION}",
                it.revisedIn <= CoachMarks.REVISION
            )
        }
    }

    // ---- the stops -----------------------------------------------------------

    @Test
    fun `points at eight things, each of them once`() {
        assertEquals(8, CoachMarks.steps.size)

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

    /**
     * The order is the order the eye takes them in, and the tiles are walked as
     * the one group they read as - which is why the lantern is last rather than
     * where it originally sat, back when it was the only tile there was. The
     * Arena tile follows it since the two now share a row, and the Battle
     * Arena button closes the list as the one thing here meant for later
     * rather than a first session.
     */
    @Test
    fun `runs the header left to right, then the tiles top to bottom, ending on the Arena`() {
        assertEquals(
            listOf(
                R.id.playerLevelText,
                R.id.stepCountText,
                R.id.scrapText,
                R.id.expeditionTile,
                R.id.stepsTile,
                R.id.streakTile,
                R.id.arenaTile,
                R.id.battleArenaButton
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
