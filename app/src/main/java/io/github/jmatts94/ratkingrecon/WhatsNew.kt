package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences

/**
 * The release notes shown once after an update, and the flag that retires them.
 *
 * [KEY_LAST_SEEN] lives in the same "SaveData" preferences as everything else,
 * which means Reset Save brings the notes back for the fresh start that follows
 * and an exported save carries the flag with it - the same bargain [Onboarding]
 * already makes for the walkthrough.
 *
 * What is shown is not in here. The list is a string resource so a release can
 * be written up without touching the rule that decides when to show it.
 */
object WhatsNew {

    const val KEY_LAST_SEEN = "LAST_SEEN_VERSION"

    /**
     * No version recorded: a fresh install, or a save from before this existed.
     *
     * Distinct from a real versionCode rather than defaulting to zero, because
     * "never seen" and "last saw version 0" want opposite answers - the first is
     * somebody who has nothing to catch up on, and treating them as the second
     * would open a changelog on a game they have not played yet.
     */
    private const val UNSEEN = -1

    /**
     * Whether the notes are owed, stamping the current version either way.
     *
     * One call rather than a query and a separate acknowledgement, so there is
     * no path that shows the notes and forgets to record it - which would show
     * them again on the next resume, and every resume after that.
     *
     * A first run answers false and stamps: an install is not an update, and
     * somebody opening the game for the first time is not owed a list of what
     * changed since a version they never had. A downgrade also answers false and
     * stamps down, so the notes reappear if that build is upgraded again.
     */
    fun consume(prefs: SharedPreferences, current: Int = BuildConfig.VERSION_CODE): Boolean {
        val lastSeen = prefs.getInt(KEY_LAST_SEEN, UNSEEN)

        if (lastSeen != current) {
            prefs.edit().putInt(KEY_LAST_SEEN, current).apply()
        }

        return lastSeen != UNSEEN && current > lastSeen
    }
}
