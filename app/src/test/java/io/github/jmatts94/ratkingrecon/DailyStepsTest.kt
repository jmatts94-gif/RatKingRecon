package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Calendar

/**
 * The daily step bucket, and the line between it and the totals it sits next to.
 *
 * The reason this is worth testing at all is that the game now keeps three
 * different step counts with three different lifetimes, and only one of them is
 * allowed to go backwards. A daily reset that took the lifetime total or the
 * contract baseline with it would cost the player Achievements progress and a
 * running contract, so the separation is asserted here rather than trusted.
 */
class DailyStepsTest {

    /** Local wall-clock milliseconds, so the day boundary is the real one. */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.set(year, month, day, hour, minute, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private val lateEvening = at(2026, Calendar.MARCH, 14, 23, 59)
    private val justAfterMidnight = at(2026, Calendar.MARCH, 15, 0, 1)
    private val sameDayMorning = at(2026, Calendar.MARCH, 14, 7, 30)

    /** Banks one batch of steps the way GameEngine does: add, then commit. */
    private fun walk(prefs: FakePrefs, gained: Int, now: Long): Int {
        val editor = prefs.edit()
        val total = DailySteps.add(prefs, editor, gained, now)
        editor.apply()
        return total
    }

    @Test
    fun `steps accumulate across batches within one day`() {
        val prefs = FakePrefs()

        walk(prefs, 120, sameDayMorning)
        walk(prefs, 120, sameDayMorning)
        walk(prefs, 80, lateEvening)

        assertEquals(320, DailySteps.today(prefs, lateEvening))
    }

    @Test
    fun `a new day starts the count again from zero`() {
        val prefs = FakePrefs()

        walk(prefs, 5_000, lateEvening)
        assertEquals(5_000, DailySteps.today(prefs, lateEvening))

        val afterMidnight = walk(prefs, 30, justAfterMidnight)

        assertEquals("the new day should not inherit yesterday's steps", 30, afterMidnight)
        assertEquals(30, DailySteps.today(prefs, justAfterMidnight))
    }

    @Test
    fun `today reads zero once the date has turned over, without being written to`() {
        val prefs = FakePrefs()

        walk(prefs, 4_321, lateEvening)

        // No walking has happened yet on the new day, so nothing has had a chance
        // to reset anything - the rollover has to come from the read itself.
        assertEquals(0, DailySteps.today(prefs, justAfterMidnight))
    }

    @Test
    fun `the day stamp splits at local midnight and holds within a day`() {
        assertNotEquals(
            DailySteps.dayStamp(lateEvening),
            DailySteps.dayStamp(justAfterMidnight)
        )
        assertEquals(
            DailySteps.dayStamp(sameDayMorning),
            DailySteps.dayStamp(lateEvening)
        )
    }

    /**
     * The one that matters most: walking banks the same steps into both counts,
     * and only the daily one is ever allowed to drop.
     */
    @Test
    fun `a daily rollover leaves the lifetime total and the contract baseline alone`() {
        val prefs = FakePrefs()
        val dao = FakeRatDao()

        // First reading only sets the baseline; the second banks 40 steps.
        GameEngine.onSteps(dao, prefs, 0f)
        GameEngine.onSteps(dao, prefs, 40f)

        assertEquals(40L, GameEngine.lifetimeStepsOf(prefs))
        assertEquals(40f, GameEngine.totalStepsOf(prefs), 0.001f)

        // Force the stored day to be stale, exactly as an overnight gap would.
        prefs.edit().putInt(DailySteps.KEY_DAY, DailySteps.dayStamp() - 1).apply()

        assertEquals("the daily bucket should have emptied", 0, DailySteps.today(prefs))
        assertEquals(
            "the lifetime total must survive the daily reset",
            40L,
            GameEngine.lifetimeStepsOf(prefs)
        )
        assertEquals(
            "the sensor total contracts measure against must survive it too",
            40f,
            GameEngine.totalStepsOf(prefs),
            0.001f
        )
    }

    @Test
    fun `walking banks the same steps into the daily count and the lifetime total`() {
        val prefs = FakePrefs()
        val dao = FakeRatDao()

        GameEngine.onSteps(dao, prefs, 0f)
        val outcome = GameEngine.onSteps(dao, prefs, 25f)

        assertEquals(25, outcome.stepsToday)
        assertEquals(25, DailySteps.today(prefs))
        assertEquals(25L, GameEngine.lifetimeStepsOf(prefs))
    }

    // ---- Worn Cog --------------------------------------------------------------

    @Test
    fun `no cog until the first threshold is crossed`() {
        assertEquals(0, DailySteps.wornCogsEarnedBetween(0, DailySteps.STEPS_PER_WORN_COG - 1))
    }

    @Test
    fun `exactly one cog on crossing the threshold`() {
        assertEquals(1, DailySteps.wornCogsEarnedBetween(0, DailySteps.STEPS_PER_WORN_COG))
    }

    @Test
    fun `a batch big enough to cross several thresholds earns several cogs`() {
        assertEquals(3, DailySteps.wornCogsEarnedBetween(0, DailySteps.STEPS_PER_WORN_COG * 3))
    }

    @Test
    fun `already-passed thresholds do not re-earn a cog`() {
        assertEquals(0, DailySteps.wornCogsEarnedBetween(DailySteps.STEPS_PER_WORN_COG, DailySteps.STEPS_PER_WORN_COG + 5))
    }

    @Test
    fun `walking a big batch through GameEngine grants Worn Cog but never touches lifetime steps or Milestones`() {
        val prefs = FakePrefs()
        val dao = FakeRatDao()
        val cog = Relics.byId("worn_cog")!!

        GameEngine.onSteps(dao, prefs, 0f)
        val before = GameEngine.lifetimeStepsOf(prefs)
        GameEngine.onSteps(dao, prefs, DailySteps.STEPS_PER_WORN_COG.toFloat())

        assertEquals(1, Relics.countOf(prefs, cog))
        // Lifetime steps still banked the walk itself - Worn Cog rides on
        // top of that, it does not replace or inflate it.
        assertEquals(before + DailySteps.STEPS_PER_WORN_COG, GameEngine.lifetimeStepsOf(prefs))
        // The milestone this exact total could otherwise brush against
        // (steps_1m, now 100,000) must still read as unmet unless the walk
        // itself actually reached it - Worn Cog's own grant must not have
        // nudged it.
        assertEquals(
            DailySteps.STEPS_PER_WORN_COG.toLong() >= 100_000L,
            Milestones.isEarned(prefs, Milestones.steps.first { it.id == "steps_1m" })
        )
    }

    // ---- Trail Rations ----------------------------------------------------------

    @Test
    fun `Trail Rations doubles the next steps batch's banked EXP, then clears itself`() {
        val prefs = FakePrefs()
        val dao = FakeRatDao()

        GameEngine.onSteps(dao, prefs, 0f)
        prefs.edit().putBoolean(ShopEffects.KEY_TRAIL_RATIONS, true).apply()

        GameEngine.onSteps(dao, prefs, 40f)

        assertEquals(80, GameEngine.expOf(prefs))
        assertFalse("spent by the batch it doubled", ShopEffects.trailRationsArmed(prefs))
    }

    @Test
    fun `a batch with nothing armed banks EXP at the ordinary rate`() {
        val prefs = FakePrefs()
        val dao = FakeRatDao()

        GameEngine.onSteps(dao, prefs, 0f)
        GameEngine.onSteps(dao, prefs, 40f)

        assertEquals(40, GameEngine.expOf(prefs))
    }
}
