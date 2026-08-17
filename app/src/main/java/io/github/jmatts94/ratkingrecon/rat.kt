package io.github.jmatts94.ratkingrecon

/**
 * A species in the master roster - the template a hatched [RatCard] is rolled from.
 *
 * [artKey] is a stable name from [RatArt], not a resource ID, so the same value
 * can be written straight into a save.
 */
data class Rat(
    val artKey: String,
    val name: String,
    val rarity: String,
    /** Flavor only - nothing in the game reads this to decide anything. */
    val faction: String
)

/**
 * Every species that can hatch.
 *
 * Lives here rather than in an Activity so the step-tracking service can roll a
 * rat while the app is closed.
 */
object Roster {

    /**
     * The three rarity tiers.
     *
     * [Rat.rarity] is free text rather than one of these constants, so every
     * lookup through [withRarity] matches without case - defensive against a
     * future entry being typed as "common" instead of "Common", which is
     * exactly what happened to five species here until it was normalised.
     */
    const val COMMON = "Common"
    const val RARE = "Rare"
    const val LEGENDARY = "Legendary"

    // Flavor groupings, same purpose as the rarity constants above: one
    // spelling, so a typo in a faction tag shows up as a species silently
    // missing from its group rather than as text that merely looks right.
    const val TINKERERS = "Tinkerers"
    const val FOUNDRY_BORN = "Foundry-born"
    const val BRAWLERS = "Brawlers"
    const val SCAVENGERS = "Scavengers"
    const val SMUGGLERS = "Smugglers"

    val all: List<Rat> = listOf(
        Rat("flux_pic", "Flux", "Rare", SMUGGLERS),
        Rat("glowtail_pic", "Glowtail", "Legendary", SCAVENGERS),
        Rat("anchor_pic", "Anchor", "Rare", BRAWLERS),
        Rat("beacon_pic", "Beacon", "Legendary", SMUGGLERS),
        Rat("bolt_pic", "Bolt", "Common", FOUNDRY_BORN),
        Rat("sooty_pic", "Sooty", "Common", SCAVENGERS),
        Rat("boop_pic", "Boop", "Common", SMUGGLERS),
        Rat("foundry_pic", "Foundry", "Legendary", FOUNDRY_BORN),
        Rat("wrencher_pic", "Wrencher", "Rare", FOUNDRY_BORN),
        Rat("welder_pic", "Welder", "Rare", TINKERERS),
        Rat("rivet_pic", "Rivet", "Rare", FOUNDRY_BORN),
        Rat("cogtail_pic", "Cogtail", "Rare", BRAWLERS),
        Rat("forman_pic", "Forman", "Legendary", TINKERERS),
        Rat("blaze_pic", "Blaze", "Legendary", BRAWLERS),
        Rat("brasscap_pic", "Brasscap", "Common", SMUGGLERS),
        Rat("gauge_pic", "Gauge", "Common", TINKERERS),
        Rat("boiler_pic", "Boiler", "Common", FOUNDRY_BORN),
        Rat("canal_pic", "Canal", "Common", SCAVENGERS),
        Rat("chimney_pic", "Chimney", "Legendary", FOUNDRY_BORN),
        Rat("coppernose_pic", "Coppernose", "Common", BRAWLERS),
        Rat("greasepaw_pic", "Greasepaw", "Common", BRAWLERS),
        Rat("hopper_pic", "Hopper", "Rare", SCAVENGERS),
        Rat("latch_pic", "Latch", "Rare", SMUGGLERS),
        Rat("magneto_pic", "Magneto", "Common", TINKERERS),
        Rat("moptail_pic", "moptail", "Common", SMUGGLERS),
        Rat("miller_pic", "Miller", "Rare", TINKERERS),
        Rat("piston_pic", "Piston", "Common", TINKERERS),
        Rat("rafter_pic", "Rafter", "Rare", SCAVENGERS),
        Rat("winch_pic", "Winch", "Common", FOUNDRY_BORN),
        Rat("spindle_pic", "Spindle", "Common", BRAWLERS),
        Rat("sparkplug_pic", "Sparkplug", "Legendary", TINKERERS),
        Rat("nutkin_pic", "Nutkin", "Common", SCAVENGERS)
    )

    /**
     * Every species at [rarity], matched without case.
     *
     * The one way to ask the roster about a tier. Anything that instead keeps
     * its own list of which species are Legendary drifts the moment a species is
     * added - which is exactly what happened to the Fusion Pot, where three
     * hand-written lists had fallen 18 species behind this one.
     */
    fun withRarity(rarity: String): List<Rat> =
        all.filter { it.rarity.equals(rarity, ignoreCase = true) }

    val common: List<Rat> = withRarity(COMMON)
    val rare: List<Rat> = withRarity(RARE)
    val legendary: List<Rat> = withRarity(LEGENDARY)

    /**
     * The stat bonus a rarity tier carries into combat, applied identically to
     * Power and Toughness.
     *
     * Flat rather than proportional like the Shop's Power Surge and Golden
     * Wrench: those cost Scrap and last one fight, this is free and permanent,
     * so it stays deliberately small next to them. Matched without case for the
     * same reason [withRarity] is - nothing here should depend on a tag never
     * being mistyped again.
     */
    fun statBonusFor(rarity: String?): Int = when {
        rarity == null -> 0
        rarity.equals(LEGENDARY, ignoreCase = true) -> 2
        rarity.equals(RARE, ignoreCase = true) -> 1
        else -> 0
    }

    /**
     * Gears the rarity badge draws: one for Common, two for Rare, three for
     * Legendary. Defaults to one rather than zero for an unrecognised tag, the
     * same reasoning [statBonusFor] defaults to no bonus - a badge with no
     * gears at all would read as broken rather than as merely Common.
     */
    fun gearCountFor(rarity: String?): Int = when {
        rarity == null -> 1
        rarity.equals(LEGENDARY, ignoreCase = true) -> 3
        rarity.equals(RARE, ignoreCase = true) -> 2
        else -> 1
    }
}
