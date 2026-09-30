package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Plays whole Arena runs with [AutoResolver] - no items, gear or relics - so
 * the curve itself can be checked. Launch playtest: a 7/6 rat could not
 * reach fight 5, and even a 20/18 rat fell by fight 4.
 */
class ArenaBalanceTest {

    private fun fightsCleared(power: Int, toughness: Int): Int {
        val rat = RatEntity(artKey = "bolt_pic", name = "R", power = power, toughness = toughness, shiny = false)
        var hp: Int? = null
        var fight = 1
        while (fight <= ArenaRun.TOTAL_FIGHTS) {
            val bot = ArenaRun.rustbotFor(fight, rat)
            val encounter = Encounter(
                ratId = 0, botName = "b", botPower = bot.power, botMaxHp = bot.maxHp,
                reward = 1, bossId = ArenaRun.bossIdFor(fight)
            )
            val battle = AutoResolver.resolve(
                encounter.toBattle(
                    rat, startingRatHp = hp,
                    bonusPower = ArenaRun.arenaPowerBonusFor(rat),
                    bonusMaxHp = ArenaRun.arenaMaxHpBonusFor(rat)
                )
            )
            if (battle.outcome != BattleOutcome.PLAYER_WON) break
            hp = min(rat.effectiveMaxHp, battle.ratHp + (rat.effectiveMaxHp * ArenaRun.ARENA_RELIEF_FRACTION).roundToInt())
            fight++
        }
        return fight - 1
    }

    @Test
    fun `an ordinary rat earns the first badge`() {
        for ((p, t) in listOf(5 to 4, 7 to 6, 6 to 7, 9 to 8, 12 to 10)) {
            val cleared = fightsCleared(p, t)
            assertTrue("$p/$t cleared only $cleared", cleared >= 5)
        }
    }

    @Test
    fun `nobody walks the whole run without help`() {
        for ((p, t) in listOf(7 to 6, 20 to 18, 25 to 22)) {
            val cleared = fightsCleared(p, t)
            assertTrue("$p/$t cleared $cleared", cleared < ArenaRun.TOTAL_FIGHTS)
        }
    }

    @Test
    fun `a stronger rat gets further`() {
        assertTrue(fightsCleared(25, 22) > fightsCleared(7, 6))
    }
}
