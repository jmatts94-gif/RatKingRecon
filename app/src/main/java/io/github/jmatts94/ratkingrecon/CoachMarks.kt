package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import androidx.annotation.IdRes
import androidx.annotation.StringRes

/** One stop on the home-screen walkthrough: what to point at, and what to say. */
data class CoachMark(
    @param:IdRes val targetId: Int,
    @param:StringRes val captionRes: Int
)

/**
 * The home-screen walkthrough, and the flag that retires it.
 *
 * Separate from [Onboarding] and additional to it. The splash explains the game;
 * this points at four things on the workshop screen and gets out of the way. It
 * reads [Onboarding.KEY_COMPLETE] as a precondition but never writes it - the
 * splash owns that flag, and the two run one after the other rather than
 * together.
 *
 * [KEY_COMPLETE] sits in the same "SaveData" preferences as everything else, so
 * Reset Save brings the walkthrough back for the fresh start that follows and
 * the flag travels with an exported save - the same bargain [Onboarding] makes.
 */
object CoachMarks {

    const val KEY_COMPLETE = "home_coach_complete"

    fun isComplete(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_COMPLETE, false)

    fun markComplete(prefs: SharedPreferences) {
        prefs.edit().putBoolean(KEY_COMPLETE, true).apply()
    }

    /**
     * Whether the workshop should be pointed at on this visit.
     *
     * The onboarding half of this is what makes "first real visit" work without
     * either screen knowing about the other. MainActivity starts the splash and
     * carries on building underneath it, so on a true first launch it reaches
     * onResume once while the splash is still unfinished - and the flag is
     * false, so nothing happens. The splash sets it on the way out, MainActivity
     * resumes a second time, and that resume is the first visit to a home screen
     * the player can actually see.
     */
    fun shouldShow(prefs: SharedPreferences): Boolean =
        Onboarding.isComplete(prefs) && !isComplete(prefs)

    /**
     * The stops, in order.
     *
     * Data rather than code, like [Onboarding.pages] - adding or reordering one
     * needs no change to the overlay, and the "2 of 4" counter follows the list
     * rather than a number written down twice.
     */
    val steps = listOf(
        CoachMark(R.id.playerLevelText, R.string.coach_level),
        CoachMark(R.id.stepCountText, R.string.coach_steps),
        CoachMark(R.id.scrapText, R.string.coach_scrap),
        CoachMark(R.id.streakTile, R.string.coach_streak)
    )
}
