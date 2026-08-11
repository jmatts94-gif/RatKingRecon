package com.example.ratkingrecon

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** One lifetime-walking target. */
data class Milestone(
    val steps: Long,
    @param:StringRes val nameRes: Int,
    @param:DrawableRes val iconRes: Int
)

/**
 * The walking half of the Achievements screen.
 *
 * Counted against [GameEngine.KEY_LIFETIME_STEPS], which is a real running total
 * rather than the sensor's own reading - see that constant for why the two are
 * not interchangeable.
 */
object Milestones {

    /**
     * Average stride, for the distance line under each milestone.
     *
     * A population average, not this player's: nothing in the app asks for
     * height or stride, so every distance shown is explicitly an estimate.
     */
    const val METRES_PER_STEP = 0.762

    val all: List<Milestone> = listOf(
        Milestone(10_000L, R.string.milestone_first_steps, R.drawable.ic_footprint),
        Milestone(50_000L, R.string.milestone_pathfinder, R.drawable.ic_footprint),
        Milestone(100_000L, R.string.milestone_wayfarer, R.drawable.ic_expedition),
        Milestone(500_000L, R.string.milestone_far_walker, R.drawable.ic_expedition),
        Milestone(1_000_000L, R.string.milestone_rat_king, R.drawable.ic_star)
    )

    fun reached(lifetimeSteps: Long, milestone: Milestone): Boolean =
        lifetimeSteps >= milestone.steps

    fun reachedCount(lifetimeSteps: Long): Int = all.count { reached(lifetimeSteps, it) }

    fun kilometresFor(steps: Long): Double = steps * METRES_PER_STEP / 1000.0

    /**
     * Progress towards [milestone], 0..100, for the bar under a locked row.
     *
     * Whole percent because the bar is a few dozen pixels tall and nothing finer
     * would be visible.
     */
    fun percentTowards(lifetimeSteps: Long, milestone: Milestone): Int =
        ((lifetimeSteps.toDouble() / milestone.steps) * 100).toInt().coerceIn(0, 100)
}
