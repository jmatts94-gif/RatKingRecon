package io.github.jmatts94.ratkingrecon

/**
 * The Fusion Pot's rarity engine.
 *
 * Lives here rather than inline in [GalleryActivity] for the reason the bug it
 * replaces went unnoticed for so long: a `when` buried in a click listener
 * inside a coroutine cannot be reached from a unit test, so nothing could
 * observe that its species lists had fallen behind [Roster].
 *
 * Those lists named 14 species between them. The roster holds 32. Splicing
 * could therefore never produce the other 18 - two Legendaries, four Rares and
 * twelve Commons - no matter how much Scrap a player fed it. Tiers are now
 * derived from [Roster], so a species added there is reachable the same day.
 */
object Fusion {

    /** A roll of this or under mints a Legendary: a 5% chance on a d100. */
    const val LEGENDARY_ROLL = 5

    /** Above [LEGENDARY_ROLL] and up to this mints a Rare: the next 25%. */
    const val RARE_ROLL = 30

    /**
     * Picks the species a splice produces.
     *
     * [roll] is passed in rather than drawn here so tests can name the tier they
     * are checking; the no-argument overload does the drawing.
     *
     * Falls back to the whole roster if a tier is somehow empty, which a roster
     * edit could cause. A splice that mints an odd species is a smaller failure
     * than one that crashes the Ledger after taking the player's Scrap.
     *
     * [bonusFraction] is Collector's Instinct's own flat bonus - see
     * [PermanentBuffs.COLLECTORS_INSTINCT_ODDS_BONUS] - added as extra points
     * onto the Rare cutoff rather than as a multiplier on either tier's own
     * odds, so a fixed 10-point buff always means exactly ten points, whatever
     * [LEGENDARY_ROLL]/[RARE_ROLL] happen to be tuned to. Legendary's own cut
     * is left untouched; the widened band comes out of Common instead.
     */
    fun speciesFor(roll: Int, bonusFraction: Double = 0.0): Rat {
        val rareCut = RARE_ROLL + bonusFraction * 100.0
        val tier = when {
            roll <= LEGENDARY_ROLL -> Roster.legendary
            roll <= rareCut -> Roster.rare
            else -> Roster.common
        }
        return tier.randomOrNull() ?: Roster.all.random()
    }

    /**
     * Rolls a splice's species, [boosted] by a Tinkerer's own effect roll -
     * see [SpliceEffects] - and by [bonusFraction] if Collector's Instinct has
     * been earned - see [speciesFor].
     *
     * Rolls the d100 twice and keeps the lower, rather than reworking the
     * tier thresholds for a second code path: a lower roll is always a tier
     * at least as good, so a roll that already landed Legendary on its own
     * cannot be improved on either way - the boost only ever helps, never
     * hurts, without needing to special-case that outcome.
     */
    fun roll(boosted: Boolean = false, bonusFraction: Double = 0.0): Rat {
        val primary = (1..100).random()
        val roll = if (boosted) minOf(primary, (1..100).random()) else primary
        return speciesFor(roll, bonusFraction)
    }
}
