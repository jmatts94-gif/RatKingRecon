package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four permanent buffs: that each latches at its own threshold, stays
 * latched once earned, and that its fixed effect actually lands where it is
 * supposed to - the Fusion Pot's odds, and the two [Battle] hooks.
 */
class PermanentBuffsTest {

    // ---- latching --------------------------------------------------------

    @Test
    fun `collector's instinct latches at exactly five hatches, not before`() {
        val prefs = FakePrefs()
        PermanentBuffs.checkCollectorsInstinct(prefs, 4L)
        assertFalse(PermanentBuffs.isEarned(prefs, PermanentBuffs.COLLECTORS_INSTINCT))

        PermanentBuffs.checkCollectorsInstinct(prefs, 5L)
        assertTrue(PermanentBuffs.isEarned(prefs, PermanentBuffs.COLLECTORS_INSTINCT))
    }

    @Test
    fun `steadfast momentum latches at a ten day streak`() {
        val prefs = FakePrefs()
        PermanentBuffs.checkSteadfastMomentum(prefs, 9)
        assertFalse(PermanentBuffs.isEarned(prefs, PermanentBuffs.STEADFAST_MOMENTUM))

        PermanentBuffs.checkSteadfastMomentum(prefs, 10)
        assertTrue(PermanentBuffs.isEarned(prefs, PermanentBuffs.STEADFAST_MOMENTUM))
    }

    @Test
    fun `steadfast momentum stays earned after the streak later breaks`() {
        val prefs = FakePrefs()
        PermanentBuffs.checkSteadfastMomentum(prefs, 10)
        assertTrue(PermanentBuffs.isEarned(prefs, PermanentBuffs.STEADFAST_MOMENTUM))

        // A broken streak resets Streak's own counter, but never un-latches
        // a buff already earned - same one-way rule Milestones follows.
        PermanentBuffs.checkSteadfastMomentum(prefs, 0)
        assertTrue(PermanentBuffs.isEarned(prefs, PermanentBuffs.STEADFAST_MOMENTUM))
    }

    @Test
    fun `rusted fang latches only when fight ten is actually cleared`() {
        val prefs = FakePrefs()
        assertFalse(PermanentBuffs.isEarned(prefs, PermanentBuffs.RUSTED_FANG))

        PermanentBuffs.checkRustedFang(prefs)
        assertTrue(PermanentBuffs.isEarned(prefs, PermanentBuffs.RUSTED_FANG))
    }

    @Test
    fun `iron boots latches at thirty thousand lifetime steps`() {
        val prefs = FakePrefs()
        PermanentBuffs.checkIronBoots(prefs, 29_999L)
        assertFalse(PermanentBuffs.isEarned(prefs, PermanentBuffs.IRON_BOOTS))

        PermanentBuffs.checkIronBoots(prefs, 30_000L)
        assertTrue(PermanentBuffs.isEarned(prefs, PermanentBuffs.IRON_BOOTS))
    }

    @Test
    fun `earned count reflects exactly what has latched`() {
        val prefs = FakePrefs()
        assertEquals(0, PermanentBuffs.earnedCount(prefs))

        PermanentBuffs.checkIronBoots(prefs, 30_000L)
        PermanentBuffs.checkRustedFang(prefs)
        assertEquals(2, PermanentBuffs.earnedCount(prefs))
    }

    // ---- Collector's Instinct: the Fusion Pot's own odds -------------------

    @Test
    fun `the splice odds bonus is zero until earned`() {
        val prefs = FakePrefs()
        assertEquals(0.0, PermanentBuffs.spliceOddsBonusFor(prefs), 0.0)
    }

    @Test
    fun `the bonus turns a roll that would be common into rare`() {
        // Roll 31 (RARE_ROLL + 1) lands Common with no bonus...
        assertTrue(Fusion.speciesFor(Fusion.RARE_ROLL + 1).artKey in Roster.common.map { it.artKey })
        // ...but Rare once Collector's Instinct's flat +10 points widens the band.
        assertTrue(Fusion.speciesFor(Fusion.RARE_ROLL + 1, bonusFraction = 0.10).artKey in Roster.rare.map { it.artKey })
    }

    @Test
    fun `the bonus widens the rare band without touching legendary's own cutoff`() {
        // Legendary's cutoff is untouched by the bonus - the widened band comes
        // entirely out of Common, at Rare's expense.
        assertTrue(Fusion.speciesFor(Fusion.LEGENDARY_ROLL, bonusFraction = 0.10).artKey in Roster.legendary.map { it.artKey })
        assertTrue(Fusion.speciesFor(Fusion.LEGENDARY_ROLL + 1, bonusFraction = 0.10).artKey in Roster.rare.map { it.artKey })

        // Roll 40 (RARE_ROLL + 10) is the new edge: still Rare with the bonus...
        assertTrue(Fusion.speciesFor(40, bonusFraction = 0.10).artKey in Roster.rare.map { it.artKey })
        // ...but roll 41 falls back to Common even with the bonus applied.
        assertTrue(Fusion.speciesFor(41, bonusFraction = 0.10).artKey in Roster.common.map { it.artKey })
    }

