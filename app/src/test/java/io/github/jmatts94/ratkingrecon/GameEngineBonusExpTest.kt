package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * bankBonusExp: EXP from something other than a walked step - a Ledger Task
 * or Scrap Run's Foundry-born bonus. Runs through the same level-up/hatch
 * logic [GameEngine.onSteps] does, via the private bankExp both share.
 */
class GameEngineBonusExpTest {

    @Test
    fun `zero or negative is a no-op`() {
        val prefs = FakePrefs()
        val dao = FakeRatDao()
        prefs.values[GameEngine.KEY_EXP] = 100

        assertNull(GameEngine.bankBonusExp(dao, prefs, 0))
        assertNull(GameEngine.bankBonusExp(dao, prefs, -5))
        assertEquals("EXP must be untouched", 100, GameEngine.expOf(prefs))
        assertEquals("no rat should have been minted", 0, dao.rows.size)
    }

    @Test
    fun `a small grant banks exp without levelling`() {
        val prefs = FakePrefs()
        val dao = FakeRatDao()
        prefs.values[GameEngine.KEY_LEVEL] = 1
        prefs.values[GameEngine.KEY_EXP] = 0

        val hatched = GameEngine.bankBonusExp(dao, prefs, TaskBonuses.EXP_LEDGER_M3)

        assertNull("75 EXP should not clear level 1's 250 requirement", hatched)
        assertEquals(TaskBonuses.EXP_LEDGER_M3, GameEngine.expOf(prefs))
        assertEquals(1, GameEngine.levelOf(prefs))
    }

    @Test
    fun `a grant that clears the level requirement hatches exactly like a step would`() {
        val prefs = FakePrefs()
        val dao = FakeRatDao()
        prefs.values[GameEngine.KEY_LEVEL] = 1
        prefs.values[GameEngine.KEY_EXP] = GameEngine.maxExpFor(1) - 10

        val hatched = GameEngine.bankBonusExp(dao, prefs, 20)

        assertTrue("20 EXP should clear the last 10 needed", hatched != null)
        assertEquals(2, GameEngine.levelOf(prefs))
        assertEquals("overflow past the threshold is discarded, same as onSteps", 0, GameEngine.expOf(prefs))
        assertEquals(1, dao.rows.size)
        assertTrue("Room should have assigned an id", hatched!!.id != 0L)
    }
}
