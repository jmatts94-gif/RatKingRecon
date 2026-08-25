package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which move a boss uses, and who its bonus lands on.
 */
class BossMovesTest {

    @Test
    fun `each named boss has exactly its own move, every time`() {
        assertEquals(BossMoves.GEAR_SMASH, BossMoves.forBoss("junk_golem", 1))
        assertEquals(BossMoves.GEAR_SMASH, BossMoves.forBoss("junk_golem", 5))
        assertEquals(BossMoves.RUSTY_RAKE, BossMoves.forBoss("old_ironclaw", 3))
        assertEquals(BossMoves.STEAM_JET, BossMoves.forBoss("boiler_baron", 2))
        assertEquals(BossMoves.WIRE_MESH, BossMoves.forBoss("circuit_reaper", 4))
    }

    @Test
    fun `an unrecognised boss id has no move, the same fallback the flavour text uses`() {
        assertNull(BossMoves.forBoss("no_such_boss", 1))
    }

    @Test
    fun `rustbringer rotates through all four in a fixed order`() {
        val sequence = (1..8).map { BossMoves.forBoss("rustbringer", it)?.id }
        assertEquals(
            listOf(
                "gear_smash", "rusty_rake", "steam_jet", "wire_mesh",
                "gear_smash", "rusty_rake", "steam_jet", "wire_mesh"
            ),
            sequence
        )
    }

    @Test
    fun `a named boss's bonus only lands on its own faction`() {
        assertTrue(BossMoves.bonusApplies(BossMoves.GEAR_SMASH, Roster.SMUGGLERS))
        assertFalse(BossMoves.bonusApplies(BossMoves.GEAR_SMASH, Roster.TINKERERS))
        assertFalse("no faction on the rat means no faction to hit", BossMoves.bonusApplies(BossMoves.GEAR_SMASH, null))
    }

    @Test
    fun `rustbringer's copies spare no faction`() {
        val rustbringerMoves = (1..4).map { BossMoves.forBoss("rustbringer", it)!! }
        val everyFaction = listOf(
            Roster.TINKERERS, Roster.FOUNDRY_BORN, Roster.BRAWLERS, Roster.SCAVENGERS, Roster.SMUGGLERS
        )

        for (move in rustbringerMoves) {
            for (faction in everyFaction) {
                assertTrue("${move.id} should hit $faction", BossMoves.bonusApplies(move, faction))
            }
            assertTrue("${move.id} should hit even an unknown faction", BossMoves.bonusApplies(move, null))
        }
    }

    @Test
    fun `only rusty rake carries the dot`() {
        assertTrue(BossMoves.RUSTY_RAKE.appliesDot)
        assertFalse(BossMoves.GEAR_SMASH.appliesDot)
        assertFalse(BossMoves.STEAM_JET.appliesDot)
        assertFalse(BossMoves.WIRE_MESH.appliesDot)
    }

    // --- the faction wheel: each boss's own weakness --------------------------

    @Test
    fun `each boss is weak to the faction one step behind the one it targets`() {
        assertEquals("strong vs Smugglers, weak vs Brawlers", Roster.BRAWLERS, BossMoves.weakFactionFor("junk_golem"))
        assertEquals("strong vs Tinkerers, weak vs Smugglers", Roster.SMUGGLERS, BossMoves.weakFactionFor("old_ironclaw"))
        assertEquals("strong vs Scavengers, weak vs Tinkerers", Roster.TINKERERS, BossMoves.weakFactionFor("boiler_baron"))
        assertEquals("strong vs Brawlers, weak vs Scavengers", Roster.SCAVENGERS, BossMoves.weakFactionFor("circuit_reaper"))
    }

    @Test
    fun `rustbringer is weak to nothing`() {
        assertNull(BossMoves.weakFactionFor("rustbringer"))
    }

    @Test
    fun `an unrecognised boss id has no weakness either`() {
        assertNull(BossMoves.weakFactionFor("no_such_boss"))
    }

    @Test
    fun `the wheel is a closed cycle of four - foundry-born is nobody's weakness`() {
        val weaknesses = listOf("junk_golem", "old_ironclaw", "boiler_baron", "circuit_reaper")
            .map { BossMoves.weakFactionFor(it) }

        assertEquals(
            "every named boss has a distinct weakness",
            weaknesses.size,
            weaknesses.distinct().size
        )
        assertFalse(Roster.FOUNDRY_BORN in weaknesses)
    }
}
