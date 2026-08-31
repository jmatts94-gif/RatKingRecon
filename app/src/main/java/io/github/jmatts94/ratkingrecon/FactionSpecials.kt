package io.github.jmatts94.ratkingrecon

/**
 * What a rat's faction does to its own Special - see [Battle.specialDamage]
 * and the secondary-effect rolls in [Battle.advance].
 *
 * Every faction still deals damage; nothing here trades damage away for
 * nothing. Brawlers get the only bigger raw multiplier - "pure power" - and
 * the other four keep the same 1.5x [Battle.SPECIAL_MULTIPLIER] everyone
 * always had, with one extra effect layered on top instead. That is what
 * keeps the five roughly level: a flat +20% for one faction against a
 * situational, sometimes-more-sometimes-less effect for the other four,
 * rather than five different multipliers that would have to be re-balanced
 * against each other by feel.
 *
 * Every roll is independent per Special use, the same way [SpliceEffects]
 * and [TaskBonuses] roll independently per faction rather than sharing one
 * die - a Tinkerer's block chance says nothing about a Scavenger's refund
 * chance, because they are never both in play on the same rat.
 */
object FactionSpecials {

    /** Brawlers: the one faction with a bigger raw multiplier instead of a secondary effect. */
    const val BRAWLER_MULTIPLIER = 1.8

    /**
     * Foundry-born: a DOT riding the Special, the same shape Rusty Rake's own
     * corrosion uses (a share of Power per round) but shorter - two rounds,
     * not three, since this is a bonus on top of a hit that already lands
     * rather than a boss's whole move. Worth roughly as much as Brawler's
     * extra 0.3x over a long fight, but wasted if the Special is what
     * finishes the target - the DOT never gets a round to tick.
     */
    const val FOUNDRY_BORN_DOT_FRACTION = 0.15
    const val FOUNDRY_BORN_DOT_ROUNDS = 2

    /**
     * Tinkerers: a chance to fully block the enemy's next hit, reusing the
     * exact flag a Protective Bubble sets - see [Battle.advance]. Utility
     * instead of a guaranteed number: worth a lot against a hit that would
     * have hurt, worth nothing against one that would not have landed at
     * all, which is the trade a precision faction is supposed to make.
     */
    const val TINKERER_BLOCK_CHANCE = 0.30

    /**
     * Scavengers: a chance the Special's cooldown lands one round short of
     * [Battle.SPECIAL_COOLDOWN] instead of a full refund - a full refund's
     * geometric chance of chaining (roughly +54% more Special casts on
     * average at even a modest chance) risked spiralling; shaving one round
     * is a bounded, modest frequency bump instead.
     */
    const val SCAVENGER_REFUND_CHANCE = 0.40

    /**
     * Smugglers: a chance for the Special to skim a windfall onto this
     * fight's own Scrap reward - the same "extra Scrap" identity
     * [TaskBonuses.SCRAP_MULTIPLIER] already gives Smugglers on a Ledger
     * Task, carried into combat instead of lifesteal.
     *
     * Lifesteal used to live here, but it was the only sustain any faction
     * had, which made Smugglers close to mandatory for a fight with no
     * margin for error (an Arena run's late fights, most of all) rather than
     * one good option among five. [PermanentBuffs.RUSTED_FANG_LIFESTEAL_FRACTION]
     * is where sustain lives now - earned once, available to every faction,
     * not gated behind picking one of them.
     *
     * Rolled only once per fight - see the `windfallProcced` guard in
     * [Battle.advance] - rather than every Special use like the other four
     * factions' effects: a Special comes around every [Battle.SPECIAL_COOLDOWN]
     * rounds, and an uncapped economy bonus would reward stalling a fight
     * out for extra rolls instead of finishing it.
     */
    const val SMUGGLER_WINDFALL_CHANCE = 0.35

    /** What a successful windfall (see [SMUGGLER_WINDFALL_CHANCE]) adds to the fight's reward. */
    const val SMUGGLER_WINDFALL_BONUS = 0.15

    private fun matches(faction: String?, target: String): Boolean =
        target.equals(faction, ignoreCase = true)

    /** The base multiplier a Special deals for [faction] - 1.8x for Brawlers, [Battle.SPECIAL_MULTIPLIER] for everyone else, including null or an unrecognised tag. */
    fun multiplierFor(faction: String?): Double =
        if (matches(faction, Roster.BRAWLERS)) BRAWLER_MULTIPLIER else Battle.SPECIAL_MULTIPLIER

    fun isFoundryBorn(faction: String?): Boolean = matches(faction, Roster.FOUNDRY_BORN)
    fun isTinkerer(faction: String?): Boolean = matches(faction, Roster.TINKERERS)
    fun isScavenger(faction: String?): Boolean = matches(faction, Roster.SCAVENGERS)
    fun isSmuggler(faction: String?): Boolean = matches(faction, Roster.SMUGGLERS)
}
