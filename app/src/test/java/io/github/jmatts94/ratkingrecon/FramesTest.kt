package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame catalogue, and the two things that depend on getting it right.
 *
 * Frames used to be described in four places at once - the ids in the Shop, the
 * colour in the Binder, the name in the Trader and a hardcoded pair in the
 * exchange rules - so these check that the single catalogue really is driving
 * all of them, and that pricing and tradeability line up the way the tiers
 * intend.
 */
class FramesTest {

    @Test
    fun `the two frames that shipped keep their save ids`() {
        assertEquals("brass", Frames.BRASS.id)
        assertEquals("ember", Frames.EMBER.id)

        // What the save actually holds, via the Shop's names for them.
        assertEquals(Frames.BRASS.id, Shop.FRAME_BRASS)
        assertEquals(Frames.EMBER.id, Shop.FRAME_EMBER)
    }

    @Test
    fun `every frame has a distinct id`() {
        assertEquals(Frames.all.size, Frames.all.map { it.id }.distinct().size)
    }

    @Test
    fun `byId finds every frame and nothing else`() {
        for (frame in Frames.all) {
            assertEquals(frame, Frames.byId(frame.id))
        }
        assertNull(Frames.byId("no_such_frame"))
        assertNull(Frames.byId(null))
    }

    // ---- pricing tiers -------------------------------------------------------

    @Test
    fun `plain frames stay in the original price band`() {
        val plain = Frames.all.filter { it.style == FrameStyle.STATIC }

        assertTrue("expected the two original frames", plain.size == 2)
        for (frame in plain) {
            assertTrue("${frame.id} priced ${frame.price}", frame.price in 150..200)
        }
    }

    @Test
    fun `animated frames are priced into the premium band`() {
        val animated = Frames.all.filterNot { it.style == FrameStyle.STATIC }

        assertTrue("expected two animated frames", animated.size == 2)
        for (frame in animated) {
            assertTrue("${frame.id} priced ${frame.price}", frame.price in 350..500)
        }
    }

    @Test
    fun `every animated frame costs more than every plain one`() {
        val dearestPlain = Frames.all.filter { it.style == FrameStyle.STATIC }.maxOf { it.price }
        val cheapestAnimated =
            Frames.all.filterNot { it.style == FrameStyle.STATIC }.minOf { it.price }

        assertTrue(
            "an animated frame must never be the cheaper option",
            cheapestAnimated > dearestPlain
        )
    }

    @Test
    fun `each animated frame has a style of its own`() {
        val styles = Frames.all.map { it.style }
        assertTrue(FrameStyle.GEARS in styles)
        assertTrue(FrameStyle.STEAM in styles)
        assertEquals(
            "two frames sharing an animation would not be distinguishable",
            Frames.all.filterNot { it.style == FrameStyle.STATIC }.size,
            Frames.all.filterNot { it.style == FrameStyle.STATIC }.map { it.style }.distinct().size
        )
    }

    // ---- what the Trader may deal in ----------------------------------------

    @Test
    fun `only the plain frames are tradeable`() {
        assertEquals(
            listOf(Frames.BRASS.id, Frames.EMBER.id),
            Frames.tradeable.map { it.id }
        )
        for (frame in Frames.all.filterNot { it.style == FrameStyle.STATIC }) {
            assertFalse("${frame.id} must not be tradeable", frame.tradeable)
        }
    }

    /**
     * The point of the rule: three relics is a much cheaper route than 350 to
     * 500 Scrap, so the Trader handing over an animated frame would undercut
     * the tier they are priced into.
     */
    @Test
    fun `the trader never offers an animated frame`() {
        val prefs = FakePrefs()

        val offered = RelicTrader.availableFrames(prefs).mapNotNull { Frames.byId(it) }

        assertTrue(offered.isNotEmpty())
        assertTrue(
            "the Trader offered an animated frame",
            offered.all { it.style == FrameStyle.STATIC }
        )
    }

    @Test
    fun `owning the plain frames empties the trader's pool even with animated ones unowned`() {
        val prefs = FakePrefs()
        ShopEffects.grantCosmetic(prefs, Frames.BRASS.id)
        ShopEffects.grantCosmetic(prefs, Frames.EMBER.id)

        assertTrue(RelicTrader.availableFrames(prefs).isEmpty())
        assertFalse(ShopEffects.ownsCosmetic(prefs, Frames.CLOCKWORK.id))
    }

    // ---- the Shop shelf ------------------------------------------------------

    @Test
    fun `every frame is on sale at the price it declares`() {
        val cosmeticRows = Shop.categories
            .flatMap { it.items }
            .mapNotNull { item ->
                (item.effect as? ShopEffect.Cosmetic)?.let { it.id to item.price }
            }
            .toMap()

        assertEquals(Frames.all.size, cosmeticRows.size)
        for (frame in Frames.all) {
            assertEquals("${frame.id} on the shelf", frame.price, cosmeticRows[frame.id])
        }
    }

    @Test
    fun `an animated frame is still bought and equipped like any other`() {
        val prefs = FakePrefs()

        assertFalse(ShopEffects.ownsCosmetic(prefs, Frames.BOILER.id))

        ShopEffects.grantCosmetic(prefs, Frames.BOILER.id)

        assertTrue(ShopEffects.ownsCosmetic(prefs, Frames.BOILER.id))
        assertEquals(Frames.BOILER.id, ShopEffects.equippedCosmetic(prefs))
        assertNotNull(Frames.byId(ShopEffects.equippedCosmetic(prefs)))
    }
}