    // ---- Steadfast Momentum: Contract Board payout and steps' own EXP -----

    @Test
    fun `the step reward multiplier is 1x until earned, 1_15x after`() {
        val prefs = FakePrefs()
        assertEquals(1.0, PermanentBuffs.stepRewardMultiplierFor(prefs), 0.0)

        PermanentBuffs.checkSteadfastMomentum(prefs, 10)
        assertEquals(1.15, PermanentBuffs.stepRewardMultiplierFor(prefs), 0.0001)
    }

    // ---- Rusted Fang and Iron Boots: the two Battle hooks -------------------

    @Test
    fun `rusted fang heals a fixed share of damage dealt, every hit`() {
        // Started below max HP - at full HP there is nothing for the heal to
        // fill, and a capped-to-zero heal would look identical to no heal at all.
        val b = Battle(
            "Rat", 20, 100, "Rustbot", 1, 1000,
            startingRatHp = 50,
            lifestealFraction = PermanentBuffs.RUSTED_FANG_LIFESTEAL_FRACTION
        )
        b.advance(BattleAction.ATTACK).also {
            assertEquals(20, it.damageDealt)
            assertEquals(1, it.buffLifesteal) // 5% of 20 = 1
        }
    }

    @Test
    fun `rusted fang heals nothing on a round that deals no damage`() {
        val b = Battle(
            "Rat", 20, 50, "Rustbot", 1, 1000,
            startingRatHp = 25,
            lifestealFraction = PermanentBuffs.RUSTED_FANG_LIFESTEAL_FRACTION
        )
        val r = b.advance(BattleAction.DEFEND)
        assertEquals(0, r.damageDealt)
        assertEquals(0, r.buffLifesteal)
    }

    @Test
    fun `rusted fang never heals past the rat's own max hp`() {
        // 100 damage dealt would heal 5 (5%) - enough to overshoot max HP from
        // a starting HP of 19 out of 20. The bot's own maxHp is 1, so it dies
        // on this hit and never counter-attacks, isolating the heal.
        val b = Battle(
            "Rat", 100, 20, "Rustbot", 1, 1,
            startingRatHp = 19,
            lifestealFraction = PermanentBuffs.RUSTED_FANG_LIFESTEAL_FRACTION
        )
        val r = b.advance(BattleAction.ATTACK)
        assertEquals(20, b.ratHp)
        assertEquals("healed only the 1 point left before the cap, not the full 5%", 1, r.buffLifesteal)
    }

    @Test
    fun `iron boots shaves a fixed share off incoming damage`() {
        val withReduction = Battle(
            "Rat", 1, 10_000, "Rustbot", 100, 1000,
            incomingDamageReduction = PermanentBuffs.IRON_BOOTS_DAMAGE_REDUCTION
        )
        val withoutReduction = Battle("Rat", 1, 10_000, "Rustbot", 100, 1000)

        // Bot survives the rat's own weak hit either way, so its counter-swing
        // always lands - isolating the reduction's effect on damageTaken.
        val reduced = withReduction.advance(BattleAction.ATTACK).damageTaken
        val plain = withoutReduction.advance(BattleAction.ATTACK).damageTaken

        assertEquals(90, reduced) // 100 * (1 - 0.10)
        assertEquals(100, plain)
    }

    @Test
    fun `iron boots stacks on top of defend rather than replacing it`() {
        val b = Battle(
            "Rat", 1, 10_000, "Rustbot", 100, 1000,
            incomingDamageReduction = PermanentBuffs.IRON_BOOTS_DAMAGE_REDUCTION
        )
        val taken = b.advance(BattleAction.DEFEND).damageTaken
        assertEquals(45, taken) // (100 / 2) * (1 - 0.10)
    }

    @Test
    fun `iron boots and rusted fang can both apply in the same battle`() {
        // Started below max HP, or the lifesteal below would have nothing to
        // heal into and read as zero regardless of whether it fired.
        val b = Battle(
            "Rat", 20, 10_000, "Rustbot", 100, 1000,
            startingRatHp = 9_000,
            lifestealFraction = PermanentBuffs.RUSTED_FANG_LIFESTEAL_FRACTION,
            incomingDamageReduction = PermanentBuffs.IRON_BOOTS_DAMAGE_REDUCTION
        )
        val r = b.advance(BattleAction.ATTACK)
        assertEquals(1, r.buffLifesteal) // 5% of 20 dealt
        assertEquals(90, r.damageTaken)  // 100 taken, 10% shaved off
    }
}
