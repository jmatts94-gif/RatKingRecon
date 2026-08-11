package com.example.ratkingrecon

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Handles Auto-Resolve tapped straight from the notification.
 *
 * The whole point is that a fight can be settled with the phone locked and in a
 * pocket, so this never opens a screen: it plays the battle on a worker thread
 * and replaces the alert with a result.
 */
class EncounterActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_AUTO_RESOLVE = "com.example.ratkingrecon.AUTO_RESOLVE"
        const val CHANNEL_ENCOUNTER = "encounter_alerts_v1"

        /** Silent twin of [CHANNEL_ENCOUNTER]; see the note on the hatch channels. */
        const val CHANNEL_ENCOUNTER_QUIET = "encounter_alerts_quiet_v1"

        const val NOTIF_ENCOUNTER = 3
        const val NOTIF_RESULT = 4

        fun channelFor(quiet: Boolean): String =
            if (quiet) CHANNEL_ENCOUNTER_QUIET else CHANNEL_ENCOUNTER
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_AUTO_RESOLVE) return

        val app = context.applicationContext
        // Broadcast receivers run on the main thread and must return quickly;
        // the database work and the battle happen off it.
        val pending = goAsync()

        Thread {
            try {
                resolve(app)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun resolve(app: Context) {
        val prefs = RatRepository.prefs(app)
        val encounter = Encounter.load(prefs) ?: return
        val rat = RatRepository.dao(app).byId(encounter.ratId) ?: run {
            Encounter.clear(prefs)
            return
        }

        val battle = AutoResolver.resolve(encounter.toBattle(rat))
        val resolution = EncounterResolver.apply(app, encounter, rat, battle)

        val manager = app.getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIF_ENCOUNTER)

        val title = if (resolution.won) {
            app.getString(R.string.notif_result_title_win)
        } else {
            app.getString(R.string.notif_result_title_loss)
        }
        val body = if (resolution.won) {
            app.getString(
                R.string.battle_won,
                resolution.ratName, resolution.botName, resolution.reward
            )
        } else {
            app.getString(R.string.battle_lost, resolution.ratName, resolution.botName)
        }

        val quiet = !GameSettings.soundEnabled(prefs)

        manager.notify(
            NOTIF_RESULT,
            NotificationCompat.Builder(app, channelFor(quiet))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(if (resolution.won) R.drawable.ic_hexagon else R.drawable.ic_toughness)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setSilent(quiet)
                .build()
        )
    }
}
