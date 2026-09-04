package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** What a milestone is measured against. */
enum class MilestoneKind { STEPS, RATS_HELD, SPECIES, SHINY, MASTERWORK, STAT_15, STAT_25, SPLICE_COUNT, SPLICE_TINKERER }

/**
 * One achievement row.
 *
 * [id] reaches the save as the latch behind the badge, so it must never change
 * once shipped. [target] is 1 for the yes/no ones, which keeps the progress
 * arithmetic uniform.
 */
data class Milestone(
    val id: String,
    @param:StringRes val nameRes: Int,
    @param:DrawableRes val iconRes: Int,
    val kind: MilestoneKind,
    val target: Long
)

/** Everything the milestones are measured against, read once per refresh. */
data class MilestoneProgress(
    val lifetimeSteps: Long = 0L,
    val ratsHeld: Int = 0,
    val speciesFound: Int = 0,
    val ownsShiny: Boolean = false,
    val masterworkPulled: Boolean = false,
    /** Whether the roster currently holds a rat at 15 Power and 15 Toughness or better. */
    val hasStat15Rat: Boolean = false,
    /** Whether the roster currently holds a rat at 25 Power and 25 Toughness or better. */
    val hasStat25Rat: Boolean = false,
    /** Lifetime splices completed at the Fusion Pot. Only ever climbs - see [Milestones.KEY_LIFETIME_SPLICES]. */
    val lifetimeSplices: Long = 0L,
    /** Whether a Tinkerer roll has ever fired on a splice. */
    val tinkererTriggered: Boolean = false
)

/**
 * The walking, collecting and hatching half of the Achievements screen.
 *
 * Every milestone is *latched*: once met it is written to the save and read back
 * from there, never recomputed from the collection. That is not belt-and-braces,
 * it is required. The Fusion Pot consumes two rats to mint one, so the roster
 * shrinks as it is used - a player who reached 25 rats and then spliced twice
 * would watch an earned badge disappear. The same goes for the only Shiny in a
 * collection, or the last instance of the species that completed it.
 *
 * Steps are the exception that proves it: [GameEngine.KEY_LIFETIME_STEPS] only
 * ever climbs. They are latched anyway, so every row on the screen answers the
 * same question the same way.
 */
object Milestones {

    /**
     * Average stride, for the distance line under the step total.
     *
     * A population average, not this player's: nothing in the app asks for
     * height or stride, so every distance shown is an estimate.
     */
    const val METRES_PER_STEP = 0.762

    private const val LATCH_PREFIX = "MILESTONE_"

    /**
     * Set when the Masterwork Hatchery mints a rat.
     *
     * A flag rather than something read back from the collection, because a
     * Masterwork rat is not marked as one - it carries no column the ordinary
     * hatch does not - and splicing it away would otherwise revoke the badge.
     */
    const val KEY_MASTERWORK_PULLED = "MASTERWORK_PULLED"

    /** Lifetime splices completed at the Fusion Pot. Monotonic like [GameEngine.KEY_LIFETIME_STEPS]. */
    const val KEY_LIFETIME_SPLICES = "LIFETIME_SPLICES"

    /** Set the first time a Tinkerer roll fires on a splice - see [SpliceEffects.Kind.TINKERER]. */
    const val KEY_TINKERER_TRIGGERED = "TINKERER_TRIGGERED"

    val steps: List<Milestone> = listOf(
        Milestone("steps_10k", R.string.milestone_first_steps, R.drawable.ic_footprint, MilestoneKind.STEPS, 10_000L),
        Milestone("steps_50k", R.string.milestone_pathfinder, R.drawable.ic_footprint, MilestoneKind.STEPS, 50_000L),
        Milestone("steps_100k", R.string.milestone_wayfarer, R.drawable.ic_expedition, MilestoneKind.STEPS, 100_000L),
        Milestone("steps_250k", R.string.milestone_trailblazer, R.drawable.ic_expedition, MilestoneKind.STEPS, 250_000L),
        Milestone("steps_500k", R.string.milestone_far_walker, R.drawable.ic_expedition, MilestoneKind.STEPS, 500_000L),
        // Lowered from 1,000,000 - the id stays "steps_1m" regardless, the
        // same never-move-it-once-shipped rule every other milestone id in
        // this file already follows. Now the same target steps_100k already
        // uses; the two unlock together rather than one gating the other,
        // which is fine - each is still its own latch, its own reward.
        Milestone("steps_1m", R.string.milestone_rat_king, R.drawable.ic_star, MilestoneKind.STEPS, 100_000L)
    )

