package io.github.jmatts94.ratkingrecon

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import kotlin.math.roundToInt

/**
 * What buying an item actually does.
 *
 * The Shop screen switches on this rather than on the item, so each kind of
 * purchase has exactly one implementation no matter how many items use it.
 */
sealed interface ShopEffect {

    /** Sets a flag that the next relevant event reads and clears. */
    data class Flag(val key: String) : ShopEffect

    /**
     * Adds one to a stack the player spends later.
     *
     * [cap] is null for the two charges that have always stacked without limit
     * (Revive Tokens, the Masterwork voucher) and a real number for the four
     * combat items, which the Shop refuses to sell past.
     */
    data class Charge(val key: String, val cap: Int? = null) : ShopEffect

    /** Happens on purchase. [apply] returns false when it could not, and nothing is charged. */
    data class Action(val id: String) : ShopEffect

    /** Bought once, then equipped or unequipped. Purely visual. */
    data class Cosmetic(val id: String) : ShopEffect

    /**
     * Opens a screen of its own instead of buying anything.
     *
     * Carries no price: the Relic Trader deals in relics, and the Shop's whole
     * purchase path - the affordability check, the button label - is denominated
     * in Scrap. A row that opens a door is a better fit than four rows the Shop
     * would have to learn a second currency for.
     */
    data class Screen(val id: String) : ShopEffect

    /** On the shelf but not for sale, so it carries no price. */
    data object ComingSoon : ShopEffect
}

/**
 * One purchasable row.
 *
 * [unlockLevel] of zero means always on sale. [tooltipBodyRes] adds the
 * long-press explainer to a row whose full terms do not fit under its name.
 */
data class ShopItem(
    val price: Int,
    val effect: ShopEffect,
    @param:StringRes val nameRes: Int,
    @param:StringRes val bodyRes: Int,
    @param:DrawableRes val iconRes: Int,
    val unlockLevel: Int = 0,
    @param:StringRes val tooltipTitleRes: Int = 0,
    @param:StringRes val tooltipBodyRes: Int = 0,
    /**
     * Filled into [bodyRes] where it carries placeholders, and empty for the
     * rows whose description is plain text.
     *
     * It exists so a row can quote the number the item actually uses instead of
     * one typed into the string beside it. The three that do - the two combat
     * multipliers and the Mutagen's stat range - are read from the constants
     * the game rolls against, so retuning one cannot leave the shelf
     * advertising the old terms.
     */
    val bodyArgs: List<Any> = emptyList()
)

/**
 * A titled group of items.
 *
 * An empty [items] draws [emptyBodyRes] in a placeholder panel, or - when that
 * is left at zero - just the heading, for a section that is deliberately bare.
 *
 * [cardBackgroundRes], [accentFillRes] and [accentTextRes] default to the warm
 * treatment every section but Combat uses. Combat overrides all three to the
 * cool teal palette, so its cards and price-pills read as one visual language
 * distinct from the rest of the shelf - see [ShopActivity]'s "combat" colours.
 */
data class ShopCategory(
    @param:StringRes val titleRes: Int,
    @param:StringRes val subtitleRes: Int,
    val items: List<ShopItem> = emptyList(),
    @param:StringRes val emptyBodyRes: Int = 0,
    @param:DrawableRes val cardBackgroundRes: Int = R.drawable.bg_card_white,
    @param:ColorRes val accentFillRes: Int = R.color.amber,
    @param:ColorRes val accentTextRes: Int = R.color.text_primary
)

/**
 * The catalogue.
 *
 * [ShopActivity] renders whatever is here without knowing what any item does, so
 * adding one - or a whole section - is an edit to this file and strings.xml.
 */
object Shop {

    /**
     * The two frames that shipped, kept as named constants because the rest of
     * the app refers to them by name. Every frame, including these, is described
     * in [Frames].
     */
    val FRAME_BRASS = Frames.BRASS.id
    val FRAME_EMBER = Frames.EMBER.id

