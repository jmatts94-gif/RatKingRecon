package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What owning a Shop item actually does.
 *
 * These cover the parts that are easy to get subtly wrong - the Quick Return
 * arithmetic, and the conditions under which an effect refuses to apply, which
 * is what stops the Shop charging for a purchase that does nothing.
 */
class ShopEffectsTest {

    private val HOUR = 60L * 60L * 1000L

    private fun prefsWithExpedition(remainingMs: Long): FakePrefs {
        val prefs = FakePrefs()
        prefs.edit()
            .putBoolean("EXPEDITION_ACTIVE", true)
            .putLong("EXPEDITION_END_TIME", System.currentTimeMillis() + remainingMs)
            .apply()
        return prefs
    }

    // ---- charges -------------------------------------------------------------

    @Test
    fun `charges stack and are spent one at a time`() {
        val prefs = FakePrefs()
        val key = ShopEffects.KEY_REVIVE_TOKENS

        assertEquals(0, ShopEffects.charges(prefs, key))

        ShopEffects.addCharge(prefs, key)
        ShopEffects.addCharge(prefs, key)
        assertEquals(2, ShopEffects.charges(prefs, key))

        assertTrue(ShopEffects.spendCharge(prefs, key))
        assertEquals(1, ShopEffects.charges(prefs, key))
    }

    /** The ordinary path for a player who never bought a token. */
    @Test
    fun `spending a charge you do not hold changes nothing`() {
        val prefs = FakePrefs()
        val key = ShopEffects.KEY_REVIVE_TOKENS

        assertFalse(ShopEffects.spendCharge(prefs, key))
        assertEquals(0, ShopEffects.charges(prefs, key))
    }

    // ---- power surge ---------------------------------------------------------

    @Test
    fun `surge changes nothing until it is armed`() {
        val prefs = FakePrefs()
        assertEquals(4, ShopEffects.loadoutFor(prefs).powerFor(4))

        prefs.edit().putBoolean(ShopEffects.KEY_POWER_SURGE, true).apply()
        assertEquals(6, ShopEffects.loadoutFor(prefs).powerFor(4))
    }

    // ---- quick return --------------------------------------------------------

    @Test
    fun `quick return takes a quarter off the time remaining`() {
        val prefs = prefsWithExpedition(remainingMs = 4 * HOUR)

        assertTrue(ShopEffects.quickReturn(prefs))

        val left = prefs.getLong("EXPEDITION_END_TIME", 0L) - System.currentTimeMillis()
        // Three hours, allowing a second either side for the clock moving.
        assertTrue("left was ${left / 60000} minutes", left in (3 * HOUR - 1000)..(3 * HOUR + 1000))
    }

    /** Repeatable, and always a quarter of what is left rather than of the original. */
    @Test
    fun `quick return never finishes the expedition outright`() {
        val prefs = prefsWithExpedition(remainingMs = 4 * HOUR)

        repeat(10) { ShopEffects.quickReturn(prefs) }

        val left = prefs.getLong("EXPEDITION_END_TIME", 0L) - System.currentTimeMillis()
        assertTrue("expedition should still be running, left was $left", left > 0)
    }

    @Test
    fun `quick return refuses when no rat is out`() {
        val prefs = FakePrefs()
        assertFalse(ShopEffects.quickReturn(prefs))
    }

    @Test
    fun `quick return refuses once the expedition is already up`() {
        val prefs = prefsWithExpedition(remainingMs = -HOUR)

        assertFalse(ShopEffects.quickReturn(prefs))
    }

    // ---- cosmetics -----------------------------------------------------------

    @Test
    fun `buying a frame owns it and puts it on`() {
        val prefs = FakePrefs()

        assertFalse(ShopEffects.ownsCosmetic(prefs, Shop.FRAME_BRASS))
        assertNull(ShopEffects.equippedCosmetic(prefs))

        ShopEffects.grantCosmetic(prefs, Shop.FRAME_BRASS)

        assertTrue(ShopEffects.ownsCosmetic(prefs, Shop.FRAME_BRASS))
        assertEquals(Shop.FRAME_BRASS, ShopEffects.equippedCosmetic(prefs))
    }

    @Test
    fun `equipping the frame already on takes it back off`() {
        val prefs = FakePrefs()
        ShopEffects.grantCosmetic(prefs, Shop.FRAME_BRASS)

        ShopEffects.toggleEquipped(prefs, Shop.FRAME_BRASS)
        assertNull(ShopEffects.equippedCosmetic(prefs))

        // Still owned, so it can go back on without being bought again.
        assertTrue(ShopEffects.ownsCosmetic(prefs, Shop.FRAME_BRASS))
        ShopEffects.toggleEquipped(prefs, Shop.FRAME_BRASS)
        assertEquals(Shop.FRAME_BRASS, ShopEffects.equippedCosmetic(prefs))
    }

    @Test
    fun `equipping the other frame swaps rather than stacking`() {
        val prefs = FakePrefs()
        ShopEffects.grantCosmetic(prefs, Shop.FRAME_BRASS)
        ShopEffects.grantCosmetic(prefs, Shop.FRAME_EMBER)

        assertEquals(Shop.FRAME_EMBER, ShopEffects.equippedCosmetic(prefs))
        assertTrue(ShopEffects.ownsCosmetic(prefs, Shop.FRAME_BRASS))
    }
}
