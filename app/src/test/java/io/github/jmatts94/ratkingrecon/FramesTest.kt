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
 * Frames used to be described in three places at once - the ids in the Shop,
 * the colour in the Binder, the display name in ShopActivity - so these check
 * that the single catalogue really is driving all of them, and that pricing
 * lines up the way the tiers intend.
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

    /**
     * The tiers are drawn by price, not by whether a frame moves.
     *
     * Ember animates and still sits in the base tier: it pulses between two
     * colours because it was otherwise indistinguishable from Brass, which is a
     * fix for a frame that already existed rather than a new premium feature.
     * Repricing something players own to keep "animated means premium" tidy
     * would have been the worse trade.
     */
    @Test
    fun `base tier frames stay in the original price band`() {
        val base = Frames.all.filter { it.baseTier }

        assertTrue("expected the two original frames", base.size == 2)
        for (frame in base) {
            assertTrue("${frame.id} priced ${frame.price}", frame.price in 150..200)
        }
    }

    /**
     * No longer one tight band: Riveted Copper sits below Clockwork and
     * Boiler as a mid tier, Aether Coil above them as the new top. What still
     * has to hold is that every premium *Shop* frame is priced like the
     * premium item it is, not like something that slipped back into base
     * tier range. Scoped to [CardFrame.sellable] on purpose - Arena Champion
     * is premium too, but it is not part of this Shop-priced tier at all,
     * and its own price sits above this band deliberately (see the next
     * test).
     */
    @Test
    fun `premium Shop frames are priced above the base tier, across a widening range`() {
        val premium = Frames.all.filterNot { it.baseTier }.filter { it.sellable }

        assertTrue("expected four premium Shop frames", premium.size == 4)
        for (frame in premium) {
            assertTrue("${frame.id} priced ${frame.price}", frame.price in 250..700)
        }
    }

    /**
     * Not sold, not base tier, and priced above even Aether Coil - the
     * flagship a run this long is meant to feel like it earned, not a
     * seventh Shop entry with the "for sale" sign taken down.
     */
    @Test
    fun `the Arena-exclusive frame is neither base tier nor sellable`() {
        assertFalse(Frames.ARENA_CHAMPION.baseTier)
        assertFalse(Frames.ARENA_CHAMPION.sellable)
        assertTrue(
            "expected Arena Champion priced above every Shop frame",
            Frames.ARENA_CHAMPION.price > Frames.all.filter { it.sellable }.maxOf { it.price }
        )
    }

    @Test
    fun `every premium frame costs more than every base one`() {
        val dearestBase = Frames.all.filter { it.baseTier }.maxOf { it.price }
        val cheapestPremium = Frames.all.filterNot { it.baseTier }.minOf { it.price }

        assertTrue(
            "a premium frame must never be the cheaper option",
            cheapestPremium > dearestBase
        )
    }

    /**
     * Six frames, no longer six different looks - Riveted Copper reuses
     * Brass's stillness and Aether Coil reuses Ember's pulse on purpose,
     * the same way Ember once reused Brass's plain-border shape and was
     * told apart from it by motion instead of by colour. What still has to
     * hold, either way: two frames sharing a style must never also share
     * every colour, or they would be the same frame twice.
     */
    @Test
    fun `frames sharing a style are still told apart by colour`() {
        val styles = Frames.all.map { it.style }
        assertTrue(FrameStyle.GEARS in styles)
        assertTrue(FrameStyle.STEAM in styles)
        assertTrue(FrameStyle.PULSE in styles)
        assertTrue(FrameStyle.STATIC in styles)

        for ((style, frames) in Frames.all.groupBy { it.style }) {
            val colours = frames.map { Triple(it.strokeColorRes, it.accentColorRes, it.accentAltColorRes) }
            assertEquals(
                "two $style frames share every colour",
                frames.size,
                colours.distinct().size
            )
        }
    }

    /**
     * A two-colour style needs two colours; a one-colour style must not break.
     * SCARRED joins PULSE here - its cracks blend between two colours the same
     * way a pulse does, just slower and never fully dark. LIGHTNING needs its
     * glow and its flash to read as different heat; RADIANT's own third stop
     * lives off-CardFrame (see FrameOverlayDrawable.radiantColors), but its
     * first two still have to differ the same way PULSE's do.
     */
    @Test
    fun `only a two-colour style carries a second accent`() {
        val twoColour = setOf(FrameStyle.PULSE, FrameStyle.SCARRED, FrameStyle.LIGHTNING, FrameStyle.RADIANT)
        for (frame in Frames.all) {
            if (frame.style in twoColour) {
                assertTrue(
                    "${frame.id} blends between one colour and itself",
                    frame.accentColorRes != frame.accentAltColorRes
                )
            } else {
                assertEquals(
                    "${frame.id} should have no second accent",
                    frame.accentColorRes,
                    frame.accentAltColorRes
                )
            }
        }
    }

    /** Only the one frame sharing GEARS with Clockwork asked for the extra presence. */
    @Test
    fun `glow is opted into per frame, not switched on for a whole style`() {
        assertTrue(Frames.IRON_GRIP.glow)
        assertFalse("Clockwork shares Iron Grip's style but not its glow", Frames.CLOCKWORK.glow)
        for (frame in Frames.all - Frames.IRON_GRIP) {
            assertFalse("${frame.id} should not glow", frame.glow)
        }
    }

    /** The moving parts must stand off the border, which is what made them invisible. */
    @Test
    fun `an animated frame never draws in its own border colour`() {
        for (frame in Frames.all.filterNot { it.style == FrameStyle.STATIC }) {
            assertTrue(
                "${frame.id} draws its animation in its border colour",
                frame.accentColorRes != frame.strokeColorRes
            )
        }
    }

    @Test
    fun `only the two original frames are base tier`() {
        assertEquals(
            listOf(Frames.BRASS.id, Frames.EMBER.id),
            Frames.baseTier.map { it.id }
        )
        for (frame in listOf(Frames.CLOCKWORK, Frames.BOILER, Frames.RIVETED_COPPER, Frames.AETHER_COIL)) {
            assertFalse("${frame.id} must not be base tier", frame.baseTier)
        }
    }

    // ---- the Shop shelf ------------------------------------------------------

    /**
     * Every frame is on the shelf to be seen, sellable or not - see
     * Shop.cosmetic's own doc comment. What [CardFrame.sellable] gates is
     * the price button, not the listing.
     */
    @Test
    fun `every frame is on the shelf at the price it declares`() {
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

    /** Listed, but never a buyable row - ShopActivity swaps its price for "Arena Reward Only". */
    @Test
    fun `the Arena-exclusive frame is on the shelf but not sellable`() {
        val cosmeticIds = Shop.categories
            .flatMap { it.items }
            .mapNotNull { (it.effect as? ShopEffect.Cosmetic)?.id }

        assertTrue(Frames.ARENA_CHAMPION.id in cosmeticIds)
        assertFalse(Frames.ARENA_CHAMPION.sellable)
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
