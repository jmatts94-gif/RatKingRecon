package com.example.ratkingrecon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings step tracking back after a reboot or an app update, so the player does
 * not have to open the app to resume progress.
 *
 * BOOT_COMPLETED and MY_PACKAGE_REPLACED are both on Android's exemption list
 * for starting a foreground service from the background, so this is a legal
 * start. It is still wrapped defensively - see [StepTrackerService.start] - the
 * one thing this must never do is crash during boot.
 *
 * Known limits, none of which are fixable from here:
 *  - Android will not deliver BOOT_COMPLETED until the app has been launched at
 *    least once, and not again after a force-stop until it is launched manually.
 *  - The step counter resets to zero on reboot, so the handful of steps taken
 *    between boot and this receiver firing are not counted.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val started = StepTrackerService.start(context)
                Log.i(TAG, "${intent.action}: step tracking started = $started")
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
