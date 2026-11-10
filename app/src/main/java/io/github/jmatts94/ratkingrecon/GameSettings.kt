package io.github.jmatts94.ratkingrecon

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

    /** Whether distances show in miles rather than km - defaults false, same reasoning as the others. */
    const val KEY_DISTANCE_MILES = "distance_unit_miles"

    /**
     * The one toggle here that defaults to false rather than true - an opt-in
     * look, not a behaviour a save written before it existed should suddenly
     * start applying.
     */
    /** Off: no new Rustbots are raised on walks, so no alert is ever owed. Bosses are unaffected. */
    const val KEY_RUSTBOT_ALERTS = "rustbot_alerts_enabled"

    const val KEY_DARK_STEAMPUNK = "dark_steampunk_enabled"

    /**
     * Whether a hatch, banked boss, Ledger Task, or daily-loop alert may raise a
     * notification at all.
     *
     * Deliberately does not cover the Rustbot encounter alert: that alert is
     * the only way in to the fight. [rustbotAlertsEnabled] stops the encounters
     * themselves instead, so turning it off cannot strand one.
     */
    fun rustbotAlertsEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_RUSTBOT_ALERTS, true)

    fun notificationsEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_NOTIFICATIONS, true)

    /** Whether an alert may make a sound or vibrate. */
    fun soundEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_SOUND, true)

    /** Whether the dark steampunk palette is on - see RatKingApp, which applies it at launch. */
    fun darkSteampunkEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_DARK_STEAMPUNK, false)

    /** Whether every distance in the app - see [Milestones.distanceFor] - shows in miles rather than km. */
    fun distanceUnitMiles(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_DISTANCE_MILES, false)
}
