package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import java.util.Calendar
import kotlin.math.min

/**
 * Winter Recon: walk [GOAL_STEPS] between 1 December 2026 and 11 January 2027
 * for the Frostbound frame and some Scrap. Steps only, so it needs no fights.
 *
 * The keys carry the year, so a later winter can start clean. Debug builds run
 * it at any date, so the banner can be seen on a test phone.
 */
object WinterEvent {

    const val GOAL_STEPS = 25_000
    const val SCRAP_REWARD = 300

    const val KEY_STEPS = "WINTER_2026_STEPS"
    const val KEY_DONE = "WINTER_2026_DONE"

    private fun localMidnight(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day)
        }.timeInMillis

    /** Local time, so the event opens and shuts at the player's own midnight. */
    fun inWindow(now: Long): Boolean =
        now >= localMidnight(2026, Calendar.DECEMBER, 1) &&
            now < localMidnight(2027, Calendar.JANUARY, 12)

    fun isRunning(now: Long = System.currentTimeMillis()): Boolean =
        BuildConfig.DEBUG || inWindow(now)

    fun steps(prefs: SharedPreferences): Int = prefs.getInt(KEY_STEPS, 0)

    fun isDone(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_DONE, false)

    /** Banks steps. True only on the one batch that finishes the goal and pays out. */
    fun advance(prefs: SharedPreferences, gained: Int, running: Boolean = isRunning()): Boolean {
        if (!running || gained <= 0 || isDone(prefs)) return false

        val total = min(steps(prefs) + gained, GOAL_STEPS)
        val editor = prefs.edit().putInt(KEY_STEPS, total)
        val finished = total >= GOAL_STEPS
        if (finished) {
            editor.putBoolean(KEY_DONE, true)
                .putInt(GameEngine.KEY_SCRAP, GameEngine.scrapOf(prefs) + SCRAP_REWARD)
        }
        editor.apply()

        if (finished) ShopEffects.grantCosmetic(prefs, Frames.FROSTBOUND.id)
        return finished
    }

    /** Debug top-up only: leaves a short walk to go, to test the finish. */
    fun debugNearlyDone(prefs: SharedPreferences) {
        prefs.edit().putInt(KEY_STEPS, GOAL_STEPS - 50).putBoolean(KEY_DONE, false).apply()
    }
}
