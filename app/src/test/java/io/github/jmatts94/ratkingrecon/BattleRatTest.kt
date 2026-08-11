package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Designating a rat for combat: who fights, and what the designation costs.
 */
class BattleRatTest {

    private fun rat(id: Long, power: Int = 3, toughness: Int = 3, shiny: Boolean = false) =
        RatEntity(
            id = id, artKey = "bolt_pic", name = "Rat$id",
            power = power, toughness = toughness, shiny = shiny
        )

    // --- designation ----------------------------------------------------------

    @Test
    fun `nothing is designated on a fresh save`() {
        val prefs = FakePrefs()
        assertFalse(BattleRat.isSet(prefs))
        assertEquals(BattleRat.NONE, BattleRat.idOf(prefs))
        assertFalse(BattleRat.isBattleRat(prefs, 1L))
    }

    @Test
    fun `designating and standing down are the same button`() {
        val prefs = FakePrefs()

        assertTrue("first press designates", BattleRat.toggle(prefs, 7L))
        assertTrue(BattleRat.isBattleRat(prefs, 7L))

        assertFalse("second press stands it down", BattleRat.toggle(prefs, 7L))
        assertFalse(BattleRat.isSet(prefs))
    }

    @Test
    fun `designating another rat replaces the first, at no cost`() {
        val prefs = FakePrefs()
        BattleRat.set(prefs, 1L)
        BattleRat.set(prefs, 2L)

        assertFalse(BattleRat.isBattleRat(prefs, 1L))
        assertTrue(BattleRat.isBattleRat(prefs, 2L))
        // Reassignment is meant to be a standing decision, not a purchase.
        assertEquals("nothing else may be written", setOf(BattleRat.KEY_ID), prefs.values.keys)
    }

    @Test
    fun `an unset designation never matches a real rat`() {
        val prefs = FakePrefs()
        assertFalse(BattleRat.isBattleRat(prefs, BattleRat.NONE))
    }

    // --- who fights -----------------------------------------------------------

    @Test
    fun `the designated rat fights, even when it is not the strongest`() {
        val prefs = FakePrefs()
        val dao = BattleStubDao(mutableListOf(rat(1, power = 2), rat(2, power = 9)))
        BattleRat.set(prefs, 1L)

        val choice = BattleRat.fighterFor(dao, prefs)

        assertEquals(1L, choice.rat?.id)
        assertTrue(choice.usedBattleRat)
    }

    @Test
    fun `with nothing designated the strongest fights, as before`() {
        val prefs = FakePrefs()
        val dao = BattleStubDao(mutableListOf(rat(1, power = 2), rat(2, power = 9)))

        val choice = BattleRat.fighterFor(dao, prefs)

        assertEquals(2L, choice.rat?.id)
        assertFalse("the caller needs to know it fell back", choice.usedBattleRat)
    }

    @Test
    fun `a knocked-out Battle Rat steps aside rather than blocking the fight`() {
        val prefs = FakePrefs()
        val recovering = rat(1).copy(recoveringUntil = Long.MAX_VALUE)
        val dao = BattleStubDao(mutableListOf(recovering, rat(2, power = 9)))
        BattleRat.set(prefs, 1L)

        val choice = BattleRat.fighterFor(dao, prefs)

        assertEquals("the fight still happens", 2L, choice.rat?.id)
        assertFalse(choice.usedBattleRat)
        assertTrue("and the designation survives the knockout", BattleRat.isSet(prefs))
    }

    /**
     * A Battle Rat can go into the Fusion Pot like any other.
     *
     * The dangling id is cleared rather than left to be looked up and missed on
     * every future encounter.
     */
    @Test
    fun `a spliced-away Battle Rat clears its own designation`() {
        val prefs = FakePrefs()
        val dao = BattleStubDao(mutableListOf(rat(1), rat(2)))
        BattleRat.set(prefs, 1L)

        dao.delete(listOf(rat(1)))
        val choice = BattleRat.fighterFor(dao, prefs)

        assertFalse("the dangling id must not survive", BattleRat.isSet(prefs))
        assertEquals("and somebody else fights", 2L, choice.rat?.id)
    }

    @Test
    fun `no rats at all means no fight`() {
        val prefs = FakePrefs()
        val dao = BattleStubDao(mutableListOf())

        assertNull(BattleRat.fighterFor(dao, prefs).rat)
    }

    // --- the Ledger Task lockout ----------------------------------------------

    @Test
    fun `the Battle Rat is excluded from the task gates`() {
        val prefs = FakePrefs()
        val dao = BattleStubDao(mutableListOf(rat(1, power = 9), rat(2, power = 3)))
        BattleRat.set(prefs, 1L)

        val excluded = BattleRat.exclusionId(dao, prefs)

        assertEquals(1L, excluded)
        assertEquals("the strong rat must not count", 3, dao.maxPowerExcluding(excluded))
        assertEquals("but it still exists", 9, dao.maxPowerExcluding(BattleRat.NONE))
    }

