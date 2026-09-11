package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who the Splicing screen will not let the player pick.
 *
 * TimeTail's own case gets a file to itself rather than a line added to the
 * other three checks: those three are courtesies the game does not otherwise
 * enforce, but nothing may ever consume him - see the class doc on
 * [SpliceEligibility] - so his exclusion needs to hold under every one of
 * their states, not just the default one.
 */
class SpliceEligibilityTest {

    private fun timetail(id: Long) =
        RatEntity(id = id, artKey = "timetail_pic", name = "TimeTail", power = 8, toughness = 8, shiny = false)

    @Test
    fun `TimeTail is excluded even with nothing else going on`() {
        val prefs = FakePrefs()
        val roster = listOf(timetail(1L))

        assertTrue(1L in SpliceEligibility.excludedIds(prefs, roster))
    }

    @Test
    fun `TimeTail stays excluded while on battle duty, on an Expedition, and on a Task`() {
        val prefs = FakePrefs()
        val roster = listOf(timetail(1L))

        BattleRat.set(prefs, 1L)
        prefs.edit()
            .putBoolean(ShopEffects.KEY_EXPEDITION_ACTIVE, true)
            .putLong("DEPLOYED_RAT_ID", 1L)
            .apply()

        assertTrue(1L in SpliceEligibility.excludedIds(prefs, roster))
    }

    @Test
    fun `an ordinary rat with nothing going on is not excluded`() {
        val prefs = FakePrefs()
        val roster = listOf(
            RatEntity(id = 1L, artKey = "bolt_pic", name = "Bolt", power = 3, toughness = 3, shiny = false)
        )

        assertTrue(SpliceEligibility.excludedIds(prefs, roster).isEmpty())
    }
}
