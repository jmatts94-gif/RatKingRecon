package io.github.jmatts94.ratkingrecon

import android.app.Activity
import android.content.SharedPreferences
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Asks for a Play rating once, right after the first boss badge: a high point,
 * and late enough that the player knows the game.
 *
 * Google decides whether the card really appears and gives no answer back, so
 * nothing in the game can depend on it.
 */
object RatingPrompt {

    const val KEY_ASKED = "RATING_PROMPT_ASKED"

    /** True exactly once, on the first badge. Records the ask as it answers. */
    fun claim(prefs: SharedPreferences, badgeEarned: Boolean): Boolean {
        if (!badgeEarned || prefs.getBoolean(KEY_ASKED, false)) return false
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        return true
    }

    fun askIfDue(activity: Activity, prefs: SharedPreferences, badgeEarned: Boolean) {
        if (!claim(prefs, badgeEarned)) return
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (request.isSuccessful && !activity.isFinishing && !activity.isDestroyed) {
                manager.launchReviewFlow(activity, request.result)
            }
        }
    }
}
