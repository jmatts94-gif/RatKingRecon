package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences

/**
 * The first-visit walkthrough of the Contract Board and Ledger Tasks,
 * [TasksActivity].
 *
 * Follows the same shape as [CoachMarks] - its own revision, its own
 * preference key, its own step list - with no legacy boolean, the same as
 * [ArenaCoachMarks] and [SplicingCoachMarks]. Both halves of this screen get a
 * stop: the Contract Board's walking bounties, and the Ledger Tasks below
 * them, which - since the rat-not-just-faction change - are worth telling a
 * new player apart from a bounty at a glance.
 */
object TasksCoachMarks {

    const val REVISION = 1
    const val KEY_REVISION = "tasks_coach_revision"

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
        CoachMark(R.id.contractOffers, R.string.coach_tasks_contracts),
        CoachMark(R.id.btnMission1, R.string.coach_tasks_ledger),
        CoachMark(R.id.relicCountText, R.string.coach_tasks_relics)
    )
}
