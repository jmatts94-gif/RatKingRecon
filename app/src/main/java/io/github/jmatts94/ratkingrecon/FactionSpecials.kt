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
     * Smugglers: lifesteal off the rat's own max HP, not off the Special's
     * damage.
     *
     * A share of damage dealt was the first cut of this, at 2-5%, but
     * "damage dealt" is [Battle.SPECIAL_MULTIPLIER] times Power, and Power is
     * a small number for most of the game - a percentage of it rounds to a
     * 1-2 HP trickle that never reads as sustain at all. A share of the
     * rat's own max HP scales with Toughness instead, which stays a real
     * number even when Power does not, and reads as what lifesteal is
     * supposed to feel like: a fight this rat can outlast, not just outhit.
     */
    const val SMUGGLER_LIFESTEAL_FRACTION = 0.05

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
