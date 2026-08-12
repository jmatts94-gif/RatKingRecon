package io.github.jmatts94.ratkingrecon

import androidx.annotation.ColorRes
import androidx.annotation.StringRes

/** How a frame moves, or that it does not. */
enum class FrameStyle {
    /** A border colour and nothing else. Costs nothing to draw. */
    STATIC,

    /** Gears turning slowly at the four corners. */
    GEARS,

    /** Steam drifting up the left and right edges. */
    STEAM
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
    val style: FrameStyle,
    val price: Int,
    /**
     * Whether the Relic Trader may hand this over.
     *
     * False for the animated pair on purpose. The Vial exchange costs three
     * relics, and letting it produce a 500-Scrap frame would make it the cheap
     * way to get one, which is the opposite of what the higher tier is for.
     */
    val tradeable: Boolean
)

/**
 * Every frame, and the only place any of them is described.
 *
 * These used to be spread across four files that each knew part of the answer:
 * the ids in [Shop], the stroke colour in [GalleryActivity], the display name in
 * [RelicTraderActivity] and a hardcoded pair in [RelicTrader]. Adding a frame
 * meant editing all four, and forgetting the last one would have quietly kept
 * new frames out of the Trader with nothing to show for it.
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
        tradeable = true
    )

    val EMBER = CardFrame(
        id = "ember",
        nameRes = R.string.shop_name_frame_ember,
        descRes = R.string.shop_desc_frame,
        strokeColorRes = R.color.terracotta,
        accentColorRes = R.color.terracotta,
        style = FrameStyle.STATIC,
        price = 200,
        tradeable = true
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
        tradeable = false
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
        tradeable = false
    )

    val all: List<CardFrame> = listOf(BRASS, EMBER, CLOCKWORK, BOILER)

    fun byId(id: String?): CardFrame? = id?.let { key -> all.firstOrNull { it.id == key } }

    /** The frames the Relic Trader is allowed to deal in. */
    val tradeable: List<CardFrame> = all.filter { it.tradeable }

    /** Border width for every frame, in dp. Uniform so none looks heavier. */
    const val STROKE_DP = 3
}
