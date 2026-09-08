package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import java.util.Calendar

/**
 * Steps walked today, on the player's own clock.
 *
 * A third step total, and deliberately not a replacement for either of the two
 * already in [GameEngine]. [GameEngine.KEY_TOTAL_STEPS] holds the sensor's own
 * since-boot reading, which contracts measure against; [GameEngine.KEY_LIFETIME_STEPS]
 * only ever climbs and is what the Achievements milestones latch on. This one
 * returns to zero every midnight and is presentation only - nothing in the game
 * reads it to decide anything, so the daily reset cannot cost a player progress.
 *
 * Persisted rather than counted in memory, which is the whole point: the home
 * screen used to show steps since the Activity was last created, so backgrounding
 * the app reset the number on screen even though the walk was banked correctly.
 */
object DailySteps {

    const val KEY_STEPS = "DAILY_STEPS"
    const val KEY_DAY = "DAILY_STEPS_DAY"

    /**
     * A local calendar day, as one number that can be compared for equality.
     *
     * Local rather than UTC because the boundary the player experiences is their
     * own midnight. Built from Calendar rather than java.time because minSdk is
     * 24 and the project does not enable core library desugaring.
     *
     * Year and day-of-year are combined rather than hashed: day-of-year never
     * exceeds 366, so the multiplier keeps the two fields from colliding.
     */
    fun dayStamp(millis: Long = System.currentTimeMillis()): Int {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = millis
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * The instant the local day containing [now] began.
     *
     * Lives here because this object already owns what "a day" means to the
     * player, and the daily quest needs the same boundary. [LedgerTasks] shares
     * [dayStamp] itself for the same reason, rather than carrying a boundary of
     * its own.
     */
    fun localMidnight(now: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /**
     * Whole days from one local midnight to another.
     *
     * Rounded rather than divided, because the clocks going forward makes a day
     * 23 hours long and back makes it 25 - either of which truncates to the
     * wrong answer, and a streak that breaks on the last Sunday in October is a
     * bug nobody would find until October.
     */
    fun daysBetween(from: Long, to: Long): Int =
        Math.round((to - from) / 86_400_000.0).toInt()

    /**
     * Today's count, or zero once the date has turned over.
     *
     * Rolls over on read rather than on a timer, so a phone that walked nothing
     * overnight still shows zero in the morning without anything having had to
     * wake up at midnight to reset it.
     */
    fun today(prefs: SharedPreferences, now: Long = System.currentTimeMillis()): Int =
        if (prefs.getInt(KEY_DAY, 0) == dayStamp(now)) prefs.getInt(KEY_STEPS, 0) else 0

    /**
     * Folds [gained] into today's count and returns the new total.
     *
     * Writes through the caller's [editor] so this lands in the same commit as
     * the rest of the step bookkeeping - a partial write here would leave the
     * daily count disagreeing with the EXP earned by the very same steps.
     */
    fun add(
        prefs: SharedPreferences,
        editor: SharedPreferences.Editor,
        gained: Int,
        now: Long = System.currentTimeMillis()
    ): Int {
        val updated = today(prefs, now) + gained
        editor.putInt(KEY_DAY, dayStamp(now)).putInt(KEY_STEPS, updated)
        return updated
    }

    /** Steps between one Worn Cog drop and the next - see [wornCogsEarnedBetween]. */
    const val STEPS_PER_WORN_COG = 2_000

    /**
     * How many Worn Cog thresholds [before]..[after] crossed - see
     * [GameEngine.onSteps], which calls this around the same [add] call
     * above with today's total before and after this batch.
     *
     * Derived from the daily total alone rather than a running counter of
     * its own: [today] already rolls over to zero at this object's own day
     * boundary, so a threshold count read straight off it resets for free
     * with nothing extra to keep in step. A batch big enough to cross more
     * than one threshold in one call - the same kind of jump a debug
     * "force encounter"-style tool might produce - correctly earns more
     * than one Worn Cog rather than being capped at one per call.
     *
     * Deliberately independent of [GameEngine.KEY_LIFETIME_STEPS] and
     * [Milestones] - this relic is earned from a day's own walking, not
     * from the lifetime total those badges are the player's own number on.
     */
    fun wornCogsEarnedBetween(before: Int, after: Int): Int =
        after / STEPS_PER_WORN_COG - before / STEPS_PER_WORN_COG
}
