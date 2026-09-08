package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Relic Trader's rules.
 *
 * One thing matters more than the rest: an exchange that cannot deliver must not
 * take the relics. That is the same promise the Shop makes about Scrap, and it
 * is easier to break here, because the item voucher can be unavailable for a
 * reason that has nothing to do with what the player is holding - every combat
 * item already at the Shop's own cap.
 */
class RelicTraderTest {

    private val gearTrade = RelicTrader.exchanges[0]
    private val reviveTrade = RelicTrader.exchanges[1]
    private val itemTrade = RelicTrader.exchanges[2]
    private val entryTrade = RelicTrader.exchanges[3]

    private fun prefsHolding(exchange: RelicExchange, amount: Int): FakePrefs {
        val prefs = FakePrefs()
        val editor = prefs.edit()
        Relics.grant(prefs, editor, exchange.relic, amount)
        editor.apply()
        return prefs
    }

    // ---- affordability -------------------------------------------------------

    @Test
    fun `every exchange costs three of one relic`() {
        for (exchange in RelicTrader.exchanges) {
            assertEquals(3, exchange.cost)
        }
        assertEquals(
            // Worn Cog excepted - it spends at the Gear Workshop, not here.
            // See Relics.ALL's own comment on why it exists at all.
            "one exchange per relic kind but Worn Cog",
            Relics.ALL.map { it.id } - "worn_cog",
            RelicTrader.exchanges.map { it.relic.id }
        )
    }

    @Test
    fun `two relics is not enough, and trying costs nothing`() {
        val prefs = prefsHolding(gearTrade, 2)

        val refusal = RelicTrader.trade(prefs, gearTrade)

        assertTrue(refusal is RelicRefusal.NotEnough)
        assertEquals(2, Relics.countOf(prefs, gearTrade.relic))
        assertEquals("no Scrap should have been paid", 0, prefs.getInt(GameEngine.KEY_SCRAP, 0))
    }

    // ---- the four rewards ----------------------------------------------------

    @Test
    fun `the gear trade pays inside its band and takes exactly three`() {
        repeat(50) {
            val prefs = prefsHolding(gearTrade, 4)

            assertNull(RelicTrader.trade(prefs, gearTrade))

            val paid = prefs.getInt(GameEngine.KEY_SCRAP, 0)
            assertTrue("paid $paid", paid in RelicTrader.SCRAP_PAYOUT)
            assertEquals(1, Relics.countOf(prefs, gearTrade.relic))
        }
    }

    @Test
    fun `the vial trade grants a revive token`() {
        val prefs = prefsHolding(reviveTrade, 3)

        assertEquals(0, ShopEffects.charges(prefs, ShopEffects.KEY_REVIVE_TOKENS))
        assertNull(RelicTrader.trade(prefs, reviveTrade))

        assertEquals(1, ShopEffects.charges(prefs, ShopEffects.KEY_REVIVE_TOKENS))
        assertEquals(0, Relics.countOf(prefs, reviveTrade.relic))
    }

    @Test
    fun `the blueprint trade grants whichever item was chosen`() {
        val prefs = prefsHolding(itemTrade, 3)

        assertNull(RelicTrader.trade(prefs, itemTrade, BattleItem.CLEANSE.name))

        assertEquals(1, ShopEffects.charges(prefs, ShopEffects.KEY_CLEANSE))
        assertEquals(0, ShopEffects.charges(prefs, ShopEffects.KEY_HP_TONIC))
        assertEquals(0, Relics.countOf(prefs, itemTrade.relic))
    }

    @Test
    fun `an item already at the shop's own cap is not offered, and falls back to one that isn't`() {
        val prefs = prefsHolding(itemTrade, 3)
        repeat(ShopEffects.ITEM_CHARGE_CAP) { ShopEffects.addCharge(prefs, ShopEffects.KEY_CLEANSE) }

        assertFalse(BattleItem.CLEANSE in RelicTrader.availableItemChoices(prefs))

        // Asking for the one already at cap falls through to one that is not.
        assertNull(RelicTrader.trade(prefs, itemTrade, BattleItem.CLEANSE.name))
        assertEquals(
            "the capped item must not have gone over",
            ShopEffects.ITEM_CHARGE_CAP,
            ShopEffects.charges(prefs, ShopEffects.KEY_CLEANSE)
        )
    }

    @Test
    fun `every item at cap refuses the trade and keeps the relics`() {
        val prefs = prefsHolding(itemTrade, 3)
        for (item in BattleItem.entries) {
            val key = when (item) {
                BattleItem.HP_TONIC -> ShopEffects.KEY_HP_TONIC
                BattleItem.CORROSIVE_CHARGE -> ShopEffects.KEY_CORROSIVE_CHARGE
                BattleItem.REINFORCED_PLATING -> ShopEffects.KEY_REINFORCED_PLATING
                BattleItem.CLEANSE -> ShopEffects.KEY_CLEANSE
            }
            repeat(ShopEffects.ITEM_CHARGE_CAP) { ShopEffects.addCharge(prefs, key) }
        }

        val refusal = RelicTrader.trade(prefs, itemTrade, BattleItem.CLEANSE.name)

        assertEquals(RelicRefusal.EveryItemFull, refusal)
        assertEquals(3, Relics.countOf(prefs, itemTrade.relic))
    }

    @Test
    fun `the wrench trade banks a voucher that waives the next arena entry`() {
        val prefs = prefsHolding(entryTrade, 3)

        assertEquals(Arena.ENTRY_COST, ShopEffects.arenaEntryCost(prefs))
        assertNull(RelicTrader.trade(prefs, entryTrade))

        assertEquals(0, ShopEffects.arenaEntryCost(prefs))
        assertEquals(0, Relics.countOf(prefs, entryTrade.relic))
    }

    @Test
    fun `entry vouchers stack but only one comes off an entry`() {
        val prefs = prefsHolding(entryTrade, 6)

        RelicTrader.trade(prefs, entryTrade)
        RelicTrader.trade(prefs, entryTrade)

        assertEquals(2, ShopEffects.charges(prefs, ShopEffects.KEY_ARENA_ENTRY_VOUCHER))
        assertEquals(0, ShopEffects.arenaEntryCost(prefs))

        ShopEffects.spendCharge(prefs, ShopEffects.KEY_ARENA_ENTRY_VOUCHER)
        assertEquals("one voucher left, still waived", 0, ShopEffects.arenaEntryCost(prefs))

        ShopEffects.spendCharge(prefs, ShopEffects.KEY_ARENA_ENTRY_VOUCHER)
        assertEquals(Arena.ENTRY_COST, ShopEffects.arenaEntryCost(prefs))
    }

    // ---- migration reaches the trader ---------------------------------------

    @Test
    fun `a legacy save cannot immediately afford a trade`() {
        val prefs = FakePrefs()
        prefs.edit()
            .putStringSet(Relics.KEY_LEGACY, mutableSetOf(gearTrade.relic.legacyName))
            .apply()

        // One of each is all the old set could hold, and a trade needs three.
        assertFalse(RelicTrader.canTrade(prefs, gearTrade))
        assertEquals(1, Relics.countOf(prefs, gearTrade.relic))
    }
}
