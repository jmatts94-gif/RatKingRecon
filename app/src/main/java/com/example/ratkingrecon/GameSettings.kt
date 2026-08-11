package com.example.ratkingrecon

import android.content.SharedPreferences

/**
 * The player-facing switches on the Settings screen.
 *
 * Both default to true, so a save written before these keys existed behaves
 * exactly as it always did rather than starting out silent.
 *
 * These live in the same "SaveData" preferences as everything else, which means
 * they travel with an exported save. That is deliberate: restoring a backup puts
 * the player's own choices back too.
 */
object GameSettings {

    const val KEY_NOTIFICATIONS = "notifications_enabled"
    const val KEY_SOUND = "sound_enabled"

    /** Whether a hatch may raise a notification at all. */
    fun notificationsEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_NOTIFICATIONS, true)

    /** Whether an alert may make a sound or vibrate. */
    fun soundEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_SOUND, true)
}
