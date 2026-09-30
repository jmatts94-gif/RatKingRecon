package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleItemLimitTest {

    private fun battle(limit: Int?) = Battle(
        ratName = "R", ratPower = 5, ratMaxHp = 200, botName = "B", botPower = 1, botMaxHp = 500,
        startingRatHp = 50, itemLimit = limit
    )

    @Test
    fun `the Arena allows one item a fight`() {
        val b = battle(1)
        assertTrue(b.itemsAllowed)
        assertEquals(BattleAction.USE_ITEM, b.advance(BattleAction.USE_ITEM, BattleItem.HP_TONIC).action)
        assertFalse(b.itemsAllowed)

        // A second item is swung as a plain Attack instead.
        val second = b.advance(BattleAction.USE_ITEM, BattleItem.HP_TONIC)
        assertEquals(BattleAction.ATTACK, second.action)
        assertNull(second.itemUsed)
        assertEquals(1, b.itemsUsed)
    }

    @Test
    fun `ordinary fights have no limit`() {
        val b = battle(null)
        repeat(3) { b.advance(BattleAction.USE_ITEM, BattleItem.CLEANSE) }
        assertEquals(3, b.itemsUsed)
        assertTrue(b.itemsAllowed)
    }
}
