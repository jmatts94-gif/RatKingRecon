package io.github.jmatts94.ratkingrecon

import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Silences a notification on the versions where a channel cannot, and only there.
 *
 * Exists because [NotificationCompat.Builder.setSilent] is not what it sounds
 * like. As well as muting, it stamps GROUP_ALERT_SUMMARY on the notification -
 * "the summary of my group does the alerting for me". Android auto-bundles
 * several notifications from one app into a group, and a child that defers to a
 * summary which was never posted is dropped: accepted by notify(), missing from
 * getActiveNotifications, never in the shade, and no error anywhere. That is
 * what hid the daily step count, and it would have taken every other alert in
 * the app with it the moment the sound switch was turned off.
 *
 * From API 26 the channel already carries silence - every alert here has a
 * quiet twin channel with no sound and no vibration - so nothing needs doing.
 * Below 26 there are no channels, so it is spelled out on the notification
 * instead. None of these three touch grouping.
 */
fun NotificationCompat.Builder.silenceWithoutGrouping(
    quiet: Boolean
): NotificationCompat.Builder {
    if (!quiet || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return this

    return setSound(null)
        .setVibrate(null)
        .setDefaults(0)
}
