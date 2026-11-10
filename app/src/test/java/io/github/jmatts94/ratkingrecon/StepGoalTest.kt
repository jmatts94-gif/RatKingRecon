package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Calendar

class StepGoalTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long {
        val calendar = Calendar.getInstance()
        calendar.set(year, month, day, hour, 0, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private val tuesday = at(2026, Calendar.NOVEMBER, 10)

    @Test
    fun `a new save starts on 6,000 and ignores goals off the list`() {
        val prefs = FakePrefs()
        assertEquals(6_000, StepGoal.goalOf(prefs))
        prefs.values[StepGoal.KEY_GOAL] = 150
        assertEquals(6_000, StepGoal.goalOf(prefs))
        StepGoal.setGoal(prefs, 500)
        assertEquals("a goal below the list is refused", 6_000, StepGoal.goalOf(prefs))
        StepGoal.setGoal(prefs, 9_000)
        assertEquals(9_000, StepGoal.goalOf(prefs))
    }

    @Test
    fun `reaching the goal pays once a day`() {
        val prefs = FakePrefs()
        assertEquals(0, StepGoal.advance(prefs, 5_999, tuesday))
        assertEquals(StepGoal.REWARD_SCRAP, StepGoal.advance(prefs, 6_000, tuesday))
        assertEquals(StepGoal.REWARD_SCRAP, GameEngine.scrapOf(prefs))
        assertEquals(0, StepGoal.advance(prefs, 9_000, tuesday))

        // Lowering the goal later the same day cannot pay a second time.
        StepGoal.setGoal(prefs, 2_000)
        assertEquals(0, StepGoal.advance(prefs, 9_000, tuesday))

        // A new day pays again.
        assertEquals(StepGoal.REWARD_SCRAP, StepGoal.advance(prefs, 2_500, tuesday + 86_400_000L))
        assertEquals(StepGoal.REWARD_SCRAP * 2, GameEngine.scrapOf(prefs))
    }

    @Test
    fun `days met counts only days at or over the goal`() {
        val days = listOf(0, 6_000, 5_999, 12_000).mapIndexed { i, s -> StepHistory.Day(i.toLong(), i, s) }
        assertEquals(2, StepGoal.daysMet(days, 6_000))
    }

    @Test
    fun `the Sunday alarm lands on the next Sunday at 6pm`() {
        val next = Calendar.getInstance().apply { timeInMillis = WeeklySummary.nextSundayEvening(tuesday) }
        assertEquals(Calendar.SUNDAY, next.get(Calendar.DAY_OF_WEEK))
        assertEquals(15, next.get(Calendar.DAY_OF_MONTH))
        assertEquals(18, next.get(Calendar.HOUR_OF_DAY))

        // Sunday at 7pm is past this week's slot, so it books the following Sunday.
        val lateSunday = at(2026, Calendar.NOVEMBER, 15, 19)
        val after = Calendar.getInstance().apply { timeInMillis = WeeklySummary.nextSundayEvening(lateSunday) }
        assertEquals(22, after.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `the summary is sent once, skips empty weeks, and only cheers a real record`() {
        val sunday = at(2026, Calendar.NOVEMBER, 15, 18)
        val prefs = FakePrefs()
        assertNull("no steps, nothing to say", WeeklySummary.prepare(prefs, sunday))

        prefs.values[DailySteps.KEY_DAY] = DailySteps.dayStamp(sunday)
        prefs.values[DailySteps.KEY_STEPS] = 7_000
        val first = WeeklySummary.prepare(prefs, sunday)!!
        assertEquals(7_000, first.total)
        assertEquals(1, first.goalDays)
        assertFalse("the first week has nothing to beat", first.bestYet)
        assertNull("already sent today", WeeklySummary.prepare(prefs, sunday))

        val nextSunday = sunday + 7 * 86_400_000L
        prefs.values[DailySteps.KEY_DAY] = DailySteps.dayStamp(nextSunday)
        prefs.values[DailySteps.KEY_STEPS] = 9_000
        assertTrue(WeeklySummary.prepare(prefs, nextSunday)!!.bestYet)
    }

    @Test
    fun `the summary respects its switch`() {
        val prefs = FakePrefs()
        prefs.values[WeeklySummary.KEY_ENABLED] = false
        prefs.values[DailySteps.KEY_DAY] = DailySteps.dayStamp(tuesday)
        prefs.values[DailySteps.KEY_STEPS] = 7_000
        assertNull(WeeklySummary.prepare(prefs, tuesday))
    }
}
