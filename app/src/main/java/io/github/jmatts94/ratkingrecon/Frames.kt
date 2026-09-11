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
    SCARRED,

    /**
     * A jagged bolt cutting across the card face, top-right to bottom-left -
     * the one style that crosses the card rather than tracing its border.
     * Dark and still most of the time, then strikes twice in quick
     * succession before going quiet again - see
     * [FrameOverlayDrawable.drawLightning].
     */
    LIGHTNING,

    /**
     * A border cycling through a full palette rather than breathing between
     * two - see [FrameOverlayDrawable.drawRadiant]. Reserved for the one
     * frame in the game meant to read as a tier above every other, [PULSE]
     * included: never dims to a whisper the way [PULSE] does at the bottom
     * of its own breath, always a solid, shifting glow.
     */
    RADIANT,

    /**
     * A trail of paw prints padding round the border - see
     * [FrameOverlayDrawable.drawPaws]. Unlike every style above, which
     * either traces the border exactly ([GEARS]/[PULSE]/[SCARRED]/[RADIANT])
     * or crosses it once ([LIGHTNING]), this one wanders side to side across
     * the line as it travels rather than tracking it, and fades out behind
     * itself rather than staying lit - a courier's own footprints, not a
     * machine part running a fixed track.
     */
    PAWS
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
    val sellable: Boolean = true,

    /**
     * Whether [ArenaRun.weightedClearedFrame] may hand this out as a repeat
     * Arena-clear reward.
     *
     * True for every frame that shipped before this flag existed, [ARENA_CHAMPION]
     * included - that pool has always drawn from every frame not yet owned,
     * sellable or not, and still should for the frames that were already in it.
     * False only for a frame earned a specific other way (see [AchievementRewards]) -
     * without this, [IRON_GRIP]/[NATURALISTS_COMPENDIUM]/[RAT_KINGS_CROWN] would be
     * winnable from a lucky Arena clear with the real achievement behind them
     * never met at all.
     */
    val arenaPool: Boolean = true,

    /**
     * Whether [FrameOverlayDrawable] draws a soft pulsing halo behind
     * whatever [style] already draws - see [FrameOverlayDrawable.drawGlow].
     * False for every frame that shipped before this existed, [IRON_GRIP]
     * included by default until it opts in - a style shared with another
     * frame (here, [CLOCKWORK]) must not change everywhere it is used just
     * because one of the two frames wearing it asked for more presence.
     */
    val glow: Boolean = false
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

    /**
     * Earned by raising one rat to 25 Power and 25 Toughness - see
     * [AchievementRewards]. Pewter and boiler_glow, the same pair [BOILER]
     * uses, but geared rather than steaming: this is a frame about a rat's
     * own raw stats, not the Hatchery's output, so it borrows Clockwork's
     * turning gears instead of Boiler's drifting steam.
     *
     * The one [FrameStyle.GEARS] frame with [glow] on - [CLOCKWORK] shares
     * the same turning track, so the two need to be told apart by more than
     * colour alone, and a rat that cleared this bar earned the extra
     * presence a plain gear track does not have room to show on its own.
     */
    val IRON_GRIP = CardFrame(
        id = "iron_grip",
        nameRes = R.string.shop_name_frame_iron_grip,
        descRes = R.string.shop_desc_frame_iron_grip,
        strokeColorRes = R.color.pewter,
        accentColorRes = R.color.boiler_glow,
        style = FrameStyle.GEARS,
        price = 400,
        baseTier = false,
        sellable = false,
        arenaPool = false,
        glow = true
    )

    /**
     * Earned by finding every species in the roster - see [AchievementRewards].
     * shiny_gold rather than any colour already spoken for by another frame:
     * this badge is about the collection being complete, the same idea
     * shiny_gold already marks everywhere else in the app.
     *
     * The only [FrameStyle.LIGHTNING] frame: a gold-lit border most of the
     * time, struck twice in quick succession by a bolt in [R.color.cream] -
     * the brightest tone this app has, so the strike itself reads as
     * genuinely hot against the gold glow it leaves behind rather than as
     * more gold on gold. [strokeColorRes] is [R.color.amber_dark] rather
     * than shiny_gold itself, so the static border and the glow the strike
     * leaves behind read as two different things.
     */
    val NATURALISTS_COMPENDIUM = CardFrame(
        id = "naturalists_compendium",
        nameRes = R.string.shop_name_frame_compendium,
        descRes = R.string.shop_desc_frame_compendium,
        strokeColorRes = R.color.amber_dark,
        accentColorRes = R.color.shiny_gold,
        accentAltColorRes = R.color.cream,
        style = FrameStyle.LIGHTNING,
        price = 500,
        baseTier = false,
        sellable = false,
        arenaPool = false
    )

    /**
     * Earned by walking 100,000 lifetime steps - see [Achievements.steps]'s
     * "steps_1m" row, whose id is untouched even though its own target and
     * this frame's name/look both changed under it (see that row's own
     * comment on why an id never moves once shipped, and LedgerTasks' M1/M2/
     * M3 for the same rule already followed once before). Display name is
     * Maunderer's Catch - renamed once already from Master Courier, which
     * is why the id and the colour resource names below both still read
     * "rat_kings_crown"/"master_courier_*"; none of the three are shown to
     * a player, so none of them chased the name a second time.
     *
     * The one [FrameStyle.PAWS] frame: a true black border rather than this
     * app's usual warm metals, and neon paw prints - both asked for by name
     * rather than picked to match the rest of the palette. Was the one
     * [FrameStyle.RADIANT] frame before this, cycling three golds to read as
     * a tier above every other frame; a courier's own footprints replace
     * that idea rather than keeping it - see colors.xml's own comment on
     * master_courier_black/master_courier_paw_green for why both colours
     * step outside the palette on purpose. No accentAltColorRes here - PAWS
     * does not blend between two colours the way PULSE does, so this stays
     * default (equal to accentColorRes) like every other single-colour
     * style; the outline [FrameOverlayDrawable.pawOutlinePaint] draws
     * behind every mark is a fixed black of its own rather than a second
     * frame colour, see that field's own comment.
     */
    val RAT_KINGS_CROWN = CardFrame(
        id = "rat_kings_crown",
        nameRes = R.string.shop_name_frame_rat_king,
        descRes = R.string.shop_desc_frame_rat_king,
        strokeColorRes = R.color.master_courier_black,
        accentColorRes = R.color.master_courier_paw_green,
        style = FrameStyle.PAWS,
        price = 800,
        baseTier = false,
        sellable = false,
        arenaPool = false
    )

    /**
     * Earned by completing 25 splices at the Fusion Pot - see [AchievementRewards].
     * Teal rather than any of the warm metals every other frame draws from: this
     * badge is about the Fusion Pot's own chemistry, not the Hatchery or a rat's
     * raw stats, so it borrows Combat's cool palette instead - see [Shop.combat].
     * Breathes the same way [EMBER] and [AETHER_COIL] do, between [R.color.teal_fill]
     * and [R.color.teal_light], with [R.color.teal_border] as the stroke so the
     * two never draw in the same colour. Not [R.color.teal_bg]: that pale end
     * measured close enough to cream/card_white that the frame nearly vanished
     * into whatever card it sat on for half of every breath - the same
     * not-enough-presence problem [IRON_GRIP] had on its own style, which is
     * why this gets the same glow fix on top of the colour one.
     */
    val CHIMERAS_WEAVE = CardFrame(
        id = "chimeras_weave",
        nameRes = R.string.shop_name_frame_chimera,
        descRes = R.string.shop_desc_frame_chimera,
        strokeColorRes = R.color.teal_border,
        accentColorRes = R.color.teal_fill,
        accentAltColorRes = R.color.teal_light,
        style = FrameStyle.PULSE,
        price = 450,
        baseTier = false,
        sellable = false,
        glow = true,
        arenaPool = false
    )

    /**
     * Earned by beating the Rustbringer - see [AchievementRewards]. The one
     * [FrameStyle.RADIANT] frame: that style has sat reserved and unused
     * since [RAT_KINGS_CROWN] moved off it onto [FrameStyle.PAWS] (see that
     * frame's own comment), described from the day it was written as "the
     * one frame in the game meant to read as a tier above every other" -
     * which is exactly what the open-ended final boss's own badge needed and
     * none of the other four combat badges do, being the one rung on that
     * ladder worth coming back to for the rest of the game.
     *
     * Cycles dark scarred iron into a pale aether glow into RADIANT's own
     * fixed boiler_glow third stop - the Rustbringer's own shell going dark,
     * then arcing, then flaring, rather than any single warm metal a lesser
     * badge already wears. copper stands still as the resting border so the
     * cycle reads as something happening to a real seal, not as the whole
     * frame.
     */
    val RUSTBRINGERS_SEAL = CardFrame(
        id = "rustbringers_seal",
        nameRes = R.string.shop_name_frame_rustbringer,
        descRes = R.string.shop_desc_frame_rustbringer,
        strokeColorRes = R.color.copper,
        accentColorRes = R.color.scarred_iron,
        accentAltColorRes = R.color.aether_glow,
        style = FrameStyle.RADIANT,
        price = 900,
        baseTier = false,
        sellable = false,
        arenaPool = false
    )

    val all: List<CardFrame> = listOf(
        BRASS, EMBER, RIVETED_COPPER, CLOCKWORK, BOILER, AETHER_COIL,
        ARENA_CHAMPION, IRON_GRIP, NATURALISTS_COMPENDIUM, RAT_KINGS_CROWN, CHIMERAS_WEAVE,
        RUSTBRINGERS_SEAL
    )

    fun byId(id: String?): CardFrame? = id?.let { key -> all.firstOrNull { it.id == key } }

    /** The two original frames, in their original 150-200 price band. */
    val baseTier: List<CardFrame> = all.filter { it.baseTier }

    /** The frames the Shop lists for sale. */
    val sellable: List<CardFrame> = all.filter { it.sellable }

    /** Border width for every frame, in dp. Uniform so none looks heavier. */
    const val STROKE_DP = 3
}
