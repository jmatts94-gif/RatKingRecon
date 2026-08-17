package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rarity stat bonus.
 *
 * Rarity is never stored on a [RatEntity] - every one of these numbers is
 * re-derived from artKey against the roster of today, so that is what gets
 * exercised here as much as the bonus values themselves.
 */
class RarityBonusTest {

    private fun rat(artKey: String, power: Int = 3, toughness: Int = 3, bonusHp: Int = 0) =
        RatEntity(
            artKey = artKey, name = "R", power = power, toughness = toughness,
            shiny = false, bonusHp = bonusHp
        )

    // ---- the tier -> bonus mapping --------------------------------------------

    @Test
    fun `bonus by tier`() {
        assertEquals(0, Roster.statBonusFor(Roster.COMMON))
        assertEquals(1, Roster.statBonusFor(Roster.RARE))
        assertEquals(2, Roster.statBonusFor(Roster.LEGENDARY))
    }

    @Test
    fun `matched without case, same as withRarity`() {
        assertEquals(0, Roster.statBonusFor("common"))
        assertEquals(1, Roster.statBonusFor("RARE"))
        assertEquals(2, Roster.statBonusFor("legendary"))
    }

    @Test
    fun `unknown or missing rarity gets no bonus rather than a guess`() {
        assertEquals(0, Roster.statBonusFor(null))
        assertEquals(0, Roster.statBonusFor("nonsense"))
    }

    // ---- the artKey lookup -----------------------------------------------------

    @Test
    fun `rarity is looked up from the roster by artKey`() {
        assertEquals(Roster.RARE, rat("flux_pic").rarity)
        assertEquals(Roster.LEGENDARY, rat("forman_pic").rarity)
        assertEquals(Roster.COMMON, rat("bolt_pic").rarity)
    }

    @Test
    fun `an artKey the roster no longer knows gets no bonus rather than a guess`() {
        val ghost = rat("does_not_exist_pic")
        assertNull(ghost.rarity)
        assertEquals("no rarity, no bonus", ghost.power, ghost.effectivePower)
        assertEquals("no rarity, no bonus", ghost.toughness, ghost.effectiveToughness)
    }

    // ---- effective stats ---------------------------------------------------------

    @Test
    fun `effective stats add the bonus - stored stats never change`() {
        val common = rat("bolt_pic")
        assertEquals(3, common.effectivePower)
        assertEquals(3, common.effectiveToughness)

        val rare = rat("flux_pic")
        assertEquals(4, rare.effectivePower)
        assertEquals(4, rare.effectiveToughness)
        assertEquals("the stored stat is untouched", 3, rare.power)
        assertEquals("the stored stat is untouched", 3, rare.toughness)

        val legendary = rat("forman_pic")
        assertEquals(5, legendary.effectivePower)
        assertEquals(5, legendary.effectiveToughness)
    }

    @Test
    fun `effective max hp is ten times the boosted toughness, plus bonusHp`() {
        val legendary = rat("forman_pic", toughness = 3, bonusHp = 7)
        // toughness 3 + 2 rarity = 5, times ten, plus the flat HP bonus
        assertEquals(57, legendary.effectiveMaxHp)
    }

    // ---- where the bonus is spent, and where it deliberately is not -----------

    @Test
    fun `toBattle fights on effective stats, not stored ones`() {
        val rare = rat("flux_pic")
        val encounter = Encounter(1, "Bot", 1, 100, 0)
        val battle = encounter.toBattle(rare)

        assertEquals("3 power + 1 Rare bonus", 4, battle.attackDamage())
        assertEquals("(3 toughness + 1 Rare bonus) * 10", 40, battle.ratMaxHp)
    }

    /**
     * The reason the bonus is a genuine edge rather than a wash: the Rustbot
     * is sized when the encounter is raised, off the rat's stored stats -
     * exactly like a Loadout buff, which it has also never known about. If
     * this read the boosted stat instead, a Legendary rat would just draw a
     * harder Rustbot to match and end up no better off.
     */
    @Test
    fun `a rustbot is sized off the stored stat, not the boosted one`() {
        val commonBot = RustbotFactory.forEncounter(20, rat("bolt_pic", power = 4, toughness = 4))
        val legendaryBot = RustbotFactory.forEncounter(20, rat("forman_pic", power = 4, toughness = 4))

        assertEquals(
            "same base stats must draw the same Rustbot regardless of rarity",
            commonBot.power, legendaryBot.power
        )
        assertEquals(
            "same base stats must draw the same Rustbot regardless of rarity",
            commonBot.maxHp, legendaryBot.maxHp
        )
    }
}
