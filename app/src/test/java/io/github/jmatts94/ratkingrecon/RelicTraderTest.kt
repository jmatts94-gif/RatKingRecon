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
 * take the relics. That is the same promise the Shop makes about Scrap. The
 * item-voucher refusal this used to guard - every combat item already at the
 * Shop's own cap - now only reaches through AchievementRewards.SalvageCache,
 * covered in AchievementRewardsTest; no exchange here grants ItemVoucher any
 * more, see RelicTrader.exchanges.
 */
class RelicTraderTest {

    private val gearTrade = RelicTrader.exchanges[0]
    private val reviveTrade = RelicTrader.exchanges[1]
    private val rationsTrade = RelicTrader.exchanges[2]
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
    fun `the blueprint trade arms Trail Rations and takes exactly three`() {
        val prefs = prefsHolding(rationsTrade, 3)

        assertFalse(ShopEffects.trailRationsArmed(prefs))
        assertNull(RelicTrader.trade(prefs, rationsTrade))

        assertTrue(ShopEffects.trailRationsArmed(prefs))
        assertEquals(0, Relics.countOf(prefs, rationsTrade.relic))
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
