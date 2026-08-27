package io.github.jmatts94.ratkingrecon

import androidx.annotation.ColorRes
import androidx.annotation.StringRes

/** How a frame moves, or that it does not. */
enum class FrameStyle {
    /** A border colour and nothing else. Costs nothing to draw. */
    STATIC,

    /** A toothed gear track running the whole perimeter. */
    GEARS,

    /** Steam drifting up the left and right edges. */
    STEAM,

    /** A border glow breathing between two colours. */
    PULSE,

    /**
     * A dark, battle-worn border: glowing cracks breathing through it in the
     * frame's own warm accent, plus a second wave of gear-tooth and
     * sword-nick marks breathing in aether blue - see
     * [FrameOverlayDrawable.secondaryAccent].
     */
    SCARRED
}

/**
 * One Binder card frame.
 *
 * [id] reaches the save - as FRAME_OWNED_<id> and as the equipped frame - so it
 * must never change. "brass" and "ember" are the two that shipped.
 */
data class CardFrame(
    val id: String,
    @param:StringRes val nameRes: Int,
    @param:StringRes val descRes: Int,
    @param:ColorRes val strokeColorRes: Int,
    /**
     * What the moving parts are drawn in.
     *
     * Separate from the border on purpose. Drawing the animation in the border's
     * own colour is what made the cogs read as texture on the edge they sat
     * against, and left the steam almost invisible against white.
     */
    @param:ColorRes val accentColorRes: Int,
    /**
     * The far end of a two-colour animation, where there is one.
     *
     * Only [FrameStyle.PULSE] uses it; every other style leaves it equal to
     * [accentColorRes], which makes a pulse between them a no-op rather than
     * something that has to be guarded against.
     */
    @param:ColorRes val accentAltColorRes: Int = accentColorRes,
    val style: FrameStyle,
    val price: Int,
    /**
     * Whether this frame belongs to the original 150-200 price band Brass and
     * Ember shipped in, rather than the premium tier above it.
     *
     * The Relic Trader used to deal in frames along exactly this line - the
     * base pair cheap enough that three relics undercut nothing, the premium
     * tier held back so relics couldn't buy around its own Scrap price - but
     * it no longer deals in frames at all. This is now purely the price-tier
     * marker the pricing tests in FramesTest hold the catalogue to.
     */
    val baseTier: Boolean,
    /**
     * Whether the Shop lists this frame at all.
     *
     * True for every frame that shipped before this flag existed - Shop.kt
     * builds its cosmetic category straight from [Frames.all], so a frame
     * left off this would silently go on sale otherwise. False is for a
     * frame meant to be earned, not bought: [price] still describes what it
     * would be worth, since [ArenaRun]'s weighted pick still reads it, but
     * nothing ever charges it.
     */
    val sellable: Boolean = true
)

/**
 * Every frame, and the only place any of them is described.
 *
 * These used to be spread across three files that each knew part of the
 * answer: the ids in [Shop], the stroke colour in [GalleryActivity], the
 * display name in [ShopActivity]. Adding a frame meant editing all three.
 */
object Frames {

    val BRASS = CardFrame(
        id = "brass",
        nameRes = R.string.shop_name_frame_brass,
        descRes = R.string.shop_desc_frame,
        strokeColorRes = R.color.amber_dark,
        accentColorRes = R.color.amber_dark,
        style = FrameStyle.STATIC,
        price = 200,
        baseTier = true
    )

    /**
     * The one base-tier frame that moves.
     *
     * Brass and Ember were both a single warm border and read as nearly the
     * same frame, so this one breathes instead.
     */
    val EMBER = CardFrame(
        id = "ember",
        nameRes = R.string.shop_name_frame_ember,
        descRes = R.string.shop_desc_frame_ember,
        strokeColorRes = R.color.terracotta,
        accentColorRes = R.color.ember_hot,
        accentAltColorRes = R.color.ember_deep,
        style = FrameStyle.PULSE,
        price = 200,
        baseTier = true
    )

