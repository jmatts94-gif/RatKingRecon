package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a rat's faction is worth to a Ledger Task or the Scrap Run.
 */
class TaskBonusesTest {

    // --- Smugglers: Scrap ------------------------------------------------------

    @Test
    fun `a smuggler boosts the reward, everyone else leaves it alone`() {
        assertEquals(230, TaskBonuses.scrapFor(200, Roster.SMUGGLERS))
        assertEquals(200, TaskBonuses.scrapFor(200, Roster.TINKERERS))
        assertEquals(200, TaskBonuses.scrapFor(200, null))
    }

    @Test
    fun `the smuggler bonus is case-insensitive, matching Roster's own convention`() {
        assertEquals(230, TaskBonuses.scrapFor(200, "smugglers"))
    }

    // --- Scavengers: duration ----------------------------------------------------

    @Test
    fun `a scavenger shortens the duration, everyone else leaves it alone`() {
        assertEquals(3_600_000L, TaskBonuses.durationFor(4_000_000L, Roster.SCAVENGERS))
        assertEquals(4_000_000L, TaskBonuses.durationFor(4_000_000L, Roster.BRAWLERS))
        assertEquals(4_000_000L, TaskBonuses.durationFor(4_000_000L, null))
    }

    // --- Tinkerers: relic chance -------------------------------------------------

    @Test
    fun `a tinkerer adds to the relic chance, everyone else leaves it alone`() {
        assertEquals(0.18, TaskBonuses.relicChanceFor(0.10, Roster.TINKERERS), 0.0001)
        assertEquals(0.50, TaskBonuses.relicChanceFor(0.50, Roster.SMUGGLERS), 0.0001)
    }

    @Test
    fun `the tinkerer bonus never pushes the chance past certainty`() {
        assertEquals(1.0, TaskBonuses.relicChanceFor(0.97, Roster.TINKERERS), 0.0001)
    }

    // --- Brawlers: instant complete ----------------------------------------------

    @Test
    fun `only a brawler can roll instant-complete, and it fires sometimes not always`() {
        assertFalse(TaskBonuses.rollsInstantComplete(null))
        assertFalse(TaskBonuses.rollsInstantComplete(Roster.SMUGGLERS))

        val hits = (1..4000).count { TaskBonuses.rollsInstantComplete(Roster.BRAWLERS) }
        val rate = hits / 4000.0
        assertTrue(
            "expected roughly ${TaskBonuses.INSTANT_COMPLETE_CHANCE}, got $rate",
            rate in 0.02..0.08
        )
    }

    @Test
    fun `endTimeFor advances by the bonus-adjusted duration when instant-complete does not fire`() {
        // No faction at all can never roll instant-complete, so this is exact
        // rather than statistical.
        val start = 1_000_000L
        assertEquals(start + 4_000_000L, TaskBonuses.endTimeFor(start, 4_000_000L, null))
        assertEquals(start + 3_600_000L, TaskBonuses.endTimeFor(start, 4_000_000L, Roster.SCAVENGERS))
    }

    @Test
    fun `endTimeFor can land on the start time itself for a brawler`() {
        val start = 1_000_000L
        val landedAtStart = (1..4000).any {
            TaskBonuses.endTimeFor(start, 4_000_000L, Roster.BRAWLERS) == start
        }
        assertTrue("4000 rolls at a 5% chance never once landed instantly", landedAtStart)
    }

    // --- Foundry-born: EXP --------------------------------------------------------

    @Test
    fun `foundry-born pays flat exp per activity, everyone else gets none`() {
        assertEquals(TaskBonuses.EXP_EXPEDITION, TaskBonuses.expFor(TaskBonuses.Activity.EXPEDITION, Roster.FOUNDRY_BORN))
        assertEquals(TaskBonuses.EXP_LEDGER_M1, TaskBonuses.expFor(TaskBonuses.Activity.LEDGER_M1, Roster.FOUNDRY_BORN))
        assertEquals(TaskBonuses.EXP_LEDGER_M2, TaskBonuses.expFor(TaskBonuses.Activity.LEDGER_M2, Roster.FOUNDRY_BORN))
        assertEquals(TaskBonuses.EXP_LEDGER_M3, TaskBonuses.expFor(TaskBonuses.Activity.LEDGER_M3, Roster.FOUNDRY_BORN))

        assertEquals(0, TaskBonuses.expFor(TaskBonuses.Activity.EXPEDITION, Roster.BRAWLERS))
        assertEquals(0, TaskBonuses.expFor(TaskBonuses.Activity.LEDGER_M3, null))
    }

    @Test
    fun `every foundry-born grant is small next to the levelling curve`() {
        // Level 2 alone already costs 500 EXP (250 per level) - the biggest
        // single grant (M3) is a fraction of even the very first level-up.
        assertTrue(TaskBonuses.EXP_LEDGER_M3 < GameEngine.maxExpFor(2))
    }

    // --- description lines ---------------------------------------------------------

    @Test
    fun `every one of the five factions has its own description, and nobody else does`() {
        assertNull(TaskBonuses.descriptionFor(null))
        assertNull(TaskBonuses.descriptionFor("Not a real faction"))

        val descriptions = listOf(
            Roster.SMUGGLERS, Roster.SCAVENGERS, Roster.TINKERERS, Roster.BRAWLERS, Roster.FOUNDRY_BORN
        ).map { TaskBonuses.descriptionFor(it) }

        assertTrue("every faction should have a description", descriptions.all { it != null })
        assertEquals("all five descriptions should be distinct", 5, descriptions.distinct().size)
    }
}
