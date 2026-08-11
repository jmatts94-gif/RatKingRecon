package com.example.ratkingrecon

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * One purchasable item.
 *
 * [prefKey] is the only thing a purchase writes. [GameEngine.rollRat] reads
 * these flags when an egg hatches and clears them in the same edit, which is
 * what makes every boost apply to the *next* hatch and never to a rat already
 * sitting in the Ledger.
 */
data class ShopItem(
    val prefKey: String,
    val price: Int,
    @param:StringRes val nameRes: Int,
    @param:StringRes val bodyRes: Int,
    @param:DrawableRes val iconRes: Int
)

/**
 * A titled group of items.
 *
 * A category with no [items] draws [emptyBodyRes] in a placeholder panel
 * instead, so a section can go on screen before there is anything to sell in it.
 */
data class ShopCategory(
    @param:StringRes val titleRes: Int,
    @param:StringRes val subtitleRes: Int,
    val items: List<ShopItem> = emptyList(),
    @param:StringRes val emptyBodyRes: Int = 0
)

/**
 * The catalogue.
 *
 * [ShopActivity] renders whatever is in [categories] without knowing what any
 * particular item does, so adding an item - or a whole category - is an edit to
 * this file and strings.xml, not to the screen or its layouts.
 */
object Shop {

    /**
     * Both boosts are one-shot: bought here, spent by the very next hatch.
     *
     * Gleam-in-a-Bottle keeps the 5 Scrap price it charged on the home screen.
     * Tinkerer's Serum never had a purchase path anywhere in the app, so 25 is a
     * new number - roughly a medium contract, and five times the Gleam because
     * it lifts both stats from 1-5 to 6-10.
     */
    private val hatchingBoosts = ShopCategory(
        titleRes = R.string.shop_cat_hatching,
        subtitleRes = R.string.shop_cat_hatching_sub,
        items = listOf(
            ShopItem(
                prefKey = GameEngine.KEY_MUTAGEN,
                price = 25,
                nameRes = R.string.btn_mutagen,
                bodyRes = R.string.shop_desc_mutagen,
                iconRes = R.drawable.ic_flask
            ),
            ShopItem(
                prefKey = GameEngine.KEY_POLISH,
                price = 5,
                nameRes = R.string.btn_shiny_polish,
                bodyRes = R.string.shop_desc_polish,
                iconRes = R.drawable.ic_sparkle
            )
        )
    )

    /** Deliberately empty. Repairs and the rest land here as they are built. */
    private val comingSoon = ShopCategory(
        titleRes = R.string.shop_cat_soon,
        subtitleRes = R.string.shop_cat_soon_sub,
        emptyBodyRes = R.string.shop_cat_soon_empty
    )

    val categories = listOf(hatchingBoosts, comingSoon)
}
