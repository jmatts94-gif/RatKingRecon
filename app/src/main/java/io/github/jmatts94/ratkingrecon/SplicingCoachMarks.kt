package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences

/**
 * The first-visit walkthrough of the Fusion Pot, [SplicingActivity].
 *
 * Follows the same shape as [CoachMarks] - its own revision, its own
 * preference key, its own step list - with no legacy boolean, the same as
 * [ArenaCoachMarks]. Onboarding's own "Fuse and Fight" page already sets up
 * the idea of splicing before a player can reach this screen at all; this
 * walkthrough is the specifics onboarding had no room for - what the mutant's
 * stats are built from, and that a faction effect can fire on top of them.
 */
object SplicingCoachMarks {

    const val REVISION = 1
    const val KEY_REVISION = "splicing_coach_revision"

    fun seenRevision(prefs: SharedPreferences): Int = prefs.getInt(KEY_REVISION, 0)

    fun stepsFor(prefs: SharedPreferences): List<CoachMark> {
        val seen = seenRevision(prefs)
        return steps.filter { it.revisedIn > seen }
    }

    fun shouldShow(prefs: SharedPreferences): Boolean = stepsFor(prefs).isNotEmpty()

    fun markComplete(prefs: SharedPreferences) {
        prefs.edit().putInt(KEY_REVISION, REVISION).apply()
    }

    val steps = listOf(
        CoachMark(R.id.splicePetGrid, R.string.coach_splice_grid),
        CoachMark(R.id.spliceStatusText, R.string.coach_splice_status),
        CoachMark(R.id.spliceConfirmButton, R.string.coach_splice_confirm)
    )
}
