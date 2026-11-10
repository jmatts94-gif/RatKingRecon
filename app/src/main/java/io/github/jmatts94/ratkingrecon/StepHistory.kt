package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import java.util.Calendar
import java.util.Locale

/**
 * The last week of walking, for the step chart.
 *
 * Presentation only: nothing in the game reads this to decide anything. A
 * finished day is banked by [DailySteps.add] at the moment the date turns over,
 * and stored as "dayStamp:steps" pairs in one string so it travels in a save
 * export like every other preference.
 */
object StepHistory {

    const val KEY_HISTORY = "STEP_HISTORY_V1"

    const val DAYS_SHOWN = 7

    data class Day(val millis: Long, val stamp: Int, val steps: Int)

    /** Tolerant on purpose: this string can arrive from an imported file. */
    fun decode(raw: String?): Map<Int, Int> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split(",").mapNotNull { pair ->
            val parts = pair.split(":")
            val day = parts.getOrNull(0)?.toIntOrNull()
            val steps = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size == 2 && day != null && steps != null && steps >= 0) day to steps else null
        }.toMap()
    }

    /** Newest [DAYS_SHOWN] days only, so the string cannot grow without limit. */
    fun encode(days: Map<Int, Int>): String =
        days.entries
            .sortedBy { it.key }
            .takeLast(DAYS_SHOWN)
            .joinToString(",") { "${it.key}:${it.value}" }

    /** Records a finished day through the caller's [editor]. Empty days store nothing. */
    fun bank(prefs: SharedPreferences, editor: SharedPreferences.Editor, stamp: Int, steps: Int) {
        if (steps <= 0) return
        val updated = decode(prefs.getString(KEY_HISTORY, null)) + (stamp to steps)
        editor.putString(KEY_HISTORY, encode(updated))
    }

    /**
     * The seven days ending today, oldest first. A day with no walking is zero.
     *
     * The live daily bucket is read too, because it still holds the last day
     * walked until the next step banks it. Without that, yesterday would show
     * as empty every morning until the player's first steps.
     */
    fun lastDays(prefs: SharedPreferences, now: Long = System.currentTimeMillis()): List<Day> {
        val stored = decode(prefs.getString(KEY_HISTORY, null))
        val liveDay = prefs.getInt(DailySteps.KEY_DAY, 0)
        val liveSteps = prefs.getInt(DailySteps.KEY_STEPS, 0)

        return (DAYS_SHOWN - 1 downTo 0).map { daysAgo ->
            // Calendar rather than subtracting milliseconds, so a day when the
            // clocks change cannot land on the wrong date.
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = now
            calendar.add(Calendar.DAY_OF_YEAR, -daysAgo)
            val stamp = DailySteps.dayStamp(calendar.timeInMillis)
            val steps = if (stamp == liveDay) liveSteps else stored[stamp] ?: 0
            Day(calendar.timeInMillis, stamp, steps)
        }
    }

    /** "950", "4.2k": short enough to sit above a narrow bar. */
    fun compact(steps: Int): String =
        if (steps < 1_000) steps.toString()
        else String.format(Locale.UK, "%.1fk", steps / 1_000.0)
}
