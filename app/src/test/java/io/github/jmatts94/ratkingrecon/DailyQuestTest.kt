package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The streak bonus a Scrap-paying day adds on top of its own roll.
 *
 * [DailyQuest.rollReward] itself is private and half random (Scrap or a
 * relic, an even split), so it is not worth pinning down here - this covers
 * the one new piece of arithmetic it leans on instead.
 */
class DailyQuestTest {

    @Test
    fun `no streak means no bonus`() {
        assertEquals(0, DailyQuest.streakBonusFor(0))
    }

    @Test
    fun `the bonus grows two Scrap per day of streak`() {
        assertEquals(2, DailyQuest.streakBonusFor(1))
        assertEquals(10, DailyQuest.streakBonusFor(5))
        assertEquals(20, DailyQuest.streakBonusFor(10))
    }

    @Test
    fun `the bonus caps at 30, reached by day 15`() {
        assertEquals(30, DailyQuest.streakBonusFor(15))
        assertEquals(
            "a streak far past the cap must not keep climbing",
            30,
            DailyQuest.streakBonusFor(100)
        )
    }
}
