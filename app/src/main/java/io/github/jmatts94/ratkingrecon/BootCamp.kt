package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import kotlin.math.max

/**
 * A new player's first minute: a free starter rat and a practice fight.
 *
 * Without it a fresh install is an empty workshop and 250 steps of waiting,
 * and most people open a new app sitting down. This puts a rat in their hands
 * and a Rustbot in front of it before they have walked anywhere.
 *
 * Both flags live in "SaveData" like the rest of the save, so Reset Save
 * brings Boot Camp back along with the walkthrough.
 */
object BootCamp {

    const val KEY_STARTER_GIVEN = "BOOTCAMP_STARTER_GIVEN"

    /** Set while the practice fight is the pending encounter. */
    const val KEY_PRACTICE_PENDING = "BOOTCAMP_PRACTICE_PENDING"

    /** A starter is never worse than 3/3 - a first rat should feel like a find. */
    const val STARTER_MIN_STAT = 3

    const val PRACTICE_REWARD = 25

    fun needsStarter(prefs: SharedPreferences): Boolean =
        !prefs.getBoolean(KEY_STARTER_GIVEN, false)

    fun markStarterGiven(prefs: SharedPreferences) {
        prefs.edit().putBoolean(KEY_STARTER_GIVEN, true).apply()
    }

    /**
     * Mints the starter, or returns null for a save that already has rats.
     *
     * The roster check is what keeps this away from existing players: anyone
     * updating into Boot Camp has hatched before, so they are simply marked
     * done. Runs off the main thread - it touches the database.
     */
    fun grantStarterIfDue(dao: RatDao, prefs: SharedPreferences): RatEntity? {
        if (!needsStarter(prefs)) return null
        if (dao.count() > 0) {
            markStarterGiven(prefs)
            return null
        }
        val rat = GameEngine.mintRat(dao, prefs, minStat = STARTER_MIN_STAT)
        markStarterGiven(prefs)
        return rat
    }

    fun isPracticePending(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_PRACTICE_PENDING, false)

    fun clearPractice(prefs: SharedPreferences) {
        prefs.edit().remove(KEY_PRACTICE_PENDING).apply()
    }

    /**
     * Sets up the practice fight as the pending encounter.
     *
     * A soft target on purpose: it hits for 1 and has half the rat's health,
     * so the first fight teaches the buttons rather than testing the roll.
     * Returns false if something else is already pending - that fight wins.
     */
    fun raisePracticeFight(prefs: SharedPreferences, rat: RatEntity, botName: String): Boolean {
        if (Encounter.isPending(prefs)) return false
        Encounter.save(
            prefs,
            Encounter(
                ratId = rat.id,
                botName = botName,
                botPower = 1,
                botMaxHp = max(1, rat.maxHp / 2),
                reward = PRACTICE_REWARD
            )
        )
        prefs.edit().putBoolean(KEY_PRACTICE_PENDING, true).apply()
        return true
    }
}
