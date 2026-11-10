package io.github.jmatts94.ratkingrecon

import android.app.Activity
import android.content.SharedPreferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.NumberFormat

/**
 * The player's own daily step target, and a small reward for reaching it.
 *
 * For the many players who mostly open the game to check their steps: a number
 * they chose, a ring that fills, and a tick when it is done. Paid once a day
 * however often the goal is changed, so lowering it cannot farm Scrap.
 */
object StepGoal {

    const val KEY_GOAL = "STEP_GOAL"
    const val KEY_PAID_DAY = "STEP_GOAL_PAID_DAY"

    const val DEFAULT_GOAL = 6_000
    const val REWARD_SCRAP = 25

    /** 2,000 to 20,000 in steps of 1,000. */
    val OPTIONS: List<Int> = (2_000..20_000 step 1_000).toList()

    fun goalOf(prefs: SharedPreferences): Int {
        val stored = prefs.getInt(KEY_GOAL, DEFAULT_GOAL)
        // Anything off the list (an edited or very old save) falls back safely.
        return if (stored in OPTIONS) stored else DEFAULT_GOAL
    }

    fun setGoal(prefs: SharedPreferences, goal: Int) {
        if (goal in OPTIONS) prefs.edit().putInt(KEY_GOAL, goal).apply()
    }

    fun isMet(steps: Int, goal: Int): Boolean = steps >= goal

    /**
     * Pays the reward the first time today's steps reach the goal. Returns the
     * Scrap paid, or zero.
     *
     * Its own commit, after the step batch is saved, so nothing else in that
     * batch can write over the new Scrap total.
     */
    fun advance(prefs: SharedPreferences, stepsToday: Int, now: Long = System.currentTimeMillis()): Int {
        val today = DailySteps.dayStamp(now)
        if (!isMet(stepsToday, goalOf(prefs))) return 0
        if (prefs.getInt(KEY_PAID_DAY, 0) == today) return 0

        prefs.edit()
            .putInt(KEY_PAID_DAY, today)
            .putInt(GameEngine.KEY_SCRAP, GameEngine.scrapOf(prefs) + REWARD_SCRAP)
            .apply()
        return REWARD_SCRAP
    }

    /** Days in [days] that reached the current goal. */
    fun daysMet(days: List<StepHistory.Day>, goal: Int): Int = days.count { isMet(it.steps, goal) }

    fun format(steps: Int): String = NumberFormat.getInstance().format(steps)

    /** A simple list to pick from. Calls [onChanged] after a new goal is saved. */
    fun showPicker(activity: Activity, prefs: SharedPreferences, onChanged: () -> Unit) {
        val current = goalOf(prefs)
        val labels = OPTIONS.map { activity.getString(R.string.goal_option, format(it)) }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.goal_picker_title)
            .setSingleChoiceItems(labels, OPTIONS.indexOf(current)) { dialog, which ->
                setGoal(prefs, OPTIONS[which])
                dialog.dismiss()
                onChanged()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}
