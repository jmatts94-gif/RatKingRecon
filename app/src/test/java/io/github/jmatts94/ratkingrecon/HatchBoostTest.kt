package io.github.jmatts94.ratkingrecon

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
     * The first reading only establishes the step baseline; the second banks 260
     * EXP, which clears the 250 needed for the first level.
     */
    private fun hatchOnce(prefs: FakePrefs, dao: FakeRatDao = FakeRatDao()): RatEntity {
        GameEngine.onSteps(dao, prefs, 0f)
        val outcome = GameEngine.onSteps(dao, prefs, 260f)
        return requireNotNull(outcome.hatched) { "expected the 260 steps to hatch a rat" }
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

    /**
     * The debug top-up mints through the same roll a walk does.
     *
     * Worth pinning because the whole reason [GameEngine.mintRat] exists is to
     * stop a second way of making a rat appearing beside the first: a cheat that
     * rolled its own would drift the moment the hatch rules changed.
     */
    @Test
    fun `a minted rat is an ordinary rat, and lands in the Ledger`() {
        val prefs = FakePrefs()
        val dao = FakeRatDao()

        val minted = GameEngine.mintRat(dao, prefs)

        assertTrue("power was ${minted.power}", minted.power in 1..5)
        assertTrue("toughness was ${minted.toughness}", minted.toughness in 1..5)
        assertTrue("species off roster", Roster.all.any { it.artKey == minted.artKey })
        assertEquals("the rat should have been inserted", 1, dao.rows.size)
        assertTrue("Room should have assigned an id", minted.id != 0L)
    }

    @Test
    fun `minting repeatedly fills the Ledger without reusing an id`() {
        val prefs = FakePrefs()
        val dao = FakeRatDao()

        val minted = List(10) { GameEngine.mintRat(dao, prefs) }

        assertEquals(10, dao.rows.size)
        assertEquals("ids must be distinct", 10, minted.map { it.id }.distinct().size)
    }

    /** A minted rat honours an armed boost, the same way the next walked hatch would. */
    @Test
    fun `a minted rat spends an armed serum exactly once`() {
        val prefs = FakePrefs()
        val dao = FakeRatDao()
        prefs.edit().putBoolean(GameEngine.KEY_MUTAGEN, true).apply()

        val first = GameEngine.mintRat(dao, prefs)
        val second = GameEngine.mintRat(dao, prefs)

        assertTrue("boosted power was ${first.power}", first.power in 6..10)
        assertTrue("the serum should not carry over", second.power in 1..5)
        assertFalse(prefs.getBoolean(GameEngine.KEY_MUTAGEN, false))
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

    /**
     * TimeTail is Secret-tier - see [Roster.SECRET] - and must never turn up
     * from an ordinary hatch, however many times the dice are rolled. He only
     * ever reaches the Ledger through [AchievementRewards.grant].
     */
    @Test
    fun `TimeTail never comes from an ordinary hatch`() {
        val prefs = FakePrefs()
        val dao = FakeRatDao()

        val minted = List(2_000) { GameEngine.mintRat(dao, prefs) }

        assertTrue(
            "TimeTail must never be minted like an ordinary species",
            minted.none { it.artKey == "timetail_pic" }
        )
    }

    /** A boost is one-shot, not a permanent upgrade to every future hatch. */
    @Test
    fun `the hatch after a boosted one is back to normal`() {
        val prefs = FakePrefs()
        val dao = FakeRatDao()
        prefs.edit().putBoolean(GameEngine.KEY_MUTAGEN, true).apply()

        val boosted = hatchOnce(prefs, dao)
        assertTrue("power was ${boosted.power}", boosted.power in 6..10)

        // Level 2 needs 500 EXP; hatchOnce left 10 banked, so 500 more clears it
        // with the same +10 margin hatchOnce itself uses.
        val next = requireNotNull(GameEngine.onSteps(dao, prefs, 760f).hatched)
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
 * That same property is why [DailyStepsTest] borrows it: it wants a plain walk,
 * with no fight breaking out partway through.
 *
 * Shared rather than file-private, alongside [FakePrefs], so the step tests can
 * drive [GameEngine.onSteps] without a second copy of this drifting from it.
 */
internal class FakeRatDao : RatDao {

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
    override fun byId(id: Long): RatEntity? = rows.firstOrNull { it.id == id }
    override fun count(): Int = rows.size
    override fun distinctSpeciesFound(rosterKeys: List<String>): Int =
        rows.map { it.artKey }.filter { it in rosterKeys }.distinct().size

    override fun maxPowerExcluding(excludedId: Long): Int =
        rows.filter { it.id != excludedId }.maxOfOrNull { it.power } ?: 0
    override fun maxToughnessExcluding(excludedId: Long): Int =
        rows.filter { it.id != excludedId }.maxOfOrNull { it.toughness } ?: 0
    override fun ownsShiny(): Boolean = rows.any { it.shiny }
    override fun ownsShinyExcluding(excludedId: Long): Boolean =
        rows.any { it.shiny && it.id != excludedId }
    override fun strongestAvailable(now: Long): RatEntity? = null

    override fun recordWin(id: Long) = replace(id) { it.copy(wins = it.wins + 1) }

    override fun recordLoss(id: Long, until: Long) =
        replace(id) { it.copy(losses = it.losses + 1, recoveringUntil = until) }

    override fun revive(id: Long) = replace(id) { it.copy(recoveringUntil = 0) }

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