    val roster: List<Milestone> = listOf(
        Milestone("roster_10", R.string.milestone_nest, R.drawable.ic_pets, MilestoneKind.RATS_HELD, 10L),
        Milestone("roster_25", R.string.milestone_colony, R.drawable.ic_book, MilestoneKind.RATS_HELD, 25L),
        Milestone("roster_50", R.string.milestone_swarm, R.drawable.ic_library_add, MilestoneKind.RATS_HELD, 50L),
        // Distinct species, not rats held - "Full Collection" means the same
        // thing here as the counter at the top of the Ledger.
        Milestone(
            "roster_full", R.string.milestone_full_collection, R.drawable.ic_hexagon,
            MilestoneKind.SPECIES, Roster.all.size.toLong()
        )
    )

    val hatching: List<Milestone> = listOf(
        Milestone("hatch_shiny", R.string.milestone_first_shiny, R.drawable.ic_sparkle, MilestoneKind.SHINY, 1L),
        Milestone("hatch_masterwork", R.string.milestone_first_masterwork, R.drawable.ic_egg, MilestoneKind.MASTERWORK, 1L),
        Milestone("hatch_stat15", R.string.milestone_battle_ready, R.drawable.ic_toughness, MilestoneKind.STAT_15, 1L),
        Milestone("hatch_stat25", R.string.milestone_apex_rat, R.drawable.ic_power, MilestoneKind.STAT_25, 1L)
    )

    /**
     * Splicing at the Fusion Pot. "Lucky Break" pairs with [SPLICE_TINKERER] rather
     * than a count, the same reason the other three categories keep one milestone
     * that isn't a plain threshold - a splice track built from nothing but bigger
     * numbers would read the same way roster and hatching already do.
     */
    val splicing: List<Milestone> = listOf(
        Milestone("splice_first", R.string.milestone_first_splice, R.drawable.ic_flask, MilestoneKind.SPLICE_COUNT, 1L),
        Milestone("splice_10", R.string.milestone_mad_scientist, R.drawable.ic_flask, MilestoneKind.SPLICE_COUNT, 10L),
        Milestone("splice_lucky", R.string.milestone_lucky_break, R.drawable.ic_sparkle, MilestoneKind.SPLICE_TINKERER, 1L),
        Milestone("splice_25", R.string.milestone_chimera_master, R.drawable.ic_hexagon, MilestoneKind.SPLICE_COUNT, 25L)
    )

    val all: List<Milestone> = steps + roster + hatching + splicing

    // ---- measuring -----------------------------------------------------------

    fun currentFor(milestone: Milestone, progress: MilestoneProgress): Long =
        when (milestone.kind) {
            MilestoneKind.STEPS -> progress.lifetimeSteps
            MilestoneKind.RATS_HELD -> progress.ratsHeld.toLong()
            MilestoneKind.SPECIES -> progress.speciesFound.toLong()
            MilestoneKind.SHINY -> if (progress.ownsShiny) 1L else 0L
            MilestoneKind.MASTERWORK -> if (progress.masterworkPulled) 1L else 0L
            MilestoneKind.STAT_15 -> if (progress.hasStat15Rat) 1L else 0L
            MilestoneKind.STAT_25 -> if (progress.hasStat25Rat) 1L else 0L
            MilestoneKind.SPLICE_COUNT -> progress.lifetimeSplices
            MilestoneKind.SPLICE_TINKERER -> if (progress.tinkererTriggered) 1L else 0L
        }

    fun isMet(milestone: Milestone, progress: MilestoneProgress): Boolean =
        currentFor(milestone, progress) >= milestone.target

    /**
     * Progress towards [milestone], 0..100, for the bar under a locked row.
     *
     * Whole percent because the bar is a few pixels tall and nothing finer would
     * be visible.
     */
    fun percentTowards(milestone: Milestone, progress: MilestoneProgress): Int =
        ((currentFor(milestone, progress).toDouble() / milestone.target) * 100)
            .toInt().coerceIn(0, 100)

    fun kilometresFor(steps: Long): Double = steps * METRES_PER_STEP / 1000.0

    // ---- latching ------------------------------------------------------------

    fun isEarned(prefs: SharedPreferences, milestone: Milestone): Boolean =
        prefs.getBoolean(LATCH_PREFIX + milestone.id, false)

    fun earnedCount(prefs: SharedPreferences): Int = all.count { isEarned(prefs, it) }

