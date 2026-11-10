package io.github.jmatts94.ratkingrecon

import android.app.Activity
import android.content.SharedPreferences
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Asks for a Play rating once, at whichever good moment comes first:
 * - the first boss badge, for players who fight;
 * - closing the 7-day chart with at least [CHART_DAYS_NEEDED] walking days on
 *   it, for players who mostly use the game to count their steps.
 *
 * Google decides whether the card really appears and gives no answer back, so
 * nothing in the game can depend on it.
 */
object RatingPrompt {

    const val KEY_ASKED = "RATING_PROMPT_ASKED"

    /** Most of a week: a regular walker, and a chart worth looking at. */
    const val CHART_DAYS_NEEDED = 5

    /** Whether the chart that was just closed is a good moment to ask. */
    fun chartIsDue(days: List<StepHistory.Day>): Boolean =
        days.count { it.steps > 0 } >= CHART_DAYS_NEEDED

    /** True exactly once, at the first good moment. Records the ask as it answers. */
    fun claim(prefs: SharedPreferences, due: Boolean): Boolean {
        if (!due || prefs.getBoolean(KEY_ASKED, false)) return false
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        return true
    }

    fun askIfDue(activity: Activity, prefs: SharedPreferences, due: Boolean) {
        if (!claim(prefs, due)) return
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (request.isSuccessful && !activity.isFinishing && !activity.isDestroyed) {
                manager.launchReviewFlow(activity, request.result)
            }
        }
    }
}
