package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Arena at twenty fights: the Rust King at the end, his mantle as the
 * prize, and fights 1-15 exactly as they were.
 */
class ArenaTwentyTest {

    private fun rat() = RatEntity(artKey = "bolt_pic", name = "R", power = 8, toughness = 8, shiny = false)

    @Test
    fun `the run is twenty fights with the Rust King last`() {
        assertEquals(20, ArenaRun.TOTAL_FIGHTS)
        assertEquals(Bosses.RUST_KING.id, ArenaRun.bossIdFor(20))
        assertEquals("rustbringer", ArenaRun.bossIdFor(15))
        assertNull(ArenaRun.bossIdFor(16))
    }

    @Test
    fun `fights 1 to 15 ramp from warm-up to the old finish line`() {
        assertEquals(0.60, ArenaRun.ratioFor(1), 1e-9)
        assertEquals(1.15, ArenaRun.ratioFor(15), 1e-9)
        assertEquals(3.0, ArenaRun.scrapMultiplierFor(15), 1e-9)
        assertEquals(0.50, ArenaRun.relicChanceFor(15), 1e-9)
    }

    @Test
    fun `fights past 15 only creep harder`() {
        assertEquals(1.16, ArenaRun.ratioFor(16), 1e-9)
        assertEquals(1.20, ArenaRun.ratioFor(20), 1e-9)
        assertTrue(ArenaRun.relicChanceFor(20) <= 0.60)
    }

    @Test
    fun `the Rust King is not on the level ladder`() {
        assertFalse(Bosses.all.any { it.id == Bosses.RUST_KING.id })
        assertNull(Bosses.byId(Bosses.RUST_KING.id))
    }

    @Test
    fun `there is a fight-20 badge that does not spin`() {
        val badge = ArenaRun.MILESTONES.single { it.fight == 20 }
        assertFalse(badge.spins)
        assertTrue(ArenaRun.MILESTONES.filter { it.fight != 20 }.all { it.spins })
    }

    @Test
    fun `the Rust King alternates his two moves and spares no faction`() {
        assertEquals(BossMoves.CROWN_CRUSH, BossMoves.forBoss("rust_king", 1))
        assertEquals(BossMoves.RUST_TIDE, BossMoves.forBoss("rust_king", 2))
        assertEquals(BossMoves.CROWN_CRUSH, BossMoves.forBoss("rust_king", 3))
        assertNull(BossMoves.CROWN_CRUSH.targetFaction)
        assertTrue(BossMoves.RUST_TIDE.appliesDot)
    }

    @Test
    fun `the Rust King enrages once, at half health`() {
        val b = Battle(
            ratName = "R", ratPower = 30, ratMaxHp = 10_000,
            botName = "King", botPower = 10, botMaxHp = 100,
            bossId = "rust_king", intents = true
        )
        assertFalse(b.botEnraged)
        assertEquals(10, b.botCurrentPower)

        val first = b.advance(BattleAction.ATTACK) // 100 -> 70
        assertFalse(first.botEnragedNow)
        val second = b.advance(BattleAction.ATTACK) // 70 -> 40
        assertTrue(second.botEnragedNow)
        assertTrue(b.botEnraged)
        assertEquals(13, b.botCurrentPower)
        assertFalse(b.advance(BattleAction.DEFEND).botEnragedNow)
    }

    @Test
    fun `other bosses never enrage`() {
        val b = Battle(
            ratName = "R", ratPower = 90, ratMaxHp = 10_000,
            botName = "Baron", botPower = 10, botMaxHp = 100,
            bossId = "boiler_baron", intents = true
        )
        b.advance(BattleAction.ATTACK)
        assertFalse(b.botEnraged)
        assertEquals(10, b.botCurrentPower)
    }

    @Test
    fun `fight 15 hands out Champion's Banner and keeps the run going`() {
        val prefs = FakePrefs()
        val dao = FakeRatDao()
        val r = rat().let { it.copy(id = dao.insert(it)) }
        ArenaRun.begin(prefs, r.id)

        val outcome = ArenaRun.recordWin(dao, prefs, 15, 10, r.maxHp, 20)

        assertFalse(outcome.cleared)
        assertEquals(Frames.ARENA_CHAMPION, outcome.cosmeticFrame)
        assertTrue(ShopEffects.ownsCosmetic(prefs, Frames.ARENA_CHAMPION.id))
        assertEquals(16, ArenaRun.currentFight(prefs))
        assertTrue(ArenaRun.isActive(prefs))
    }

    @Test
    fun `beating the Rust King pays his mantle first`() {
        val prefs = FakePrefs()
        val dao = FakeRatDao()
        val r = rat().let { it.copy(id = dao.insert(it)) }
        ArenaRun.begin(prefs, r.id)

        val outcome = ArenaRun.recordWin(dao, prefs, 20, 10, r.maxHp, 20)

        assertTrue(outcome.cleared)
        assertEquals(Frames.RUST_KINGS_MANTLE, outcome.cosmeticFrame)
        assertTrue(ShopEffects.ownsCosmetic(prefs, Frames.RUST_KINGS_MANTLE.id))
        assertEquals(20, outcome.milestoneFightNewlyEarned)
        assertFalse(ArenaRun.isActive(prefs))
    }

    @Test
    fun `the mantle is never in the repeat-clear pool and never for sale`() {
        assertFalse(Frames.RUST_KINGS_MANTLE.arenaPool)
        assertFalse(Frames.RUST_KINGS_MANTLE.sellable)
        assertTrue(Frames.RUST_KINGS_MANTLE.price > Frames.all.filter { it != Frames.RUST_KINGS_MANTLE }.maxOf { it.price })
    }
}
