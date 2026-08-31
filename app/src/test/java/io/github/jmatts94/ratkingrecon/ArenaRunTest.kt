package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Arena's own high-stat bonus, checked directly.
 *
 * [ArenaRun.arenaPowerBonusFor]/[ArenaRun.arenaMaxHpBonusFor] are pure
 * functions of a rat's effective stats, so the threshold and the uncapped
 * per-point scaling are verified here rather than by walking around with a
 * phone.
 */
class ArenaRunTest {

    private fun rat(power: Int, toughness: Int) =
        RatEntity(artKey = "bolt_pic", name = "R", power = power, toughness = toughness, shiny = false)

    @Test
    fun `no bonus at or below the threshold`() {
        assertEquals(0, ArenaRun.arenaPowerBonusFor(rat(power = 15, toughness = 1)))
        assertEquals(0, ArenaRun.arenaMaxHpBonusFor(rat(power = 1, toughness = 15)))
    }

    @Test
    fun `bonus is one point per point of stat above the threshold`() {
        assertEquals(1, ArenaRun.arenaPowerBonusFor(rat(power = 16, toughness = 1)))
        assertEquals(1, ArenaRun.arenaMaxHpBonusFor(rat(power = 1, toughness = 16)))
    }

    @Test
    fun `bonus is uncapped, scaling with however far past the threshold the stat is`() {
        assertEquals(20, ArenaRun.arenaPowerBonusFor(rat(power = 35, toughness = 1)))
        assertEquals(20, ArenaRun.arenaMaxHpBonusFor(rat(power = 1, toughness = 35)))
    }

    @Test
    fun `power and toughness are checked independently`() {
        // High Power, low Toughness: only the Attack bonus should apply.
        val lopsided = rat(power = 22, toughness = 5)
        assertEquals(7, ArenaRun.arenaPowerBonusFor(lopsided))
        assertEquals(0, ArenaRun.arenaMaxHpBonusFor(lopsided))
    }
}
