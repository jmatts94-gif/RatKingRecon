package io.github.jmatts94.ratkingrecon

/**
 * The chance each parent's own faction has to leave its mark on a splice, on
 * top of the outcome [Fusion] already rolls.
 *
 * Each of the two parents rolls independently off its own faction - a
 * same-faction pair rolls the same effect twice, and both successes stack,
 * the same as a cross-faction pair's two different effects both landing.
 * Nothing here favours or excludes a pairing; [SplicingActivity.splice] is
 * the only caller and treats every parent's roll identically.
 *
 * The five chances are deliberately not equal. Smugglers and the stat-trade
 * pair (Brawlers, Foundry-born) are priced against each other by net stat
 * sum, not by chance alone: a flat +1/+1 nets two points with no downside, so
 * it has to roll less often than a +2/-1 or -1/+2 trade that only nets one -
 * otherwise the safe option would dominate the risky ones outright.
 * Scavengers never touch combat stats at all, so they can afford to be the
 * most generous. Tinkerers are the outlier: usually nothing (a roll that
 * already landed Legendary has nothing left to boost), occasionally a full
 * rarity tier, which is worth more than any single stat point here - so it is
 * rarest of the five.
 */
object SpliceEffects {

    const val SMUGGLER_CHANCE = 0.20
    const val BRAWLER_CHANCE = 0.30
    const val FOUNDRY_BORN_CHANCE = 0.30
    const val SCAVENGER_CHANCE = 0.35
    const val TINKERER_CHANCE = 0.15

    /** Share of the splice's Scrap cost a successful Scavenger roll hands back. */
    const val SCAVENGER_REFUND_FRACTION = 0.40

    enum class Kind { SMUGGLER, BRAWLER, FOUNDRY_BORN, SCAVENGER, TINKERER }

    /** One hit's Power/Toughness change. Zero for the two effects that don't touch stats. */
    data class StatDelta(val power: Int, val toughness: Int)

    private fun matches(faction: String?, target: String): Boolean =
        target.equals(faction, ignoreCase = true)

    /**
     * Rolls the one effect [faction] can trigger, or null for an unrecognised
     * faction (including no faction at all) or a roll that simply misses.
     */
    fun rollFor(faction: String?): Kind? {
        val (kind, chance) = when {
            matches(faction, Roster.SMUGGLERS) -> Kind.SMUGGLER to SMUGGLER_CHANCE
            matches(faction, Roster.BRAWLERS) -> Kind.BRAWLER to BRAWLER_CHANCE
            matches(faction, Roster.FOUNDRY_BORN) -> Kind.FOUNDRY_BORN to FOUNDRY_BORN_CHANCE
            matches(faction, Roster.SCAVENGERS) -> Kind.SCAVENGER to SCAVENGER_CHANCE
            matches(faction, Roster.TINKERERS) -> Kind.TINKERER to TINKERER_CHANCE
            else -> return null
        }
        return kind.takeIf { Math.random() < chance }
    }

    /**
     * What one success of [kind] does to the mutant's stats.
     *
     * The single source for these numbers - [SplicingActivity.splice] sums
     * this per triggered roll to get the mutant's real stats, and reads it
     * again to word the result toast, so the two can never drift apart.
     */
    fun statDeltaFor(kind: Kind): StatDelta = when (kind) {
        Kind.SMUGGLER -> StatDelta(power = 1, toughness = 1)
        Kind.BRAWLER -> StatDelta(power = 2, toughness = -1)
        Kind.FOUNDRY_BORN -> StatDelta(power = -1, toughness = 2)
        Kind.SCAVENGER, Kind.TINKERER -> StatDelta(power = 0, toughness = 0)
    }
}
