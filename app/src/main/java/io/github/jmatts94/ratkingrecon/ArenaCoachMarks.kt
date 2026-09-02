package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences

/**
 * The first-visit walkthrough of the champion picker, [ArenaSelectActivity].
 *
 * Follows the same shape as [CoachMarks] - its own revision, its own
 * preference key, its own step list - but with no legacy boolean to read: this
 * walkthrough never existed as a plain flag, so there is nothing earlier to be
 * owed. Reaching this screen at all already means a run is not active and the
 * player has at least one eligible rat, so there is no empty-roster case to
 * design around the way the home screen's does.
 *
 * The Arena's own rules - 15 fights, no healing between them - are already the
 * home screen's [CoachMarks.steps] last stop; repeating that here would be the
 * same sentence twice on two different screens. This walkthrough only covers
 * what is specific to choosing a champion.
 */
object ArenaCoachMarks {

    const val REVISION = 1
    const val KEY_REVISION = "arena_coach_revision"

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
        CoachMark(R.id.arenaSelectGrid, R.string.coach_arena_select_grid),
        CoachMark(R.id.arenaSelectStatusText, R.string.coach_arena_select_status),
        CoachMark(R.id.arenaSelectConfirmButton, R.string.coach_arena_select_confirm)
    )
}