    /**
     * The exemption agreed for a one-rat roster.
     *
     * Excluding a player's only rat would gate every task against an empty
     * collection, which reads as the feature being broken rather than as a cost.
     */
    @Test
    fun `a single-rat roster is exempt from the exclusion`() {
        val prefs = FakePrefs()
        val dao = BattleStubDao(mutableListOf(rat(1, power = 9)))
        BattleRat.set(prefs, 1L)

        assertEquals(BattleRat.NONE, BattleRat.exclusionId(dao, prefs))
        assertEquals("its stats still count", 9, dao.maxPowerExcluding(BattleRat.exclusionId(dao, prefs)))
    }

    @Test
    fun `the exclusion starts applying at two rats`() {
        val prefs = FakePrefs()
        val dao = BattleStubDao(mutableListOf(rat(1, power = 9)))
        BattleRat.set(prefs, 1L)
        assertEquals(BattleRat.NONE, BattleRat.exclusionId(dao, prefs))

        dao.insert(rat(0, power = 4))
        assertEquals(1L, BattleRat.exclusionId(dao, prefs))
    }

    @Test
    fun `nothing is excluded when no rat is designated`() {
        val prefs = FakePrefs()
        val dao = BattleStubDao(mutableListOf(rat(1), rat(2)))

        assertEquals(BattleRat.NONE, BattleRat.exclusionId(dao, prefs))
    }

    @Test
    fun `excluding nobody is the same as counting everything`() {
        val dao = BattleStubDao(mutableListOf(rat(1, 9, 4, shiny = true), rat(2, 3, 8)))

        assertEquals(9, dao.maxPowerExcluding(BattleRat.NONE))
        assertEquals(8, dao.maxToughnessExcluding(BattleRat.NONE))
        assertTrue(dao.ownsShinyExcluding(BattleRat.NONE))
    }

    @Test
    fun `excluding the only shiny hides it from the shiny task`() {
        val prefs = FakePrefs()
        val dao = BattleStubDao(mutableListOf(rat(1, shiny = true), rat(2)))
        BattleRat.set(prefs, 1L)

        assertTrue("the shiny is still in the collection", dao.ownsShinyExcluding(BattleRat.NONE))
        assertFalse(
            "but not for task purposes",
            dao.ownsShinyExcluding(BattleRat.exclusionId(dao, prefs))
        )
    }

    // --- combat goes through the designation ----------------------------------

    @Test
    fun `raiseEncounter sends the Battle Rat`() {
        val prefs = FakePrefs()
        val dao = BattleStubDao(mutableListOf(rat(1, power = 2), rat(2, power = 9)))
        BattleRat.set(prefs, 1L)

        val encounter = GameEngine.raiseEncounter(dao, prefs, 12)

        assertNotNull(encounter)
        assertEquals("the designated rat, not the strongest", 1L, encounter!!.ratId)
    }

    @Test
    fun `a banked boss is met by the Battle Rat too`() {
        val prefs = FakePrefs()
        val dao = BattleStubDao(mutableListOf(rat(1, power = 2), rat(2, power = 9)))
        prefs.values[GameEngine.KEY_LEVEL] = 12
        BattleRat.set(prefs, 1L)
        Bosses.bank(prefs, Bosses.all.first())

        Bosses.startBanked(dao, prefs) { "The Junk Golem" }

        assertEquals(1L, Encounter.load(prefs)?.ratId)
    }
}

private class BattleStubDao(private val rats: MutableList<RatEntity>) : RatDao {
    private var nextId = 100L
    override fun insert(rat: RatEntity): Long {
        val id = nextId++; rats += rat.copy(id = id); return id
    }
    override fun insertAll(rats: List<RatEntity>) { rats.forEach { insert(it) } }
    override fun all(): List<RatEntity> = rats.toList()
    override fun byPowerDesc(): List<RatEntity> = rats.sortedByDescending { it.power }
    override fun shinyOnly(): List<RatEntity> = rats.filter { it.shiny }
    override fun byId(id: Long): RatEntity? = rats.firstOrNull { it.id == id }
    override fun count(): Int = rats.size
    override fun distinctSpeciesFound(rosterKeys: List<String>): Int =
        rats.map { it.artKey }.filter { it in rosterKeys }.distinct().size
    override fun maxPowerExcluding(excludedId: Long): Int =
        rats.filter { it.id != excludedId }.maxOfOrNull { it.power } ?: 0
    override fun maxToughnessExcluding(excludedId: Long): Int =
        rats.filter { it.id != excludedId }.maxOfOrNull { it.toughness } ?: 0
    override fun ownsShiny(): Boolean = rats.any { it.shiny }
    override fun ownsShinyExcluding(excludedId: Long): Boolean =
        rats.any { it.shiny && it.id != excludedId }
    override fun weakest(limit: Int): List<RatEntity> = rats.sortedBy { it.score }.take(limit)
    override fun strongestAvailable(now: Long): RatEntity? =
        rats.filter { !it.isRecovering(now) }.maxByOrNull { it.score }
    override fun recordWin(id: Long) = Unit
    override fun recordLoss(id: Long, until: Long) = Unit
    override fun revive(id: Long) = Unit
    override fun update(rat: RatEntity) = Unit
    override fun delete(rats: List<RatEntity>) {
        val ids = rats.map { it.id }.toSet(); this.rats.removeAll { it.id in ids }
    }
    override fun deleteAll() { rats.clear() }
}
