package io.github.jmatts94.ratkingrecon

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Calendar

/**
 * The three alerts the daily loop is allowed to post, and nothing else.
 *
 * Built on the same pattern as [LedgerTaskAlerts] rather than a system of its
 * own: a channel and a silent twin, created here because an alarm can fire in a
 * boot where the step service never ran and a notification posted to a channel
 * that does not exist is dropped.
 *
 * One channel covers all three so a player who does not want daily nagging can
 * turn off exactly that in the system settings without losing hatch or combat
 * alerts.
 */
object DailyAlerts {

    private const val CHANNEL = "daily_round_v1"
    private const val CHANNEL_QUIET = "daily_round_quiet_v1"

    // Kept clear of StepTrackerService (1-6) and LedgerTaskAlerts (20+).
    private const val NOTIF_BOSS_TEASER = 30
    private const val NOTIF_CONTRACT_PAID = 31
    private const val NOTIF_STREAK = 32

    /** Hour of the local evening the streak reminder is aimed at. */
    private const val STREAK_REMINDER_HOUR = 19

    private const val ALARM_REQUEST = 200

    // ---- posting -------------------------------------------------------------

    /** Hints that something big is near, without promising it or naming it. */
    fun postBossTeaser(context: Context) {
        post(
            context,
            NOTIF_BOSS_TEASER,
            context.getString(R.string.notif_boss_teaser_title),
            context.getString(R.string.notif_boss_teaser_text),
            R.drawable.ic_power
        )
    }

    /** A contract crossed its step target and paid out while the app was away. */
    fun postContractPaid(context: Context, name: String, reward: Int) {
        post(
            context,
            NOTIF_CONTRACT_PAID,
            context.getString(R.string.notif_contract_paid_title),
            context.getString(R.string.notif_contract_paid_text, name, reward),
            R.drawable.ic_contract
        )
    }

    private fun post(
        context: Context,
        id: Int,
        title: String,
        body: String,
        iconRes: Int
    ) {
        val prefs = RatRepository.prefs(context)
        if (!GameSettings.notificationsEnabled(prefs)) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        createChannels(context)
        val quiet = !GameSettings.soundEnabled(prefs)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, if (quiet) CHANNEL_QUIET else CHANNEL)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(iconRes)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(open)
            .silenceWithoutGrouping(quiet)
            .build()

        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    // ---- the streak reminder -------------------------------------------------

    /**
     * Books tonight's reminder, or tomorrow's if the evening has already gone.
     *
     * Inexact, like the Ledger Task alarms and for the same reason: it needs no
     * permission on Android 12+, and a nudge measured against a whole day does
     * not care about a few minutes.
     */
    fun scheduleStreakReminder(context: Context, now: Long = System.currentTimeMillis()) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return

        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now
        calendar.set(Calendar.HOUR_OF_DAY, STREAK_REMINDER_HOUR)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (calendar.timeInMillis <= now) calendar.add(Calendar.DAY_OF_YEAR, 1)

        manager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST,
                Intent(context, StreakReminderReceiver::class.java).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
    }

    /**
     * Posts the evening reminder, if there is anything to remind about.
     *
     * Silent when the quest is already done, and silent at a streak of zero on a
     * day with no grace owed - there is nothing at risk, and a notification that
     * fires whether or not it matters stops being read.
     */
    fun postStreakReminderIfDue(context: Context) {
        val prefs = RatRepository.prefs(context)
        DailyQuest.ensureToday(prefs)

        if (DailyQuest.isComplete(prefs)) return

        val streak = Streak.count(prefs)
        val grace = Streak.onGraceDay(prefs)
        if (streak <= 0 && !grace) return

        val body = if (grace) {
            context.getString(R.string.notif_streak_text_grace)
        } else {
            context.getString(R.string.notif_streak_text, streak)
        }

        post(
            context,
            NOTIF_STREAK,
            context.getString(R.string.notif_streak_title),
            body,
            R.drawable.ic_lantern
        )
    }

    private fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.channel_daily_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.channel_daily_desc) }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_QUIET,
                context.getString(R.string.channel_daily_quiet_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_daily_desc)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }
}

/** Wakes in the evening to ask whether the streak is about to lapse. */
class StreakReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DailyAlerts.postStreakReminderIfDue(context)
        // Books the next one, since an inexact alarm is a single shot.
        DailyAlerts.scheduleStreakReminder(context)
    }
}
