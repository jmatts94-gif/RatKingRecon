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

/**
 * Tells the player when a Ledger Task has finished.
 *
 * A task ends on a wall clock rather than on anything the app is doing, so
 * unlike a hatch there is no running code to notice it. An alarm is set when the
 * task starts and wakes this receiver at the end - which is also why claiming
 * stayed silent before: nothing was ever watching.
 *
 * The alarm is inexact ([AlarmManager.setAndAllowWhileIdle]) on purpose. It
 * needs no permission on Android 12+, and a task measured in hours does not care
 * about a few minutes' drift.
 */
object LedgerTaskAlarms {

    /** The three tasks, in the order the screen shows them. */
    val TASK_IDS = listOf("M1", "M2", "M3")

    const val EXTRA_TASK_ID = "task_id"

    fun schedule(context: Context, taskId: String, endAt: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, intentFor(context, taskId))
    }

    /**
     * Re-arms every unfinished task.
     *
     * Alarms do not survive a reboot, so this runs from [BootReceiver]. An end
     * time already in the past simply fires straight away, which is the right
     * answer for a task that finished while the phone was off.
     */
    fun scheduleAll(context: Context) {
        val prefs = RatRepository.prefs(context)
        for (id in TASK_IDS) {
            if (!prefs.getBoolean("${id}_ACTIVE", false)) continue
            schedule(context, id, prefs.getLong("${id}_END_TIME", 0L))
        }
    }

    private fun intentFor(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, LedgerTaskAlarmReceiver::class.java)
            .setPackage(context.packageName)
            .putExtra(EXTRA_TASK_ID, taskId)

        return PendingIntent.getBroadcast(
            context,
            // One slot per task, so three alarms can be pending at once.
            TASK_IDS.indexOf(taskId).coerceAtLeast(0) + 100,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}

/** Posts the "task finished" alert. */
class LedgerTaskAlarmReceiver : BroadcastReceiver() {

    companion object {
        /**
         * Its own channel pair rather than borrowing the hatch one, so the player
         * can silence task alerts separately in the system settings. The silent
         * twin exists because a channel's sound is fixed once it is created.
         */
        private const val CHANNEL = "task_alerts_v1"
        private const val CHANNEL_QUIET = "task_alerts_quiet_v1"

        private const val NOTIF_BASE = 20
    }

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(LedgerTaskAlarms.EXTRA_TASK_ID) ?: return
        val prefs = RatRepository.prefs(context)

        // Nothing to announce if it was already claimed, or if the alarm ran
        // early for any reason.
        if (!prefs.getBoolean("${taskId}_ACTIVE", false)) return
        if (System.currentTimeMillis() < prefs.getLong("${taskId}_END_TIME", 0L)) return

        val title = prefs.getString("${taskId}_TITLE", null) ?: return
        val quiet = !GameSettings.soundEnabled(prefs)

        createChannels(context)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, LedgerTasksActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, if (quiet) CHANNEL_QUIET else CHANNEL)
            .setContentTitle(context.getString(R.string.notif_task_title))
            .setContentText(context.getString(R.string.notif_task_text, title))
            .setSmallIcon(R.drawable.ic_expedition)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setSilent(quiet)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIF_BASE + LedgerTaskAlarms.TASK_IDS.indexOf(taskId), notification)
    }

    /**
     * Created here rather than in StepTrackerService because the alarm can fire
     * without the service having run this boot, and a notification posted to a
     * channel that does not exist is dropped.
     */
    private fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.channel_task_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.channel_task_desc) }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_QUIET,
                context.getString(R.string.channel_task_quiet_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_task_desc)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }
}