    /** Ids for the immediate-effect items, dispatched in ShopActivity. */
    const val ACTION_QUICK_RETURN = "quick_return"
    const val ACTION_MASTERWORK = "masterwork_hatch"

    /** Screens reachable from a shop row. */
    const val SCREEN_RELIC_TRADER = "relic_trader"

    private val hatching = ShopCategory(
        titleRes = R.string.shop_cat_hatching,
        subtitleRes = R.string.shop_cat_hatching_sub,
        items = listOf(
            ShopItem(
                price = 50,
                effect = ShopEffect.Flag(GameEngine.KEY_MUTAGEN),
                nameRes = R.string.btn_mutagen,
                bodyRes = R.string.shop_desc_mutagen,
                iconRes = R.drawable.ic_flask,
                bodyArgs = listOf(GameEngine.MUTAGEN_STAT.first, GameEngine.MUTAGEN_STAT.last)
            ),
            ShopItem(
                price = 150,
                effect = ShopEffect.Flag(GameEngine.KEY_POLISH),
                nameRes = R.string.btn_shiny_polish,
                bodyRes = R.string.shop_desc_polish,
                iconRes = R.drawable.ic_sparkle
            ),
            ShopItem(
                price = Masterwork.PRICE,
                effect = ShopEffect.Action(ACTION_MASTERWORK),
                nameRes = R.string.shop_name_hatchery,
                bodyRes = R.string.shop_desc_hatchery,
                iconRes = R.drawable.ic_egg,
                unlockLevel = Masterwork.UNLOCK_LEVEL,
                tooltipTitleRes = R.string.tooltip_hatchery_title,
                tooltipBodyRes = R.string.tooltip_hatchery_body
            )
        )
    )

    private val combat = ShopCategory(
        titleRes = R.string.shop_cat_combat,
        subtitleRes = R.string.shop_cat_combat_sub,
        cardBackgroundRes = R.drawable.bg_card_teal,
        accentFillRes = R.color.teal_fill,
        accentTextRes = R.color.teal_bg,
        items = listOf(
            ShopItem(
                price = 100,
                effect = ShopEffect.Charge(ShopEffects.KEY_REVIVE_TOKENS),
                nameRes = R.string.shop_name_revive,
                bodyRes = R.string.shop_desc_revive,
                iconRes = R.drawable.ic_pets
            ),
            ShopItem(
                price = 75,
                effect = ShopEffect.Flag(ShopEffects.KEY_POWER_SURGE),
                nameRes = R.string.shop_name_surge,
                bodyRes = R.string.shop_desc_surge,
                iconRes = R.drawable.ic_power,
                bodyArgs = listOf(ShopEffects.percentBonus(ShopEffects.SURGE_MULTIPLIER))
            ),
            ShopItem(
                price = 200,
                effect = ShopEffect.Flag(ShopEffects.KEY_GOLDEN_WRENCH),
                nameRes = R.string.shop_name_wrench,
                bodyRes = R.string.shop_desc_wrench,
                iconRes = R.drawable.ic_sparkle,
                tooltipTitleRes = R.string.tooltip_wrench_title,
                tooltipBodyRes = R.string.tooltip_wrench_body,
                bodyArgs = listOf(ShopEffects.percentBonus(ShopEffects.WRENCH_MULTIPLIER))
            ),

            // The four combat items - held as counts up to ITEM_CHARGE_CAP and
            // spent mid-fight from the Battle screen's Items panel, unlike the
            // three rows above which arm ahead of a fight. See Battle.applyItem
            // for what each one actually does.
            ShopItem(
                price = 120,
                effect = ShopEffect.Charge(ShopEffects.KEY_HP_TONIC, cap = ShopEffects.ITEM_CHARGE_CAP),
                nameRes = R.string.shop_name_hp_tonic,
                bodyRes = R.string.shop_desc_hp_tonic,
                iconRes = R.drawable.ic_flask,
                bodyArgs = listOf(
                    (Battle.HP_TONIC_FRACTION * 100).roundToInt(),
                    (Battle.TONIC_REGEN_FRACTION * 100).roundToInt()
                )
            ),
            ShopItem(
                price = 80,
                effect = ShopEffect.Charge(ShopEffects.KEY_REINFORCED_PLATING, cap = ShopEffects.ITEM_CHARGE_CAP),
                nameRes = R.string.shop_name_reinforced_plating,
                bodyRes = R.string.shop_desc_reinforced_plating,
                iconRes = R.drawable.ic_toughness
            ),
            ShopItem(
                price = 60,
                effect = ShopEffect.Charge(ShopEffects.KEY_CORROSIVE_CHARGE, cap = ShopEffects.ITEM_CHARGE_CAP),
                nameRes = R.string.shop_name_corrosive_charge,
                bodyRes = R.string.shop_desc_corrosive_charge,
                iconRes = R.drawable.ic_settings,
                bodyArgs = listOf(
                    (Battle.CORROSIVE_DOT_FRACTION * 100).roundToInt(),
                    Battle.CORROSIVE_DOT_ROUNDS,
                    (Battle.CORROSIVE_ATTACK_DOT_FRACTION * 100).roundToInt()
                )
            ),
            ShopItem(
                price = 40,
                effect = ShopEffect.Charge(ShopEffects.KEY_CLEANSE, cap = ShopEffects.ITEM_CHARGE_CAP),
                nameRes = R.string.shop_name_cleanse,
                bodyRes = R.string.shop_desc_cleanse,
                iconRes = R.drawable.ic_sparkle
            )
        )
    )