    /**
     * Latches every milestone [progress] now satisfies, and returns the ones
     * that were not already earned.
     *
     * Only ever sets, never clears, which is what makes an achievement permanent.
     */
    fun refresh(prefs: SharedPreferences, progress: MilestoneProgress): List<Milestone> {
        val newlyEarned = all.filter { !isEarned(prefs, it) && isMet(it, progress) }
        if (newlyEarned.isEmpty()) return emptyList()

        val editor = prefs.edit()
        newlyEarned.forEach { editor.putBoolean(LATCH_PREFIX + it.id, true) }
        editor.apply()
        newlyEarned.forEach { grantReward(prefs, it.id) }
        return newlyEarned
    }

    /**
     * Latches the step milestones alone.
     *
     * Separate because the full refresh reads the collection, and this one is
     * called from the sensor path where a database round trip per batch of steps
     * would be paid on every reading the pedometer delivers.
     */
    fun refreshSteps(prefs: SharedPreferences, lifetimeSteps: Long): List<Milestone> {
        val progress = MilestoneProgress(lifetimeSteps = lifetimeSteps)
        val newlyEarned = steps.filter { !isEarned(prefs, it) && isMet(it, progress) }
        if (newlyEarned.isEmpty()) return emptyList()

        val editor = prefs.edit()
        newlyEarned.forEach { editor.putBoolean(LATCH_PREFIX + it.id, true) }
        editor.apply()
        newlyEarned.forEach { grantReward(prefs, it.id) }
        return newlyEarned
    }

    /** Pays whatever [AchievementRewards] has attached to [milestoneId], if anything. */
    private fun grantReward(prefs: SharedPreferences, milestoneId: String) {
        AchievementRewards.forMilestone(milestoneId)?.let { AchievementRewards.grant(prefs, it) }
    }

    /** Effective Power/Toughness a rat must clear for [MilestoneKind.STAT_15]. */
    private const val STAT_15_THRESHOLD = 15
    /** Effective Power/Toughness a rat must clear for [MilestoneKind.STAT_25]. */
    private const val STAT_25_THRESHOLD = 25

    /** Reads everything the milestones measure. Blocking: it queries the collection. */
    fun readProgress(dao: RatDao, prefs: SharedPreferences): MilestoneProgress {
        // Loaded once and reused for both stat checks rather than two separate
        // full-table reads - the other fields below stay their own lightweight
        // aggregate queries, unchanged, since only the stat check needs a rat's
        // own row to test two conditions against each other.
        val roster = dao.all()
        return MilestoneProgress(
            lifetimeSteps = GameEngine.lifetimeStepsOf(prefs),
            ratsHeld = dao.count(),
            speciesFound = dao.distinctSpeciesFound(Roster.all.map { it.artKey }),
            ownsShiny = dao.ownsShiny(),
            masterworkPulled = prefs.getBoolean(KEY_MASTERWORK_PULLED, false),
            hasStat15Rat = roster.any {
                it.effectivePower >= STAT_15_THRESHOLD && it.effectiveToughness >= STAT_15_THRESHOLD
            },
            hasStat25Rat = roster.any {
                it.effectivePower >= STAT_25_THRESHOLD && it.effectiveToughness >= STAT_25_THRESHOLD
            },
            lifetimeSplices = prefs.getLong(KEY_LIFETIME_SPLICES, 0L),
            tinkererTriggered = prefs.getBoolean(KEY_TINKERER_TRIGGERED, false)
        )
    }

    /** Records a Masterwork pull, for the badge that has nothing else to read. */
    fun recordMasterworkPull(prefs: SharedPreferences) {
        prefs.edit().putBoolean(KEY_MASTERWORK_PULLED, true).apply()
    }

    /**
     * Records one splice at the Fusion Pot. Monotonic like [GameEngine.KEY_LIFETIME_STEPS] -
     * the roster shrinks as splicing is used, so a badge counting rats held would
     * be the wrong thing to gate this on.
     */
    fun recordSplice(prefs: SharedPreferences) {
        prefs.edit().putLong(KEY_LIFETIME_SPLICES, prefs.getLong(KEY_LIFETIME_SPLICES, 0L) + 1).apply()
    }

    /** Records that a Tinkerer roll has fired at least once, for "Lucky Break". */
    fun recordTinkererTrigger(prefs: SharedPreferences) {
        prefs.edit().putBoolean(KEY_TINKERER_TRIGGERED, true).apply()
    }
}
