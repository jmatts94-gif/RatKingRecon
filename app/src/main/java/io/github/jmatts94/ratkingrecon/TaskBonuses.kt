package io.github.jmatts94.ratkingrecon

import androidx.annotation.StringRes
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * What a rat's faction is worth to a Ledger Task or the Scrap Run, if one is
 * assigned at all.
 *
 * One bonus per faction, unlike the boss combat wheel where Foundry-born is
 * deliberately neutral - every faction here maps to exactly one effect, so
 * the only "no bonus" case is no rat assigned, not an off-faction one. Every
 * function below takes the faction as a nullable String and is a no-op for
 * null or a non-matching one, so a caller never has to branch on whether a
 * rat was actually picked before asking these what it is worth.
 */
object TaskBonuses {

    /** Smugglers: extra Scrap on completion. */
    const val SCRAP_MULTIPLIER = 1.15

    /** Scavengers: shorter duration, decided once at start. */
    const val DURATION_MULTIPLIER = 0.90

    /**
     * Tinkerers: extra relic chance, additive rather than multiplicative so it
     * cannot distort M3's already-high base the way a percentage bump would.
     */
    const val RELIC_CHANCE_BONUS = 0.08

    /**
     * The Scrap Run's own relic chance, which did not exist before this - it
     * paid Scrap only. Small and flat, so Tinkerers have something to add to
     * everywhere a rat can be assigned rather than just on the Ledger board.
     */
    const val EXPEDITION_BASE_RELIC_CHANCE = 0.08

    /** Brawlers: a flat chance to finish the moment the job starts. */
    const val INSTANT_COMPLETE_CHANCE = 0.05

    /**
     * Foundry-born: flat EXP per activity, deliberately not derived from the
     * Scrap reward or the duration so it cannot compound with either. Small
     * enough that even an unrealistic nonstop-relaunch day does not meaningfully
     * dent the levelling curve - see the numbers this was checked against in
     * the commit this shipped in.
     */
    const val EXP_EXPEDITION = 20
    const val EXP_LEDGER_M1 = 10
    const val EXP_LEDGER_M2 = 30
    const val EXP_LEDGER_M3 = 75

    /** Which activity EXP is being banked for, so [expFor] knows which flat amount applies. */
    enum class Activity { EXPEDITION, LEDGER_M1, LEDGER_M2, LEDGER_M3 }

    private fun matches(faction: String?, target: String): Boolean =
        target.equals(faction, ignoreCase = true)

    /** [baseReward] boosted for a Smuggler, unchanged for anyone else (including nobody). */
    fun scrapFor(baseReward: Int, faction: String?): Int =
        if (matches(faction, Roster.SMUGGLERS)) {
            (baseReward * SCRAP_MULTIPLIER).roundToInt()
        } else {
            baseReward
        }

    /** [baseDurationMs] shortened for a Scavenger, unchanged for anyone else. */
    fun durationFor(baseDurationMs: Long, faction: String?): Long =
        if (matches(faction, Roster.SCAVENGERS)) {
            (baseDurationMs * DURATION_MULTIPLIER).roundToLong()
        } else {
            baseDurationMs
        }

    /** [baseChance] raised for a Tinkerer, unchanged for anyone else. Never past certainty. */
    fun relicChanceFor(baseChance: Double, faction: String?): Double =
        if (matches(faction, Roster.TINKERERS)) {
            (baseChance + RELIC_CHANCE_BONUS).coerceAtMost(1.0)
        } else {
            baseChance
        }

    /** Whether a Brawler's instant-complete fires, rolled once. Always false for anyone else. */
    fun rollsInstantComplete(faction: String?): Boolean =
        matches(faction, Roster.BRAWLERS) && Math.random() < INSTANT_COMPLETE_CHANCE

    /**
     * The end time a job starting at [startTime] should be given: immediately,
     * if a Brawler's instant-complete fires, otherwise [startTime] plus
     * whatever [durationFor] made of [baseDurationMs].
     */
    fun endTimeFor(startTime: Long, baseDurationMs: Long, faction: String?): Long =
        if (rollsInstantComplete(faction)) {
            startTime
        } else {
            startTime + durationFor(baseDurationMs, faction)
        }

    /**
     * The player-facing line for what [faction] is worth, or null for nobody
     * or an unrecognised faction. Shown beside the Deploy button and in the
     * Ledger Task rat picker, so the bonus is a visible reason to pick one rat
     * over another rather than a hidden incentive.
     */
    @StringRes
    fun descriptionFor(faction: String?): Int? = when {
        matches(faction, Roster.SMUGGLERS) -> R.string.task_bonus_smugglers
        matches(faction, Roster.SCAVENGERS) -> R.string.task_bonus_scavengers
        matches(faction, Roster.TINKERERS) -> R.string.task_bonus_tinkerers
        matches(faction, Roster.BRAWLERS) -> R.string.task_bonus_brawlers
        matches(faction, Roster.FOUNDRY_BORN) -> R.string.task_bonus_foundry_born
        else -> null
    }

    /** Foundry-born's flat EXP for [activity], or 0 for anyone else (including nobody). */
    fun expFor(activity: Activity, faction: String?): Int {
        if (!matches(faction, Roster.FOUNDRY_BORN)) return 0
        return when (activity) {
            Activity.EXPEDITION -> EXP_EXPEDITION
            Activity.LEDGER_M1 -> EXP_LEDGER_M1
            Activity.LEDGER_M2 -> EXP_LEDGER_M2
            Activity.LEDGER_M3 -> EXP_LEDGER_M3
        }
    }
}
