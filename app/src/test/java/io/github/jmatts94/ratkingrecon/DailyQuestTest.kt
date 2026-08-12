package io.github.jmatts94.ratkingrecon

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The daily quest, and the streak rule that makes one missed day survivable.
 *
 * The grace rule is the part worth pinning: it has three states rather than two,
 * and every way of getting it wrong either ends a streak that should have lived
 * or keeps one that should have ended.
 */
class DailyQuestTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 9): Long {
        val c = Calendar.getInstance()
        c.set(year, month, day, hour, 0, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private val day1 = at(2026, Calendar.MAY, 4)
    private val day2 = at(2026, Calendar.MAY, 5)
    private val day3 = at(2026, Calendar.MAY, 6)
    private val day4 = at(2026, Calendar.MAY, 7)

    /** Finishes whatever today asks for, whatever type it rolled. */
    private fun completeToday(prefs: FakePrefs, now: Long) {
        DailyQuest.ensureToday(prefs, now)
        when (DailyQuest.type(prefs)) {
            QuestType.STEPS -> {
                val editor = prefs.edit()
                DailySteps.add(prefs, editor, DailyQuest.target(prefs), now)
                editor.apply()
                DailyQuest.record(prefs, QuestType.STEPS, now)
            }
            QuestType.WIN_FIGHT -> DailyQuest.record(prefs, QuestType.WIN_FIGHT, now)
            QuestType.HATCH -> DailyQuest.record(prefs, QuestType.HATCH, now)
        }
    }

    // ---- rolling -------------------------------------------------------------

    @Test
    fun `a quest is rolled once per day and not re-rolled within it`() {
        val prefs = FakePrefs()
        DailyQuest.ensureToday(prefs, day1)

        val type = DailyQuest.type(prefs)
        val target = DailyQuest.target(prefs)

        repeat(10) { DailyQuest.ensureToday(prefs, day1 + it * 60_000L) }

        assertEquals(type, DailyQuest.type(prefs))
        assertEquals(target, DailyQuest.target(prefs))
    }

    @Test
    fun `a step target lands inside the advertised band`() {
        repeat(200) {
            val prefs = FakePrefs()
            DailyQuest.ensureToday(prefs, day1)
            if (DailyQuest.type(prefs) == QuestType.STEPS) {
                assertTrue(
                    "target was ${DailyQuest.target(prefs)}",
                    DailyQuest.target(prefs) in DailyQuest.STEP_TARGET
                )
            }
        }
    }

    /**
     * A day asking for a fight is not advanced by a hatch, and vice versa.
     *
     * Repeated because the day's type is random: over this many rolls every
     * type is exercised, and each one is checked against the two events it is
     * not asking for.
     */
    @Test
    fun `a quest only counts the event it asked for`() {
        repeat(60) {
            val prefs = FakePrefs()
            DailyQuest.ensureToday(prefs, day1)

            val asked = DailyQuest.type(prefs)
            for (other in QuestType.entries.filter { it != asked }) {
                DailyQuest.record(prefs, other, day1)
            }

            assertFalse(
                "a $asked quest was finished by something else",
                DailyQuest.isComplete(prefs)
            )
            assertEquals(0, Streak.count(prefs))
        }
    }

    @Test
    fun `a steps quest is not finished by a short walk`() {
        repeat(60) {
            val prefs = FakePrefs()
            DailyQuest.ensureToday(prefs, day1)
            if (DailyQuest.type(prefs) != QuestType.STEPS) return@repeat

            val editor = prefs.edit()
            DailySteps.add(prefs, editor, DailyQuest.target(prefs) - 1, day1)
            editor.apply()

            assertFalse(DailyQuest.record(prefs, QuestType.STEPS, day1) != null)
            assertFalse(DailyQuest.isComplete(prefs))
        }
    }

    // ---- streak --------------------------------------------------------------

    @Test
    fun `consecutive completions build the streak`() {
        val prefs = FakePrefs()

        completeToday(prefs, day1)
        assertEquals(1, Streak.count(prefs))

        completeToday(prefs, day2)
        assertEquals(2, Streak.count(prefs))

        completeToday(prefs, day3)
        assertEquals(3, Streak.count(prefs))
    }

    @Test
    fun `finishing twice in one day counts once`() {
        val prefs = FakePrefs()
        completeToday(prefs, day1)
        completeToday(prefs, day1)

        assertEquals(1, Streak.count(prefs))
    }

    /** One missed day is a warning, not an ending. */
    @Test
    fun `a single missed day keeps the streak and raises the flag`() {
        val prefs = FakePrefs()
        completeToday(prefs, day1)
        completeToday(prefs, day2)
        assertEquals(2, Streak.count(prefs))

        // day3 passes untouched; the app is opened on day4.
        DailyQuest.ensureToday(prefs, day4)

        assertEquals("the streak survives one miss", 2, Streak.count(prefs))
        assertTrue(Streak.onGraceDay(prefs))
    }

    @Test
    fun `completing after a missed day continues the streak`() {
        val prefs = FakePrefs()
        completeToday(prefs, day1)
        completeToday(prefs, day2)

        // day3 missed, day4 finished.
        completeToday(prefs, day4)

        assertEquals("continues from where it was", 3, Streak.count(prefs))
        assertFalse("the grace day is spent", Streak.onGraceDay(prefs))
    }

    @Test
    fun `two consecutive missed days reset the streak`() {
        val prefs = FakePrefs()
        completeToday(prefs, day1)
        assertEquals(1, Streak.count(prefs))

        // day2 and day3 both pass; the app is opened on day4.
        DailyQuest.ensureToday(prefs, day4)

        assertEquals(0, Streak.count(prefs))
        assertFalse(Streak.onGraceDay(prefs))
    }

    @Test
    fun `a long absence resets rather than counting as one miss`() {
        val prefs = FakePrefs()
        completeToday(prefs, day1)

        DailyQuest.ensureToday(prefs, at(2026, Calendar.JUNE, 20))

        assertEquals("seven weeks away is not a grace day", 0, Streak.count(prefs))
    }

    @Test
    fun `a first ever launch does not punish the player`() {
        val prefs = FakePrefs()
        DailyQuest.ensureToday(prefs, day1)

        assertEquals(0, Streak.count(prefs))
        assertFalse(Streak.onGraceDay(prefs))
    }

    // ---- reward tiers --------------------------------------------------------

    @Test
    fun `relic eligibility widens with the streak`() {
        assertEquals(listOf(Relics.ALL[0]), DailyQuest.eligibleRelics(0))
        assertEquals(listOf(Relics.ALL[0]), DailyQuest.eligibleRelics(6))

        assertEquals(Relics.ALL.take(3), DailyQuest.eligibleRelics(7))
        assertEquals(Relics.ALL.take(3), DailyQuest.eligibleRelics(13))

        assertEquals(Relics.ALL, DailyQuest.eligibleRelics(14))
        assertEquals(Relics.ALL, DailyQuest.eligibleRelics(400))
    }

    @Test
    fun `the top relic is only ever a fourteen day prize`() {
        val wrench = Relics.ALL.last()
        for (streak in 0..13) {
            assertFalse(
                "streak $streak should not reach ${wrench.id}",
                wrench in DailyQuest.eligibleRelics(streak)
            )
        }
        assertTrue(wrench in DailyQuest.eligibleRelics(14))
    }

    @Test
    fun `finishing a quest pays exactly one reward`() {
        val prefs = FakePrefs()
        DailyQuest.ensureToday(prefs, day1)

        val scrapBefore = GameEngine.scrapOf(prefs)
        val relicsBefore = Relics.total(prefs)

        completeToday(prefs, day1)

        val gainedScrap = GameEngine.scrapOf(prefs) - scrapBefore
        val gainedRelics = Relics.total(prefs) - relicsBefore

        assertTrue("paid nothing", gainedScrap > 0 || gainedRelics > 0)
        assertFalse("paid twice", gainedScrap > 0 && gainedRelics > 0)
        if (gainedScrap > 0) assertTrue(gainedScrap in DailyQuest.SCRAP_REWARD)
        if (gainedRelics > 0) assertEquals(1, gainedRelics)
    }
}
