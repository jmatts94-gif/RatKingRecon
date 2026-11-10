package io.github.jmatts94.ratkingrecon

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import java.util.Calendar

/**
 * One gentle notification on Sunday evening: the week's steps, the days the
 * goal was hit, and a cheer for a best-ever week.
 *
 * Has its own switch in Settings rather than sharing the hatch alerts one, so
 * a player can keep exactly the alerts they like.
 */
object WeeklySummary {

    const val KEY_ENABLED = "weekly_summary_enabled"
    const val KEY_BEST_WEEK = "WEEKLY_BEST_STEPS"
    const val KEY_LAST_SENT_DAY = "WEEKLY_SUMMARY_SENT_DAY"

    /** 6pm, clear of the 7pm streak reminder. */
    const val HOUR = 18

    private const val ALARM_REQUEST = 201

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    /** The next Sunday at [HOUR], strictly after [now]. */
    fun nextSundayEvening(now: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now
        calendar.set(Calendar.HOUR_OF_DAY, HOUR)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        while (calendar.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY || calendar.timeInMillis <= now) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    /** What the notification says, or null when there is nothing to say. */
    data class Summary(val total: Int, val goalDays: Int, val bestYet: Boolean)

    /**
     * Works out this week's summary and records it, so it is sent once and the
     * best week is remembered. Null if it is off, already sent today, or the
     * week had no steps at all.
     */
    fun prepare(prefs: SharedPreferences, now: Long = System.currentTimeMillis()): Summary? {
        if (!isEnabled(prefs)) return null
        val today = DailySteps.dayStamp(now)
        if (prefs.getInt(KEY_LAST_SENT_DAY, 0) == today) return null

        val days = StepHistory.lastDays(prefs, now)
        val total = days.sumOf { it.steps }
        if (total <= 0) return null

        val previousBest = prefs.getInt(KEY_BEST_WEEK, 0)
        // The very first summary has nothing to beat, so it does not claim a record.
        val bestYet = previousBest > 0 && total > previousBest

        prefs.edit()
            .putInt(KEY_LAST_SENT_DAY, today)
            .putInt(KEY_BEST_WEEK, maxOf(previousBest, total))
            .apply()

        return Summary(total, StepGoal.daysMet(days, StepGoal.goalOf(prefs)), bestYet)
    }

    /** Inexact, like the streak reminder: a weekly note does not care about a few minutes. */
    fun schedule(context: Context, now: Long = System.currentTimeMillis()) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextSundayEvening(now),
            PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST,
                Intent(context, WeeklySummaryReceiver::class.java).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
    }
}

/** Wakes on Sunday evening, posts the week, and books next Sunday. */
class WeeklySummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WeeklySummary.prepare(RatRepository.prefs(context))?.let { DailyAlerts.postWeeklySummary(context, it) }
        WeeklySummary.schedule(context)
    }
}
