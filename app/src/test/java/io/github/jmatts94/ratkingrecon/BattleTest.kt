package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The combat rules, checked directly.
 *
 * [Battle] has no Android dependencies and no randomness, so the whole system
 * can be verified here rather than by walking around with a phone.
 */
class BattleTest {

    private fun battle(
        ratPower: Int = 3,
        ratHp: Int = 30,
        botPower: Int = 3,
        botHp: Int = 30
    ) = Battle("Rat", ratPower, ratHp, "Rustbot", botPower, botHp)

    @Test
    fun `attack deals damage equal to power`() {
        val b = battle(ratPower = 4)
        val r = b.advance(BattleAction.ATTACK)
        assertEquals(4, r.damageDealt)
        assertEquals(26, b.botHp)
    }

    @Test
    fun `special deals one and a half times power`() {
        assertEquals(5, battle(ratPower = 3).specialDamage())   // 4.5 rounds to 5
        assertEquals(6, battle(ratPower = 4).specialDamage())
        assertEquals(2, battle(ratPower = 1).specialDamage())   // 1.5 rounds to 2
    }

    @Test
    fun `special is available immediately then locked for the cooldown`() {
        val b = battle()
        assertTrue(b.specialAvailable)

        b.advance(BattleAction.SPECIAL)
        assertFalse("locked the round after use", b.specialAvailable)

        b.advance(BattleAction.ATTACK)
        assertFalse("still locked two rounds after", b.specialAvailable)

        b.advance(BattleAction.ATTACK)
        assertTrue("ready again three rounds later", b.specialAvailable)
    }

    @Test
    fun `requesting special on cooldown falls back to attack`() {
        val b = battle(ratPower = 4)
        b.advance(BattleAction.SPECIAL)
        val r = b.advance(BattleAction.SPECIAL)
        assertEquals(BattleAction.ATTACK, r.action)
        assertEquals(4, r.damageDealt)
    }

    @Test
    fun `defend halves incoming damage and deals none`() {
        val b = battle(botPower = 6)
        val r = b.advance(BattleAction.DEFEND)
        assertEquals(0, r.damageDealt)
        assertEquals(3, r.damageTaken)
        assertEquals(30, b.botHp)
    }

    @Test
    fun `the bot does not strike back if it died this round`() {
        val b = battle(ratPower = 10, botHp = 5, botPower = 7)
        val r = b.advance(BattleAction.ATTACK)
        assertEquals(0, r.damageTaken)
        assertEquals(b.ratMaxHp, b.ratHp)
        assertEquals(BattleOutcome.PLAYER_WON, r.outcome)
    }

    @Test
    fun `player loses when the rat reaches zero`() {
        val b = battle(ratPower = 1, ratHp = 4, botPower = 4, botHp = 100)
        val r = b.advance(BattleAction.ATTACK)
        assertEquals(0, b.ratHp)
        assertEquals(BattleOutcome.PLAYER_LOST, r.outcome)
    }

    // --- the agreed difficulty curve -------------------------------------

    @Test
    fun `ramp runs from forty five percent to parity at level twelve`() {
        assertEquals(0.45, RustbotFactory.rampFor(1), 0.001)
        assertEquals(0.70, RustbotFactory.rampFor(6), 0.001)
        assertEquals(1.00, RustbotFactory.rampFor(12), 0.001)
        assertEquals(1.00, RustbotFactory.rampFor(40), 0.001)
    }

    @Test
    fun `a level one encounter is trivially winnable`() {
        val rat = RatEntity(artKey = "flux_pic", name = "R", power = 3, toughness = 3, shiny = false)
        val bot = RustbotFactory.forEncounter(1, rat)
        assertEquals(1, bot.power)
        assertEquals(10, bot.maxHp)

        val result = AutoResolver.resolve(
            Battle("R", rat.power, rat.maxHp, bot.name, bot.power, bot.maxHp)
        )
        assertEquals(BattleOutcome.PLAYER_WON, result.outcome)
        assertTrue("should barely be scratched", result.ratHp >= rat.maxHp - 5)
    }

    @Test
    fun `an even match at parity is still winnable but close`() {
        val rat = RatEntity(artKey = "flux_pic", name = "R", power = 3, toughness = 3, shiny = false)
        val bot = RustbotFactory.forEncounter(12, rat)
        assertEquals(3, bot.power)
        assertEquals(30, bot.maxHp)

        val result = AutoResolver.resolve(
            Battle("R", rat.power, rat.maxHp, bot.name, bot.power, bot.maxHp)
        )
        assertEquals(BattleOutcome.PLAYER_WON, result.outcome)
        assertTrue("should be a real fight", result.ratHp < rat.maxHp / 2)
    }

    @Test
    fun `scaling protects a weak rat at high level`() {
        val weak = RatEntity(artKey = "bolt_pic", name = "W", power = 1, toughness = 1, shiny = false)
        val bot = RustbotFactory.forEncounter(30, weak)
        assertEquals("never outclasses a weak rat", 1, bot.power)

        val result = AutoResolver.resolve(
            Battle("W", weak.power, weak.maxHp, bot.name, bot.power, bot.maxHp)
        )
        assertEquals(BattleOutcome.PLAYER_WON, result.outcome)
    }

    @Test
    fun `auto resolve always terminates`() {
        for (p in 1..10) for (t in 1..10) {
            val rat = RatEntity(artKey = "flux_pic", name = "R", power = p, toughness = t, shiny = false)
            for (level in intArrayOf(1, 6, 12, 30)) {
                val bot = RustbotFactory.forEncounter(level, rat)
                val done = AutoResolver.resolve(
                    Battle("R", rat.power, rat.maxHp, bot.name, bot.power, bot.maxHp)
                )
                assertTrue(
                    "P$p T$t L$level did not finish",
                    done.outcome != BattleOutcome.ONGOING
                )
            }
        }
    }

    @Test
    fun `rewards stay inside the intended band`() {
        for (level in intArrayOf(1, 10, 30)) {
            val base = 10 + level * 2
            repeat(200) {
                val reward = RustbotFactory.rewardFor(level)
                assertTrue("$reward too low for L$level", reward >= (base * 0.85).toInt())
                assertTrue("$reward too high for L$level", reward <= (base * 1.15).toInt() + 1)
            }
        }
    }
}
