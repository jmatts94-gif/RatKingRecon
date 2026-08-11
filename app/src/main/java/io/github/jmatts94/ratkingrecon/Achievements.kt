package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** What a milestone is measured against. */
enum class MilestoneKind { STEPS, RATS_HELD, SPECIES, SHINY, MASTERWORK }

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
    val masterworkPulled: Boolean = false
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

    val steps: List<Milestone> = listOf(
        Milestone("steps_10k", R.string.milestone_first_steps, R.drawable.ic_footprint, MilestoneKind.STEPS, 10_000L),
        Milestone("steps_50k", R.string.milestone_pathfinder, R.drawable.ic_footprint, MilestoneKind.STEPS, 50_000L),
        Milestone("steps_100k", R.string.milestone_wayfarer, R.drawable.ic_expedition, MilestoneKind.STEPS, 100_000L),
        Milestone("steps_250k", R.string.milestone_trailblazer, R.drawable.ic_expedition, MilestoneKind.STEPS, 250_000L),
        Milestone("steps_500k", R.string.milestone_far_walker, R.drawable.ic_expedition, MilestoneKind.STEPS, 500_000L),
        Milestone("steps_1m", R.string.milestone_rat_king, R.drawable.ic_star, MilestoneKind.STEPS, 1_000_000L)
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
        Milestone("hatch_masterwork", R.string.milestone_first_masterwork, R.drawable.ic_egg, MilestoneKind.MASTERWORK, 1L)
    )

    val all: List<Milestone> = steps + roster + hatching

    // ---- measuring -----------------------------------------------------------

    fun currentFor(milestone: Milestone, progress: MilestoneProgress): Long =
        when (milestone.kind) {
            MilestoneKind.STEPS -> progress.lifetimeSteps
            MilestoneKind.RATS_HELD -> progress.ratsHeld.toLong()
            MilestoneKind.SPECIES -> progress.speciesFound.toLong()
            MilestoneKind.SHINY -> if (progress.ownsShiny) 1L else 0L
            MilestoneKind.MASTERWORK -> if (progress.masterworkPulled) 1L else 0L
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
        return newlyEarned
    }

    /** Reads everything the milestones measure. Blocking: it queries the collection. */
    fun readProgress(dao: RatDao, prefs: SharedPreferences): MilestoneProgress =
        MilestoneProgress(
            lifetimeSteps = GameEngine.lifetimeStepsOf(prefs),
            ratsHeld = dao.count(),
            speciesFound = dao.distinctSpeciesFound(Roster.all.map { it.artKey }),
            ownsShiny = dao.ownsShiny(),
            masterworkPulled = prefs.getBoolean(KEY_MASTERWORK_PULLED, false)
        )

    /** Records a Masterwork pull, for the badge that has nothing else to read. */
    fun recordMasterworkPull(prefs: SharedPreferences) {
        prefs.edit().putBoolean(KEY_MASTERWORK_PULLED, true).apply()
    }
}