    private val expeditions = ShopCategory(
        titleRes = R.string.shop_cat_expeditions,
        subtitleRes = R.string.shop_cat_expeditions_sub,
        items = listOf(
            ShopItem(
                price = 100,
                effect = ShopEffect.Action(ACTION_QUICK_RETURN),
                nameRes = R.string.shop_name_quick_return,
                bodyRes = R.string.shop_desc_quick_return,
                iconRes = R.drawable.ic_expedition
            )
        )
    )

    /**
     * Every frame in [Frames], in catalogue order - [Frames.all] rather than
     * [Frames.sellable], because a frame that cannot be bought still belongs
     * on the shelf to be seen and wanted, not hidden from it. What
     * [CardFrame.sellable] actually gates is the price button itself - see
     * [ShopActivity.labelForUnlocked], which swaps it for "Arena Reward Only"
     * on a frame this is false for - so nothing here goes on sale merely for
     * existing in the catalogue.
     */
    private val cosmetic = ShopCategory(
        titleRes = R.string.shop_cat_cosmetic,
        subtitleRes = R.string.shop_cat_cosmetic_sub,
        items = Frames.all.map { frame ->
            ShopItem(
                price = frame.price,
                effect = ShopEffect.Cosmetic(frame.id),
                nameRes = frame.nameRes,
                bodyRes = frame.descRes,
                iconRes = when (frame.style) {
                    FrameStyle.GEARS -> R.drawable.ic_hexagon
                    FrameStyle.STEAM -> R.drawable.ic_flask
                    FrameStyle.PULSE -> R.drawable.ic_sparkle
                    FrameStyle.STATIC -> R.drawable.ic_star
                    FrameStyle.SCARRED -> R.drawable.ic_power
                }
            )
        }
    )

    private val featured = ShopCategory(
        titleRes = R.string.shop_cat_featured,
        subtitleRes = R.string.shop_cat_featured_sub,
        items = listOf(
            ShopItem(
                // Priced in relics, not Scrap, so this row carries no Scrap
                // price at all - see [ShopEffect.Screen].
                price = 0,
                effect = ShopEffect.Screen(SCREEN_RELIC_TRADER),
                nameRes = R.string.shop_name_relic_trader,
                bodyRes = R.string.shop_desc_relic_trader,
                iconRes = R.drawable.ic_flask,
                tooltipTitleRes = R.string.tooltip_trader_title,
                tooltipBodyRes = R.string.tooltip_trader_body
            )
        )
    )

    val categories = listOf(hatching, combat, expeditions, cosmetic, featured)
}
