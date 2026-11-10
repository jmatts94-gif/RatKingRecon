package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class StepHistoryTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long {
        val calendar = Calendar.getInstance()
        calendar.set(year, month, day, hour, 0, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun walk(prefs: FakePrefs, steps: Int, now: Long) {
        val editor = prefs.edit()
        DailySteps.add(prefs, editor, steps, now)
        editor.apply()
    }

    @Test
    fun `a finished day is banked when the date turns over`() {
        val prefs = FakePrefs()
        walk(prefs, 4_200, at(2026, Calendar.MARCH, 14))
        walk(prefs, 100, at(2026, Calendar.MARCH, 15))

        val days = StepHistory.lastDays(prefs, at(2026, Calendar.MARCH, 15))
        assertEquals(StepHistory.DAYS_SHOWN, days.size)
        assertEquals(4_200, days[5].steps)
        assertEquals(100, days[6].steps)
    }

    @Test
    fun `yesterday still shows before the first step of the morning`() {
        val prefs = FakePrefs()
        walk(prefs, 3_000, at(2026, Calendar.MARCH, 14))

        val days = StepHistory.lastDays(prefs, at(2026, Calendar.MARCH, 15, 7))
        assertEquals(3_000, days[5].steps)
        assertEquals(0, days[6].steps)
    }

    @Test
    fun `missed days show as zero, not as the wrong day`() {
        val prefs = FakePrefs()
        walk(prefs, 1_000, at(2026, Calendar.MARCH, 10))
        walk(prefs, 2_000, at(2026, Calendar.MARCH, 15))

        val days = StepHistory.lastDays(prefs, at(2026, Calendar.MARCH, 15))
        assertEquals(listOf(0, 1_000, 0, 0, 0, 0, 2_000), days.map { it.steps })
    }

    @Test
    fun `the chart crosses a clock change and a new year without gaps`() {
        // UK clocks go forward on 29 March 2026.
        val spring = StepHistory.lastDays(FakePrefs(), at(2026, Calendar.MARCH, 31))
        assertEquals(StepHistory.DAYS_SHOWN, spring.map { it.stamp }.toSet().size)

        val newYear = StepHistory.lastDays(FakePrefs(), at(2027, Calendar.JANUARY, 2))
        assertEquals(DailySteps.dayStamp(at(2026, Calendar.DECEMBER, 27)), newYear.first().stamp)
        assertEquals(DailySteps.dayStamp(at(2027, Calendar.JANUARY, 2)), newYear.last().stamp)
    }

    @Test
    fun `only the newest days are kept`() {
        val many = (1..30).associate { (2026_000 + it) to it }
        val kept = StepHistory.decode(StepHistory.encode(many))
        assertEquals(StepHistory.DAYS_SHOWN, kept.size)
        assertEquals(30, kept[2026_030])
        assertTrue(2026_023 !in kept)
    }

    @Test
    fun `a damaged imported string is ignored rather than crashing`() {
        val decoded = StepHistory.decode("2026100:50,rubbish,2026101:,:9,2026102:-4,2026103:7")
        assertEquals(mapOf(2026100 to 50, 2026103 to 7), decoded)
        assertTrue(StepHistory.decode(null).isEmpty())
    }

    @Test
    fun `a day with no steps stores nothing`() {
        val prefs = FakePrefs()
        val editor = prefs.edit()
        StepHistory.bank(prefs, editor, 2026100, 0)
        editor.apply()
        assertEquals(null, prefs.getString(StepHistory.KEY_HISTORY, null))
    }

    @Test
    fun `labels stay short enough for a narrow bar`() {
        assertEquals("0", StepHistory.compact(0))
        assertEquals("999", StepHistory.compact(999))
        assertEquals("1.0k", StepHistory.compact(1_000))
        assertEquals("12.5k", StepHistory.compact(12_500))
    }
}
