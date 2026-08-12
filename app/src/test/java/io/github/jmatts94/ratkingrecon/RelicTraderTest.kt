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
 * is easier to break here, because two of the four rewards can be unavailable
 * for reasons that have nothing to do with what the player is holding - every
 * frame already owned, or a combat buff already armed.
 */
class RelicTraderTest {

    private val gearTrade = RelicTrader.exchanges[0]
    private val frameTrade = RelicTrader.exchanges[1]
    private val buffTrade = RelicTrader.exchanges[2]
    private val voucherTrade = RelicTrader.exchanges[3]

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
            "one exchange per relic kind",
            Relics.ALL.map { it.id },
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
    fun `the frame trade grants the frame that was chosen`() {
        val prefs = prefsHolding(frameTrade, 3)

        assertNull(RelicTrader.trade(prefs, frameTrade, Shop.FRAME_EMBER))

        assertTrue(ShopEffects.ownsCosmetic(prefs, Shop.FRAME_EMBER))
        assertFalse(ShopEffects.ownsCosmetic(prefs, Shop.FRAME_BRASS))
        assertEquals(0, Relics.countOf(prefs, frameTrade.relic))
    }

    @Test
    fun `a frame already owned is never handed over twice`() {
        val prefs = prefsHolding(frameTrade, 3)
        ShopEffects.grantCosmetic(prefs, Shop.FRAME_BRASS)

        // Asking for the one already owned falls through to the one that is not.
        assertNull(RelicTrader.trade(prefs, frameTrade, Shop.FRAME_BRASS))

        assertTrue(ShopEffects.ownsCosmetic(prefs, Shop.FRAME_EMBER))
    }

    @Test
    fun `owning every frame refuses the trade and keeps the relics`() {
        val prefs = prefsHolding(frameTrade, 3)
        ShopEffects.grantCosmetic(prefs, Shop.FRAME_BRASS)
        ShopEffects.grantCosmetic(prefs, Shop.FRAME_EMBER)

        val refusal = RelicTrader.trade(prefs, frameTrade, Shop.FRAME_BRASS)

        assertEquals(RelicRefusal.OwnsEveryFrame, refusal)
        assertEquals("the relics must survive a refusal", 3, Relics.countOf(prefs, frameTrade.relic))
        assertFalse(RelicTrader.canTrade(prefs, frameTrade))
    }

    @Test
    fun `the blueprint trade arms whichever buff was chosen`() {
        val surge = prefsHolding(buffTrade, 3)
        assertNull(RelicTrader.trade(surge, buffTrade, ShopEffects.KEY_POWER_SURGE))
        assertTrue(ShopEffects.powerSurgeArmed(surge))
        assertFalse(ShopEffects.wrenchArmed(surge))

        val wrench = prefsHolding(buffTrade, 3)
        assertNull(RelicTrader.trade(wrench, buffTrade, ShopEffects.KEY_GOLDEN_WRENCH))
        assertTrue(ShopEffects.wrenchArmed(wrench))
        assertFalse(ShopEffects.powerSurgeArmed(wrench))
    }

    /**
     * The Shop already refuses to sell a second buff, because only one can ride
     * a fight. Trading for one has to refuse for the same reason, or the relics
     * buy something that is immediately thrown away.
     */
    @Test
    fun `a buff already armed refuses the trade and keeps the relics`() {
        val prefs = prefsHolding(buffTrade, 3)
        prefs.edit().putBoolean(ShopEffects.KEY_GOLDEN_WRENCH, true).apply()

        val refusal = RelicTrader.trade(prefs, buffTrade, ShopEffects.KEY_POWER_SURGE)

        assertEquals(RelicRefusal.BuffAlreadyArmed, refusal)
        assertEquals(3, Relics.countOf(prefs, buffTrade.relic))
        assertFalse("the wrong buff must not have been armed", ShopEffects.powerSurgeArmed(prefs))
    }

    @Test
    fun `the wrench trade banks a voucher that discounts the hatchery`() {
        val prefs = prefsHolding(voucherTrade, 3)

        assertEquals(Masterwork.PRICE, ShopEffects.masterworkPrice(prefs))
        assertNull(RelicTrader.trade(prefs, voucherTrade))

        assertEquals(
            Masterwork.PRICE - ShopEffects.MASTERWORK_VOUCHER_VALUE,
            ShopEffects.masterworkPrice(prefs)
        )
        assertEquals(0, Relics.countOf(prefs, voucherTrade.relic))
    }

    @Test
    fun `vouchers stack but only one comes off a purchase`() {
        val prefs = prefsHolding(voucherTrade, 6)

        RelicTrader.trade(prefs, voucherTrade)
        RelicTrader.trade(prefs, voucherTrade)

        assertEquals(2, ShopEffects.charges(prefs, ShopEffects.KEY_MASTERWORK_VOUCHER))
        assertEquals(
            "two vouchers must not stack into one purchase",
            Masterwork.PRICE - ShopEffects.MASTERWORK_VOUCHER_VALUE,
            ShopEffects.masterworkPrice(prefs)
        )

        // Spending one leaves the next purchase still discounted.
        ShopEffects.spendCharge(prefs, ShopEffects.KEY_MASTERWORK_VOUCHER)
        assertEquals(
            Masterwork.PRICE - ShopEffects.MASTERWORK_VOUCHER_VALUE,
            ShopEffects.masterworkPrice(prefs)
        )

        ShopEffects.spendCharge(prefs, ShopEffects.KEY_MASTERWORK_VOUCHER)
        assertEquals(Masterwork.PRICE, ShopEffects.masterworkPrice(prefs))
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
