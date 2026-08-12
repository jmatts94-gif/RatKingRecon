package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences

/**
 * How many days running the player has finished the daily quest.
 *
 * A missed day does not end a streak. The first one only raises a flag; it takes
 * a second consecutive miss to reset. That is the whole point of the rule - one
 * bad day is a bad day, not a reason to stop playing - and it means the streak
 * has three states rather than two: running, running-but-owed, and broken.
 *
 * Everything lives in the same "SaveData" preferences as the rest of the game,
 * so a streak travels with an exported save and is cleared by Reset Save.
 */
object Streak {

    const val KEY_COUNT = "STREAK_COUNT"

    /** Local midnight of the last day whose quest was finished. */
    const val KEY_LAST_DAY = "STREAK_LAST_DAY"

    /** Set when exactly one day has been missed. A second miss resets instead. */
    const val KEY_MISSED = "STREAK_MISSED"

    fun count(prefs: SharedPreferences): Int = prefs.getInt(KEY_COUNT, 0)

    /** True when one day has been dropped and the next one decides the streak. */
    fun onGraceDay(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_MISSED, false)

    /**
     * Banks a completed day.
     *
     * Clears the grace flag, because finishing the quest is exactly what the
     * grace day was waiting for. A day already banked cannot be banked twice -
     * completion writes the day it happened on, and a second call for the same
     * day is ignored, so nothing can double-count a streak by finishing one
     * quest and then re-checking.
     */
    fun recordCompletion(
        prefs: SharedPreferences,
        editor: SharedPreferences.Editor,
        today: Long
    ) {
        if (prefs.getLong(KEY_LAST_DAY, 0L) == today) return

        editor.putInt(KEY_COUNT, count(prefs) + 1)
            .putLong(KEY_LAST_DAY, today)
            .putBoolean(KEY_MISSED, false)
    }

    /**
     * Settles what the days between [lastSeenDay] and [today] did to the streak.
     *
     * Called once when the app notices the date has changed, and handed the gap
     * rather than assuming it is one day: a player who does not open the app all
     * week comes back to a single rollover covering seven days, and treating
     * that as one missed day would make a streak survive any absence at all.
     *
     * [completedLastSeenDay] is whether the quest that was running when the app
     * was last used got finished, which is the difference between the gap
     * starting yesterday and starting the day before.
     */
    fun settleRollover(
        prefs: SharedPreferences,
        editor: SharedPreferences.Editor,
        lastSeenDay: Long,
        today: Long,
        completedLastSeenDay: Boolean
    ) {
        // Days that went by without a completed quest. The day last seen counts
        // as missed unless its quest was finished; every whole day since is
        // missed by definition, because the app was not open to finish one.
        val elapsed = DailySteps.daysBetween(lastSeenDay, today)
        if (elapsed <= 0) return

        val missedNow = (elapsed - 1) + if (completedLastSeenDay) 0 else 1
        if (missedNow <= 0) return

        val alreadyOwed = if (onGraceDay(prefs)) 1 else 0

        if (missedNow + alreadyOwed >= 2) {
            editor.putInt(KEY_COUNT, 0).putBoolean(KEY_MISSED, false)
        } else {
            editor.putBoolean(KEY_MISSED, true)
        }
    }
}
