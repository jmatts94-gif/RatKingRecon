package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Intents mode: announced Rustbot moves, parries and repairs.
 *
 * No faction on these rats, so no Special side-effect rolls - every number
 * here is exact.
 */
class BattleIntentsTest {

    private fun battle(
        intents: Boolean = true,
        ratPower: Int = 4,
        ratMaxHp: Int = 1000,
        botPower: Int = 10,
        botMaxHp: Int = 1000,
        bossId: String? = null
    ) = Battle(
        ratName = "Rat", ratPower = ratPower, ratMaxHp = ratMaxHp,
        botName = "Bot", botPower = botPower, botMaxHp = botMaxHp,
        bossId = bossId, intents = intents
    )

    @Test
    fun `intents are hidden when the mode is off`() {
        assertNull(battle(intents = false).botIntent)
    }

    @Test
    fun `an ordinary Rustbot follows a readable pattern`() {
        val b = battle()
        val seen = (1..12).map { b.botIntent.also { b.advance(BattleAction.ATTACK) } }
        assertEquals(
            listOf(
                BotIntent.STRIKE, BotIntent.STRIKE, BotIntent.HEAVY,
                BotIntent.STRIKE, BotIntent.REPAIR, BotIntent.HEAVY,
                BotIntent.STRIKE, BotIntent.STRIKE, BotIntent.HEAVY,
                BotIntent.STRIKE, BotIntent.REPAIR, BotIntent.HEAVY
            ),
            seen
        )
    }

    @Test
    fun `each round reports the intent it was announced with`() {
        val b = battle()
        repeat(6) {
            val announced = b.botIntent
            assertEquals(announced, b.advance(BattleAction.ATTACK).botIntent)
        }
    }

    @Test
    fun `an ordinary HEAVY hits for two and a half times Power`() {
        val b = battle()
        b.advance(BattleAction.ATTACK)
        b.advance(BattleAction.ATTACK)
        assertEquals(BotIntent.HEAVY, b.botIntent)
        assertEquals(25, b.botNextDamage())
        assertEquals(25, b.advance(BattleAction.ATTACK).damageTaken)
    }

    @Test
    fun `a boss keeps its old one and a half times Special`() {
        assertEquals(15, battle(bossId = "junk_golem").botSpecialDamage())
    }

    @Test
    fun `defending a HEAVY parries it`() {
        val b = battle()
        b.advance(BattleAction.SPECIAL) // round 1: Special used, on cooldown
        b.advance(BattleAction.ATTACK)
        val botBefore = b.botHp

        val r = b.advance(BattleAction.DEFEND)

        assertTrue(r.parried)
        assertEquals(12, r.damageTaken) // 25 halved, rounded down
        assertEquals(2, r.counterDamage) // half of Power 4
        assertEquals(botBefore - 2, b.botHp)
        // Used on round 1 with a 3-round cooldown it is ready on round 4
        // anyway - the parry's refund shows on the next use instead.
        assertTrue(b.specialAvailable)
    }

    @Test
    fun `a parry brings the Special back a round early`() {
        val parried = battle()
        val plain = battle()
        // Special on round 2, so it is still cooling on round 3's HEAVY.
        for (b in listOf(parried, plain)) {
            b.advance(BattleAction.ATTACK)
            b.advance(BattleAction.SPECIAL)
        }
        parried.advance(BattleAction.DEFEND)
        plain.advance(BattleAction.ATTACK)

        assertTrue(parried.specialAvailable)
        assertFalse(plain.specialAvailable)
    }

    @Test
    fun `defending a plain STRIKE is not a parry`() {
        val r = battle().advance(BattleAction.DEFEND)
        assertFalse(r.parried)
        assertEquals(0, r.counterDamage)
        assertEquals(5, r.damageTaken)
    }

    @Test
    fun `no parries without intents mode`() {
        val b = battle(intents = false)
        b.advance(BattleAction.ATTACK)
        b.advance(BattleAction.ATTACK)
        assertFalse(b.advance(BattleAction.DEFEND).parried)
    }

    @Test
    fun `a REPAIR round heals when left alone and deals nothing`() {
        val b = battle()
        repeat(4) { b.advance(BattleAction.ATTACK) } // 16 damage dealt
        assertEquals(BotIntent.REPAIR, b.botIntent)
        assertEquals(0, b.botNextDamage())

        val r = b.advance(BattleAction.DEFEND)

        assertEquals(0, r.damageTaken)
        assertEquals(16, r.botRepaired) // capped at what it had lost
        assertEquals(1000, b.botHp)
    }

    @Test
    fun `hitting a REPAIR round interrupts it`() {
        val b = battle()
        repeat(4) { b.advance(BattleAction.ATTACK) }
        val before = b.botHp

        val r = b.advance(BattleAction.ATTACK)

        assertEquals(0, r.botRepaired)
        assertEquals(0, r.damageTaken)
        assertEquals(before - 4, b.botHp)
    }

    @Test
    fun `a boss never repairs`() {
        val b = battle(bossId = "junk_golem")
        val seen = (1..12).map { b.botIntent.also { b.advance(BattleAction.ATTACK) } }
        assertFalse(seen.contains(BotIntent.REPAIR))
        assertTrue(seen.contains(BotIntent.CHARGING))
    }

    @Test
    fun `auto-resolve parries HEAVYs and never blocks a REPAIR`() {
        val b = battle()
        AutoResolver.resolve(b)
        b.log.forEach { r ->
            when (r.botIntent) {
                BotIntent.HEAVY -> if (r.botHp > 0) assertEquals(BattleAction.DEFEND, r.action)
                BotIntent.REPAIR -> assertTrue(r.action != BattleAction.DEFEND)
                else -> {}
            }
        }
    }
}
