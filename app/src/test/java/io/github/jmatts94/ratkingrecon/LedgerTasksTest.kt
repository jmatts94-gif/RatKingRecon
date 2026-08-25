package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * The Ledger board's refresh rules.
 *
 * Two of these are the ones worth protecting. A task the player has finished but
 * not yet claimed must survive the daily pass, or the reward is taken back
 * before it is banked; and claiming a task must refresh that slot, or the board
 * goes on advertising a job that has already been done until the next calendar
 * day. Both used to live inside an Activity where neither could be asserted.
 */
class LedgerTasksTest {

    /** Local midnight, matching the board's own boundary. Same convention as DailyStepsTest. */
    private fun localMidnight(year: Int, month: Int, day: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.set(year, month, day, 0, 0, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    // January, away from any DST transition in either hemisphere, so dayOne + 23h
    // never accidentally crosses into dayTwo on a machine in a DST-observing zone.
    private val dayOne = localMidnight(2026, Calendar.JANUARY, 12)
    private val dayTwo = localMidnight(2026, Calendar.JANUARY, 13)

    private fun startRunning(prefs: FakePrefs, tier: LedgerTaskTier) {
        prefs.edit().putBoolean(LedgerTasks.activeKey(tier.id), true).apply()
    }

    // ---- rolling -------------------------------------------------------------

    @Test
    fun `every tier rolls inside its own bands`() {
        repeat(200) {
            for (tier in LedgerTasks.all) {
                val offer = tier.roll()
                assertTrue(
                    "${tier.id} requirement was ${offer.requirement}",
                    offer.requirement in tier.requirements
                )
                assertTrue("${tier.id} reward was ${offer.reward}", offer.reward in tier.rewards)
                assertTrue("${tier.id} rolled a blank title", offer.title.isNotBlank())
            }
        }
    }

    @Test
    fun `the shiny task always asks for exactly one`() {
        repeat(50) {
            assertEquals(1, LedgerTasks.M3.roll().requirement)
        }
    }

    /** The reward line prints whole hours, so the tiers have to divide cleanly. */
    @Test
    fun `tier durations are whole hours`() {
        assertEquals(2, LedgerTasks.M1.hours)
        assertEquals(8, LedgerTasks.M2.hours)
        assertEquals(24, LedgerTasks.M3.hours)
    }

    @Test
    fun `the three slots keep the save keys they shipped with`() {
        assertEquals(listOf("M1", "M2", "M3"), LedgerTasks.all.map { it.id })
        assertEquals("M1_TITLE", LedgerTasks.titleKey("M1"))
        assertEquals("M2_REQ", LedgerTasks.requirementKey("M2"))
        assertEquals("M3_REWARD", LedgerTasks.rewardKey("M3"))
        assertEquals("M1_ACTIVE", LedgerTasks.activeKey("M1"))
        assertEquals("M2_END_TIME", LedgerTasks.endTimeKey("M2"))
    }

    // ---- storing -------------------------------------------------------------

    @Test
    fun `a stored offer reads back as it was written`() {
        val prefs = FakePrefs()
        val offer = LedgerTaskOffer(title = "The Iron Sewer", requirement = 3, reward = 22)

        val editor = prefs.edit()
        LedgerTasks.store(editor, LedgerTasks.M1, offer)
        editor.apply()

        assertEquals(offer, LedgerTasks.stored(prefs, LedgerTasks.M1))
    }

    // ---- the daily pass ------------------------------------------------------

    @Test
    fun `the first pass of a new day fills every idle slot`() {
        val prefs = FakePrefs()

        assertTrue(LedgerTasks.rerollForNewDay(prefs, dayOne))

        for (tier in LedgerTasks.all) {
            val offer = LedgerTasks.stored(prefs, tier)
            assertTrue("${tier.id} was left blank", offer.title.isNotBlank())
            assertTrue("${tier.id} reward was ${offer.reward}", offer.reward in tier.rewards)
        }
    }

    @Test
    fun `a second pass on the same day changes nothing`() {
        val prefs = FakePrefs()
        LedgerTasks.rerollForNewDay(prefs, dayOne)
        val before = LedgerTasks.all.map { LedgerTasks.stored(prefs, it) }

        assertFalse("the same day should not reroll again", LedgerTasks.rerollForNewDay(prefs, dayOne))

        assertEquals(before, LedgerTasks.all.map { LedgerTasks.stored(prefs, it) })
    }

    @Test
    fun `a running task survives the daily pass with its reward intact`() {
        val prefs = FakePrefs()
        LedgerTasks.rerollForNewDay(prefs, dayOne)

        startRunning(prefs, LedgerTasks.M2)
        val running = LedgerTasks.stored(prefs, LedgerTasks.M2)

        assertTrue(LedgerTasks.rerollForNewDay(prefs, dayTwo))

        assertEquals(
            "an unclaimed reward must not be rerolled away",
            running,
            LedgerTasks.stored(prefs, LedgerTasks.M2)
        )
    }

    @Test
    fun `the day marker advances even when every slot was running`() {
        val prefs = FakePrefs()
        LedgerTasks.all.forEach { startRunning(prefs, it) }

        assertTrue(LedgerTasks.rerollForNewDay(prefs, dayOne))
        assertEquals(
            LedgerTasks.dayIndexOf(dayOne),
            prefs.getInt(LedgerTasks.KEY_LAST_ROLL_DAY, -1)
        )

        // Having recorded the day, the board must not reroll again later the
        // same day just because a slot has since been claimed.
        assertFalse(LedgerTasks.rerollForNewDay(prefs, dayOne))
    }

    // ---- the claim reroll ----------------------------------------------------

    @Test
    fun `rerolling a slot replaces what was on it`() {
        val prefs = FakePrefs()
        val editor = prefs.edit()
        LedgerTasks.store(
            editor,
            LedgerTasks.M3,
            LedgerTaskOffer(title = "The Flooded Crater", requirement = 1, reward = 101)
        )
        editor.apply()

        // The bands are wide, so a single reroll can legitimately land on the
        // same numbers. Rerolling until something moves proves it is rolling
        // rather than that any one roll differs.
        var moved = false
        repeat(40) {
            val next = prefs.edit()
            LedgerTasks.reroll(next, LedgerTasks.M3)
            next.apply()
            val now = LedgerTasks.stored(prefs, LedgerTasks.M3)
            if (now.title != "The Flooded Crater" || now.reward != 101) moved = true
        }
        assertTrue("40 rerolls never changed the slot", moved)

        val finally = LedgerTasks.stored(prefs, LedgerTasks.M3)
        assertTrue(finally.reward in LedgerTasks.M3.rewards)
        assertEquals(1, finally.requirement)
    }

    /**
     * The bug this whole extraction came from: a claimed task kept the name and
     * payout it was completed with until the next calendar day.
     */
    @Test
    fun `a claimed slot is refreshed without waiting for the next day`() {
        val prefs = FakePrefs()
        LedgerTasks.rerollForNewDay(prefs, dayOne)

        // A sentinel the tier can never roll, so "did this reroll?" has a
        // deterministic answer - comparing against a real previous roll would
        // flake whenever the new one landed on the same numbers.
        val editor = prefs.edit()
        LedgerTasks.store(
            editor,
            LedgerTasks.M1,
            LedgerTaskOffer(title = "COMPLETED JOB", requirement = 99, reward = 9_999)
        )
        editor.putBoolean(LedgerTasks.activeKey(LedgerTasks.M1.id), true)
        editor.apply()

        // What claim() does: bank the reward, mark the slot idle, reroll it.
        val claim = prefs.edit()
        claim.putBoolean(LedgerTasks.activeKey(LedgerTasks.M1.id), false)
        LedgerTasks.reroll(claim, LedgerTasks.M1)
        claim.apply()

        // Still the same day, so the daily pass is no help here and cannot be
        // what refreshed the slot.
        assertFalse(LedgerTasks.rerollForNewDay(prefs, dayOne))

        val offer = LedgerTasks.stored(prefs, LedgerTasks.M1)
        assertFalse("the slot should be startable again", LedgerTasks.isRunning(prefs, LedgerTasks.M1))
        assertNotEquals("the finished job is still on the board", "COMPLETED JOB", offer.title)
        assertTrue("reward was ${offer.reward}", offer.reward in LedgerTasks.M1.rewards)
        assertTrue("requirement was ${offer.requirement}", offer.requirement in LedgerTasks.M1.requirements)
    }

    /**
     * The bug this pins the fix for: the board used to turn over on a fixed
     * twenty-four hours from the epoch, a UTC boundary no player outside UTC
     * actually lives on. It now turns over at local midnight, the same
     * boundary Steps, the daily quest and the streak already share - a
     * calendar day, not a fixed span, which is what makes it agree with them
     * across a DST change too.
     */
    @Test
    fun `the day index turns over at local midnight, not a fixed twenty four hours`() {
        assertEquals(LedgerTasks.dayIndexOf(dayOne), LedgerTasks.dayIndexOf(dayOne + 23 * 3_600_000L))
        assertNotEquals(LedgerTasks.dayIndexOf(dayOne), LedgerTasks.dayIndexOf(dayTwo))
    }
}
