package com.example.ratkingrecon

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two Shop boosts, tested from the side that actually matters: what they do
 * to a hatch.
 *
 * The Shop's whole transaction is setting a flag - so what stops a purchase
 * leaking into the wrong rat is [GameEngine.rollRat] reading that flag on the
 * next hatch and clearing it in the same edit. These lock that rule down, so the
 * purchase buttons can move again without quietly changing what buying one does.
 */
class HatchBoostTest {

    /**
     * Walks far enough to cross the Level 1 threshold, forcing exactly one hatch.
     *
     * The first reading only establishes the step baseline; the second banks 60
     * EXP, which clears the 50 needed for the first level.
     */
    private fun hatchOnce(prefs: FakePrefs, dao: FakeRatDao = FakeRatDao()): RatEntity {
        GameEngine.onSteps(dao, prefs, 0f)
        val outcome = GameEngine.onSteps(dao, prefs, 60f)
        return requireNotNull(outcome.hatched) { "expected the 60 steps to hatch a rat" }
    }

    @Test
    fun `serum forces high stats and is spent by the hatch`() {
        val prefs = FakePrefs()
        prefs.edit().putBoolean(GameEngine.KEY_MUTAGEN, true).apply()

        val rat = hatchOnce(prefs)

        assertTrue("power was ${rat.power}", rat.power in 6..10)
        assertTrue("toughness was ${rat.toughness}", rat.toughness in 6..10)
        assertFalse(
            "the serum should be spent by the hatch it applied to",
            prefs.getBoolean(GameEngine.KEY_MUTAGEN, false)
        )
    }

    @Test
    fun `gleam forces a shiny and is spent by the hatch`() {
        val prefs = FakePrefs()
        prefs.edit().putBoolean(GameEngine.KEY_POLISH, true).apply()

        val rat = hatchOnce(prefs)

        assertTrue("the gleam should guarantee a shiny", rat.shiny)
        assertFalse(
            "the gleam should be spent by the hatch it applied to",
            prefs.getBoolean(GameEngine.KEY_POLISH, false)
        )
    }

    @Test
    fun `an unboosted hatch rolls in the ordinary range`() {
        val rat = hatchOnce(FakePrefs())

        assertTrue("power was ${rat.power}", rat.power in 1..5)
        assertTrue("toughness was ${rat.toughness}", rat.toughness in 1..5)
    }

    /** The point of the Hatching Boosts wording on the Shop screen. */
    @Test
    fun `a boost never touches a rat that is already collected`() {
        val prefs = FakePrefs()
        val dao = FakeRatDao()
        dao.insert(
            RatEntity(artKey = "forman_pic", name = "Forman", power = 2, toughness = 5, shiny = false)
        )

        prefs.edit()
            .putBoolean(GameEngine.KEY_MUTAGEN, true)
            .putBoolean(GameEngine.KEY_POLISH, true)
            .apply()

        hatchOnce(prefs, dao)

        val alreadyOwned = dao.rows.first()
        assertEquals(2, alreadyOwned.power)
        assertEquals(5, alreadyOwned.toughness)
        assertFalse(alreadyOwned.shiny)
    }

    /** A boost is one-shot, not a permanent upgrade to every future hatch. */
    @Test
    fun `the hatch after a boosted one is back to normal`() {
        val prefs = FakePrefs()
        val dao = FakeRatDao()
        prefs.edit().putBoolean(GameEngine.KEY_MUTAGEN, true).apply()

        val boosted = hatchOnce(prefs, dao)
        assertTrue("power was ${boosted.power}", boosted.power in 6..10)

        // Level 2 needs 100 EXP, so 100 more steps hatches exactly once more.
        val next = requireNotNull(GameEngine.onSteps(dao, prefs, 160f).hatched)
        assertTrue("power was ${next.power}", next.power in 1..5)
        assertTrue("toughness was ${next.toughness}", next.toughness in 1..5)
    }
}

/**
 * In-memory RatDao.
 *
 * [strongestAvailable] always reports nobody, which switches off the encounter
 * roll - it shares [GameEngine.onSteps] with hatching but has nothing to do with
 * boosts, and leaving it live would make these tests randomly do extra work.
 */
private class FakeRatDao : RatDao {

    val rows = mutableListOf<RatEntity>()
    private var nextId = 1L

    override fun insert(rat: RatEntity): Long {
        val id = nextId++
        rows += rat.copy(id = id)
        return id
    }

    override fun insertAll(rats: List<RatEntity>) {
        rats.forEach { insert(it) }
    }

    override fun all(): List<RatEntity> = rows.toList()
    override fun byPowerDesc(): List<RatEntity> = rows.sortedByDescending { it.power }
    override fun shinyOnly(): List<RatEntity> = rows.filter { it.shiny }
    override fun byId(id: Long): RatEntity? = rows.firstOrNull { it.id == id }
    override fun count(): Int = rows.size
    override fun distinctSpeciesFound(rosterKeys: List<String>): Int =
        rows.map { it.artKey }.filter { it in rosterKeys }.distinct().size

    override fun maxPower(): Int = rows.maxOfOrNull { it.power } ?: 0
    override fun maxToughness(): Int = rows.maxOfOrNull { it.toughness } ?: 0
    override fun ownsShiny(): Boolean = rows.any { it.shiny }
    override fun weakest(limit: Int): List<RatEntity> = rows.sortedBy { it.score }.take(limit)
    override fun strongestAvailable(now: Long): RatEntity? = null
    override fun availableCount(now: Long): Int = rows.count { !it.isRecovering(now) }

    override fun recordWin(id: Long) = replace(id) { it.copy(wins = it.wins + 1) }

    override fun recordLoss(id: Long, until: Long) =
        replace(id) { it.copy(losses = it.losses + 1, recoveringUntil = until) }

    override fun revive(id: Long) = replace(id) { it.copy(recoveringUntil = 0) }

    override fun update(rat: RatEntity) = replace(rat.id) { rat }

    override fun delete(rats: List<RatEntity>) {
        val ids = rats.map { it.id }.toSet()
        rows.removeAll { it.id in ids }
    }

    override fun deleteAll() {
        rows.clear()
    }

    private fun replace(id: Long, change: (RatEntity) -> RatEntity) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = change(rows[index])
    }
}