    val CLOCKWORK = CardFrame(
        id = "clockwork",
        nameRes = R.string.shop_name_frame_clockwork,
        descRes = R.string.shop_desc_frame_clockwork,
        strokeColorRes = R.color.copper,
        // Bright brass against the copper border, so the machinery reads as
        // machinery rather than as more of the edge it sits on.
        accentColorRes = R.color.brass_bright,
        style = FrameStyle.GEARS,
        price = 350,
        baseTier = false
    )

    val BOILER = CardFrame(
        id = "boiler",
        nameRes = R.string.shop_name_frame_boiler,
        descRes = R.string.shop_desc_frame_boiler,
        strokeColorRes = R.color.pewter,
        // Warm, because it is a boiler. Pewter steam on a white card was very
        // nearly invisible.
        accentColorRes = R.color.boiler_glow,
        style = FrameStyle.STEAM,
        price = 500,
        baseTier = false
    )

    /**
     * The gap between the base pair and the animated premium two: a colour
     * that does not move, priced above 200 but below the machinery.
     *
     * Copper rather than a colour of its own on purpose. What sets this apart
     * from Clockwork's own copper border is the rivets and the stillness, not
     * the metal - the same way Brass and Ember share a family of colour and
     * are told apart by motion instead.
     */
    val RIVETED_COPPER = CardFrame(
        id = "riveted_copper",
        nameRes = R.string.shop_name_frame_riveted,
        descRes = R.string.shop_desc_frame_riveted,
        strokeColorRes = R.color.copper,
        accentColorRes = R.color.copper,
        style = FrameStyle.STATIC,
        price = 275,
        baseTier = false
    )

    /**
     * The top of the premium tier. Breathes the same way Ember does - between
     * two colours on [FrameStyle.PULSE] rather than a kind of movement of its
     * own - priced above Boiler as the most expensive frame in the shop.
     */
    val AETHER_COIL = CardFrame(
        id = "aether_coil",
        nameRes = R.string.shop_name_frame_aether,
        descRes = R.string.shop_desc_frame_aether,
        strokeColorRes = R.color.aether_border,
        accentColorRes = R.color.aether_glow,
        accentAltColorRes = R.color.aether_deep,
        style = FrameStyle.PULSE,
        price = 650,
        baseTier = false
    )

    /**
     * The one frame nothing sells or trades for - guaranteed on the first
     * time a run clears all fifteen Arena fights, see
     * [ArenaRun.weightedClearedFrame]. The only [FrameStyle.SCARRED] frame:
     * a dark, battle-worn border with cracks breathing between the same two
     * colours the Arena's own screens are built from - [R.color.brass_bright]
     * lights its "Enter" button, [R.color.copper] borders every panel - so it
     * reads as carried out of that mode, not as a seventh entry in the same
     * warm-and-still family the base six share. A second wave of gear-tooth
     * and sword-nick marks breathes in [R.color.aether_glow]/[R.color.aether_deep]
     * on top of that - the same pair [AETHER_COIL] pulses between - so the
     * damage this frame has taken and whatever has marked it since read as
     * two distinct tones rather than more of the same crack.
     */
    val ARENA_CHAMPION = CardFrame(
        id = "arena_champion",
        nameRes = R.string.shop_name_frame_arena_champion,
        descRes = R.string.shop_desc_frame_arena_champion,
        strokeColorRes = R.color.scarred_iron,
        accentColorRes = R.color.brass_bright,
        accentAltColorRes = R.color.copper,
        style = FrameStyle.SCARRED,
        price = 750,
        baseTier = false,
        sellable = false
    )

    val all: List<CardFrame> =
        listOf(BRASS, EMBER, RIVETED_COPPER, CLOCKWORK, BOILER, AETHER_COIL, ARENA_CHAMPION)

    fun byId(id: String?): CardFrame? = id?.let { key -> all.firstOrNull { it.id == key } }

    /** The two original frames, in their original 150-200 price band. */
    val baseTier: List<CardFrame> = all.filter { it.baseTier }

    /** The frames the Shop lists for sale. */
    val sellable: List<CardFrame> = all.filter { it.sellable }

    /** Border width for every frame, in dp. Uniform so none looks heavier. */
    const val STROKE_DP = 3
}
