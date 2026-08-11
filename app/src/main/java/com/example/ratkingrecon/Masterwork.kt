package com.example.ratkingrecon

/**
 * The Masterwork Hatchery: a bought hatch, rather than a walked one.
 *
 * Deliberately separate from [GameEngine.rollRat]. The ordinary hatch is driven
 * by steps and shaped by whatever consumables are armed; this one is bought
 * outright and guarantees its result, so sharing a code path would mean one of
 * them constantly checking which it was. Nothing here touches the step loop.
 */
object Masterwork {

    const val PRICE = 800

    /** Kept at the level the hatchery has always been gated behind. */
    const val UNLOCK_LEVEL = 5

    /** Top bracket of the ordinary 1..5 stat roll. */
    val TOP_BRACKET = 4..5

    /**
     * The tier the Masterwork never produces.
     *
     * [Roster] tags rarity as free text and is inconsistent about case - five
     * species are tagged "common" rather than "Common" - so this is matched
     * without case. A case-sensitive check would quietly leak those five into
     * the premium pool, which is exactly what excluding the bottom tier is
     * meant to prevent.
     */
    private const val EXCLUDED_TIER = "common"

    /**
     * Everything above the bottom tier: the Rare and Legendary species.
     *
     * Derived from [Roster] rather than listed here, so a species added to the
     * roster joins the premium pool - or stays out of it - purely on its own
     * rarity tag.
     */
    val pool: List<Rat> =
        Roster.all.filterNot { it.rarity.equals(EXCLUDED_TIER, ignoreCase = true) }

    /**
     * Mints a rat, with every guarantee the Masterwork sells.
     *
     * Pure: it picks and rolls but stores nothing, so the caller owns the write
     * and this stays testable off-device.
     */
    fun roll(): RatEntity {
        val species = pool.random()
        return RatEntity(
            artKey = species.artKey,
            name = species.name,
            power = TOP_BRACKET.random(),
            toughness = TOP_BRACKET.random(),
            // The point of the item. No roll, unlike the ordinary 1-in-10.
            shiny = true
        )
    }
}
