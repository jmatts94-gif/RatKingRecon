package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each achievement pays, and that granting it actually lands.
 */
class AchievementRewardsTest {

    @Test
    fun `scrap rewards credit the right amount, additively`() {
        val prefs = FakePrefs()
        AchievementRewards.grant(prefs, AchievementReward.Scrap(25))
        assertEquals(25, GameEngine.scrapOf(prefs))

        AchievementRewards.grant(prefs, AchievementReward.Scrap(50))
        assertEquals(75, GameEngine.scrapOf(prefs))
    }

    @Test
    fun `free mutagen arms the same flag the shop item does`() {
        val prefs = FakePrefs()
        AchievementRewards.grant(prefs, AchievementReward.FreeMutagen)
        assertTrue(prefs.getBoolean(GameEngine.KEY_MUTAGEN, false))
    }

    @Test
    fun `revive token stacks the same charge the shop sells`() {
        val prefs = FakePrefs()
        AchievementRewards.grant(prefs, AchievementReward.ReviveToken)
        assertEquals(1, ShopEffects.charges(prefs, ShopEffects.KEY_REVIVE_TOKENS))
    }

    @Test
    fun `salvage cache grants exactly one combat item`() {
        val prefs = FakePrefs()
        val before = BattleItem.entries.sumOf { ShopEffects.charges(prefs, keyFor(it)) }

        AchievementRewards.grant(prefs, AchievementReward.SalvageCache)

        val after = BattleItem.entries.sumOf { ShopEffects.charges(prefs, keyFor(it)) }
        assertEquals(1, after - before)
    }

    /** The one edge RelicTrader's own Item Voucher refuses outright - a badge already latched cannot. */
    @Test
    fun `salvage cache falls back to scrap once every item is at cap`() {
        val prefs = FakePrefs()
        BattleItem.entries.forEach { item ->
            repeat(ShopEffects.ITEM_CHARGE_CAP) { ShopEffects.addCharge(prefs, keyFor(item)) }
        }

        AchievementRewards.grant(prefs, AchievementReward.SalvageCache)

        assertEquals(40, GameEngine.scrapOf(prefs))
    }

    @Test
    fun `cosmetic rewards grant and equip the frame`() {
        val prefs = FakePrefs()
        AchievementRewards.grant(prefs, AchievementReward.Cosmetic(Frames.IRON_GRIP.id))

        assertTrue(ShopEffects.ownsCosmetic(prefs, Frames.IRON_GRIP.id))
        assertEquals(Frames.IRON_GRIP.id, ShopEffects.equippedCosmetic(prefs))
    }

    @Test
    fun `every combat badge maps to a reward`() {
        for (boss in Bosses.all) {
            assertTrue("no reward mapped for ${boss.id}", AchievementRewards.forBoss(boss.id) != null)
        }
    }

    @Test
    fun `every hatching, roster and step milestone maps to a reward`() {
        for (milestone in Milestones.hatching + Milestones.roster + Milestones.steps) {
            assertTrue("no reward mapped for ${milestone.id}", AchievementRewards.forMilestone(milestone.id) != null)
        }
    }

    /**
     * The interaction this whole flag exists to prevent: a frame meant to be
     * earned one specific way must never also fall out of a lucky Arena
     * clear with the real achievement behind it never actually met.
     */
    @Test
    fun `achievement-exclusive frames are never in the Arena's weighted pool`() {
        assertFalse(Frames.IRON_GRIP.arenaPool)
        assertFalse(Frames.NATURALISTS_COMPENDIUM.arenaPool)
        assertFalse(Frames.RAT_KINGS_CROWN.arenaPool)
        assertTrue("the Arena's own flagship frame must stay in its own pool", Frames.ARENA_CHAMPION.arenaPool)
    }

    private fun keyFor(item: BattleItem): String = when (item) {
        BattleItem.HP_TONIC -> ShopEffects.KEY_HP_TONIC
        BattleItem.CORROSIVE_CHARGE -> ShopEffects.KEY_CORROSIVE_CHARGE
        BattleItem.REINFORCED_PLATING -> ShopEffects.KEY_REINFORCED_PLATING
        BattleItem.CLEANSE -> ShopEffects.KEY_CLEANSE
    }
}
