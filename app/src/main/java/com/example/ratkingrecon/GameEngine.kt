package com.example.ratkingrecon

import android.content.SharedPreferences

/**
 * Every step-driven game rule, in one place.
 *
 * This exists because the step sensor moved into [StepTrackerService]. If both
 * the service and the Activity processed sensor events they would each read,
 * modify and write the same SharedPreferences keys, double-counting EXP or
 * losing a hatched rat. The service is now the only caller of [onSteps]; the
 * Activity reads state through the accessors below and never mutates progress.
 */
object GameEngine {

    const val KEY_LEVEL = "PLAYER_LEVEL"
    const val KEY_EXP = "CURRENT_EXP"
    const val KEY_SCRAP = "SCRAP"

    /** Cumulative sensor reading at the last time steps were banked. */
    private const val KEY_BASELINE = "STEP_BASELINE"

    /** Last cumulative reading seen, so the UI can show a session total. */
    const val KEY_TOTAL_STEPS = "STEP_TOTAL"

    const val KEY_MUTAGEN = "MUTAGEN_ACTIVE"
    const val KEY_POLISH = "POLISH_ACTIVE"

    private const val KEY_BOUNTY_ACTIVE = "BOUNTY_ACTIVE"
    private const val KEY_BOUNTY_TARGET = "BOUNTY_TARGET"
    private const val KEY_BOUNTY_END = "BOUNTY_END_TIME"
    private const val KEY_BOUNTY_REWARD = "BOUNTY_REWARD"

    private const val EXP_PER_LEVEL = 50

    /** What a batch of steps produced. All fields are "nothing happened" by default. */
    data class Outcome(
        val hatched: RatEntity? = null,
        val newLevel: Int = 0,
        val bountyReward: Int = 0,
        val bountyFailed: Boolean = false,
        val changed: Boolean = false
    )

    fun levelOf(prefs: SharedPreferences): Int = prefs.getInt(KEY_LEVEL, 1)
    fun expOf(prefs: SharedPreferences): Int = prefs.getInt(KEY_EXP, 0)
    fun scrapOf(prefs: SharedPreferences): Int = prefs.getInt(KEY_SCRAP, 0)
    fun totalStepsOf(prefs: SharedPreferences): Float = prefs.getFloat(KEY_TOTAL_STEPS, 0f)
    fun maxExpFor(level: Int): Int = level * EXP_PER_LEVEL

    /**
     * Folds a cumulative step-counter reading into the save.
     *
     * [totalSteps] is TYPE_STEP_COUNTER's value, which counts from device boot
     * and resets to zero on reboot - handled below by re-baselining rather than
     * subtracting into a negative.
     */
    fun onSteps(dao: RatDao, prefs: SharedPreferences, totalSteps: Float): Outcome {
        val baseline = prefs.getFloat(KEY_BASELINE, -1f)

        // First reading ever, or the device rebooted and the counter restarted.
        if (baseline < 0f || totalSteps < baseline) {
            prefs.edit()
                .putFloat(KEY_BASELINE, totalSteps)
                .putFloat(KEY_TOTAL_STEPS, totalSteps)
                .apply()
            return Outcome()
        }

        val gained = (totalSteps - baseline).toInt()
        if (gained <= 0) return Outcome()

        val editor = prefs.edit()
        editor.putFloat(KEY_BASELINE, totalSteps)
        editor.putFloat(KEY_TOTAL_STEPS, totalSteps)

        val bounty = resolveBounty(prefs, editor, totalSteps)

        var level = levelOf(prefs)
        var exp = expOf(prefs) + gained
        var hatched: RatEntity? = null
        var newLevel = 0

        if (exp >= maxExpFor(level)) {
            hatched = rollRat(prefs, editor)
            // Room assigns the instance id; keep the stored copy so callers see it.
            hatched = hatched.copy(id = dao.insert(hatched))
            level += 1
            newLevel = level
            exp = 0
        }

        editor.putInt(KEY_LEVEL, level)
        editor.putInt(KEY_EXP, exp)
        editor.apply()

        return Outcome(
            hatched = hatched,
            newLevel = newLevel,
            bountyReward = bounty.first,
            bountyFailed = bounty.second,
            changed = true
        )
    }

    /** Rolls a rat, consuming any active consumables. */
    private fun rollRat(prefs: SharedPreferences, editor: SharedPreferences.Editor): RatEntity {
        val mutagen = prefs.getBoolean(KEY_MUTAGEN, false)
        val polish = prefs.getBoolean(KEY_POLISH, false)

        val species = Roster.all.random()
        val card = RatEntity(
            artKey = species.artKey,
            power = if (mutagen) (6..10).random() else (1..5).random(),
            toughness = if (mutagen) (6..10).random() else (1..5).random(),
            name = species.name,
            shiny = polish || (1..10).random() == 1
        )

        // Consumables are spent by the hatch they applied to.
        editor.putBoolean(KEY_MUTAGEN, false).putBoolean(KEY_POLISH, false)
        return card
    }

    /** Returns (rewardPaid, failed). Both are zero/false when no bounty resolved. */
    private fun resolveBounty(
        prefs: SharedPreferences,
        editor: SharedPreferences.Editor,
        totalSteps: Float
    ): Pair<Int, Boolean> {
        if (!prefs.getBoolean(KEY_BOUNTY_ACTIVE, false)) return 0 to false

        val now = System.currentTimeMillis()
        if (now > prefs.getLong(KEY_BOUNTY_END, 0L)) {
            editor.putBoolean(KEY_BOUNTY_ACTIVE, false)
            return 0 to true
        }

        if (totalSteps >= prefs.getFloat(KEY_BOUNTY_TARGET, Float.MAX_VALUE)) {
            val reward = prefs.getInt(KEY_BOUNTY_REWARD, 0)
            editor.putBoolean(KEY_BOUNTY_ACTIVE, false)
            editor.putInt(KEY_SCRAP, scrapOf(prefs) + reward)
            return reward to false
        }

        return 0 to false
    }
}
