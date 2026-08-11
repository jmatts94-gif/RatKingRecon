package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Encounter triggering: what stops a Rustbot fight from being raised, and how
 * often one is raised when nothing does.
 *
 * The rate assertions are statistical and use a seeded generator so they cannot
 * flake. They are deliberately loose - they exist to catch a gate that has
 * silently stopped letting encounters through, not to pin the tuning.
 */
class EncounterTriggerTest {

    /** Roughly a step every half-second, the cadence a walking phone reports at. */
    private fun walk(
        dao: RatDao,
        prefs: FakePrefs,
        steps: Int,
        stepsPerBatch: Int = 1,
        startAt: Float = 0f
    ): Int {
        var encounters = 0
        var counter = startAt
        var walked = 0
        while (walked < steps) {
            val batch = minOf(stepsPerBatch, steps - walked)
            counter += batch
            walked += batch
            val outcome = GameEngine.onSteps(dao, prefs, counter)
            if (outcome.encounter != null) {
                encounters++
                // Stand in for the player settling it, so the pending-encounter
                // gate does not swallow the rest of the walk.
                Encounter.clear(prefs)
            }
        }
        return encounters
    }

    // --- the reboot bug -------------------------------------------------------

    @Test
    fun `reboot clears the encounter spacing marker`() {
        val prefs = FakePrefs()
        val dao = StubDao(rats = mutableListOf(rat(id = 1)))

        // A phone that has been up a while: the counter is already high.
        GameEngine.onSteps(dao, prefs, 47_000f)
        walk(dao, prefs, steps = 2_000, startAt = 47_000f)
        assertTrue(
            "expected the walk to have recorded an encounter marker",
            prefs.values.containsKey("LAST_ENCOUNTER_STEPS")
        )

        // Reboot: TYPE_STEP_COUNTER restarts from zero.
        GameEngine.onSteps(dao, prefs, 0f)

        assertNull(
            "the pre-reboot marker must not survive, or the gap check blocks " +
                "every encounter until the counter climbs back past it",
            prefs.values["LAST_ENCOUNTER_STEPS"]
        )
    }

    @Test
    fun `encounters still trigger after a reboot`() {
        val prefs = FakePrefs()
        val dao = StubDao(rats = mutableListOf(rat(id = 1)))

        GameEngine.onSteps(dao, prefs, 47_000f)
        walk(dao, prefs, steps = 1_000, startAt = 47_000f)

        GameEngine.onSteps(dao, prefs, 0f)
        val after = walk(dao, prefs, steps = 4_000, startAt = 0f)

        assertTrue(
            "no encounters in 4000 steps after a reboot - the gap gate is stuck",
            after > 0
        )
    }

    // --- roster-scaled recovery ----------------------------------------------

    @Test
    fun `recovery scales from ten minutes to thirty as the roster grows`() {
        val minutes = (1..7).map { (EncounterResolver.recoveryMsFor(it) / 60_000L).toInt() }
        assertEquals(listOf(10, 14, 18, 22, 26, 30, 30), minutes)
    }

    @Test
    fun `an empty roster still gets the floor rather than zero`() {
        assertEquals(EncounterResolver.MIN_RECOVERY_MS, EncounterResolver.recoveryMsFor(0))
    }

    @Test
    fun `recovery never exceeds the full window`() {
        (0..50).forEach {
            assertTrue(EncounterResolver.recoveryMsFor(it) <= EncounterResolver.RECOVERY_MS)
            assertTrue(EncounterResolver.recoveryMsFor(it) >= EncounterResolver.MIN_RECOVERY_MS)
        }
    }

    // --- the gates ------------------------------------------------------------

    @Test
    fun `an unresolved encounter blocks every later one`() {
        val prefs = FakePrefs()
        val dao = StubDao(rats = mutableListOf(rat(id = 1)))
        GameEngine.onSteps(dao, prefs, 0f)

        // Walk until one lands, then leave it pending.
        var counter = 0f
        while (!Encounter.isPending(prefs) && counter < 20_000f) {
            counter += 1f
            GameEngine.onSteps(dao, prefs, counter)
        }
        assertTrue("no encounter in 20000 steps", Encounter.isPending(prefs))

        val start = counter
        var raised = 0
        while (counter < start + 5_000f) {
            counter += 1f
            if (GameEngine.onSteps(dao, prefs, counter).encounter != null) raised++
        }
        assertEquals("a pending encounter must suppress new ones", 0, raised)
    }

    @Test
    fun `no encounter while every rat is recovering`() {
        val prefs = FakePrefs()
        // Hatching during the walk still adds rats, so the stub reports the
        // whole roster knocked out rather than starting it empty.
        val dao = StubDao(rats = mutableListOf(rat(id = 1)), allRecovering = true)
        GameEngine.onSteps(dao, prefs, 0f)

        val raised = walk(dao, prefs, steps = 5_000)
        assertEquals("gate 4 must refuse an encounter with no fighter", 0, raised)
    }

    // --- raising one directly, which the debug trigger uses -------------------

    @Test
    fun `raiseEncounter builds a pending fight against the best rat`() {
        val prefs = FakePrefs()
        val dao = StubDao(mutableListOf(rat(id = 1), rat(id = 2)))
        prefs.values[GameEngine.KEY_LEVEL] = 12

        val raised = GameEngine.raiseEncounter(dao, prefs, 12)

        assertNotNull(raised)
        assertTrue(Encounter.isPending(prefs))
        assertEquals(raised, Encounter.load(prefs))
        assertTrue("a Rustbot should have been scaled", raised!!.botPower >= 1)
        assertTrue("and it should pay something", raised.reward > 0)
    }

    @Test
    fun `raiseEncounter declines when no rat can fight`() {
        val prefs = FakePrefs()
        val dao = StubDao(mutableListOf(rat(id = 1)), allRecovering = true)

        assertNull(GameEngine.raiseEncounter(dao, prefs, 12))
        assertFalse("nothing may be left pending", Encounter.isPending(prefs))
    }

    /**
     * The spacing marker belongs to the walk.
     *
     * A forced encounter must not consume the gap, or using the debug trigger
     * would quietly delay the next real encounter by 150 steps.
     */
    @Test
    fun `raiseEncounter leaves the walk's spacing marker alone`() {
        val prefs = FakePrefs()
        val dao = StubDao(mutableListOf(rat(id = 1)))

        GameEngine.raiseEncounter(dao, prefs, 12)

        assertFalse(prefs.values.containsKey("LAST_ENCOUNTER_STEPS"))
    }

    // --- an encounter whose rat is gone ---------------------------------------

    /**
     * The failure this guards against ends the game quietly.
     *
     * A splice can eat the rat an encounter named. The fight then cannot open,
     * and because a pending encounter blocks new ones, combat stops for good
     * with nothing on screen to say why.
     */
    @Test
    fun `an encounter whose rat is gone clears itself`() {
        val prefs = FakePrefs()
        val dao = StubDao(mutableListOf(rat(id = 1)))

        GameEngine.raiseEncounter(dao, prefs, 12)
        assertTrue(Encounter.isPending(prefs))

        // The Fusion Pot consumes the fighter.
        dao.deleteAll()

        assertNull(Encounter.loadFightable(prefs, dao))
        assertFalse(
            "a fight nobody can take must not stay pending",
            Encounter.isPending(prefs)
        )
    }

    @Test
    fun `combat recovers after a stale encounter is cleared`() {
        val prefs = FakePrefs()
        val dao = StubDao(mutableListOf(rat(id = 1)))

        GameEngine.raiseEncounter(dao, prefs, 12)
        dao.deleteAll()
        Encounter.loadFightable(prefs, dao)

        // A new rat hatches, and the game must be able to raise a fight again.
        val replacement = dao.insert(rat(id = 0))
        assertNotNull(GameEngine.raiseEncounter(dao, prefs, 12))
        assertEquals(replacement, Encounter.load(prefs)?.ratId)
    }

    @Test
    fun `a fightable encounter is returned with its rat and left pending`() {
        val prefs = FakePrefs()
        val dao = StubDao(mutableListOf(rat(id = 1)))

        val raised = GameEngine.raiseEncounter(dao, prefs, 12)
        val loaded = Encounter.loadFightable(prefs, dao)

        assertNotNull(loaded)
        assertEquals(raised, loaded!!.first)
        assertEquals(raised!!.ratId, loaded.second.id)
        assertTrue("a good encounter must survive being read", Encounter.isPending(prefs))
    }

    @Test
    fun `nothing pending means nothing to load and nothing to clear`() {
        val prefs = FakePrefs()
        val dao = StubDao(mutableListOf(rat(id = 1)))

        assertNull(Encounter.loadFightable(prefs, dao))
        assertFalse(Encounter.isPending(prefs))
    }

    // --- rate -----------------------------------------------------------------

    @Test
    fun `a thousand steps yields roughly two encounters`() {
        val trials = 400
        var total = 0
        repeat(trials) {
            val prefs = FakePrefs()
            val dao = StubDao(rats = mutableListOf(rat(id = 1)))
            GameEngine.onSteps(dao, prefs, 0f)
            total += walk(dao, prefs, steps = 1_000)
        }

        val mean = total.toDouble() / trials
        assertTrue(
            "expected about 2 encounters per 1000 steps, measured $mean",
            mean in 1.0..3.5
        )
    }

    @Test
    fun `batched delivery loses encounters to the one-per-batch cap`() {
        val trials = 400

        fun meanFor(stepsPerBatch: Int): Double {
            var total = 0
            repeat(trials) {
                val prefs = FakePrefs()
                val dao = StubDao(rats = mutableListOf(rat(id = 1)))
                GameEngine.onSteps(dao, prefs, 0f)
                total += walk(dao, prefs, steps = 1_000, stepsPerBatch = stepsPerBatch)
            }
            return total.toDouble() / trials
        }

        val perStep = meanFor(1)
        val oneBatch = meanFor(1_000)

        assertTrue(
            "a single 1000-step delivery cannot raise more than one encounter",
            oneBatch <= 1.0
        )
        assertTrue(
            "per-step delivery ($perStep) should out-produce one batch ($oneBatch)",
            perStep > oneBatch
        )
    }

    private fun rat(id: Long) = RatEntity(
        id = id,
        artKey = "bolt_pic",
        name = "Bolt",
        power = 3,
        toughness = 3,
        shiny = false
    )
}

/**
 * A RatDao that answers from a list.
 *
 * Separate from the fake in HatchBoostTest, which reports nothing available on
 * purpose so its hatch assertions are never disturbed by an encounter.
 */
private class StubDao(
    private val rats: MutableList<RatEntity>,
    /** Reports the whole roster knocked out, to exercise the no-fighter gate. */
    private val allRecovering: Boolean = false
) : RatDao {

    private var nextId = 100L

    override fun insert(rat: RatEntity): Long {
        val id = nextId++
        rats += rat.copy(id = id)
        return id
    }

    override fun insertAll(rats: List<RatEntity>) {
        rats.forEach { insert(it) }
    }

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
        if (allRecovering) null
        else rats.filter { !it.isRecovering(now) }.maxByOrNull { it.score }


    override fun recordWin(id: Long) = replace(id) { it.copy(wins = it.wins + 1) }

    override fun recordLoss(id: Long, until: Long) =
        replace(id) { it.copy(losses = it.losses + 1, recoveringUntil = until) }

    override fun revive(id: Long) = replace(id) { it.copy(recoveringUntil = 0) }
    override fun update(rat: RatEntity) = replace(rat.id) { rat }

    override fun delete(rats: List<RatEntity>) {
        val ids = rats.map { it.id }.toSet()
        this.rats.removeAll { it.id in ids }
    }

    override fun deleteAll() {
        rats.clear()
    }

    private fun replace(id: Long, change: (RatEntity) -> RatEntity) {
        val index = rats.indexOfFirst { it.id == id }
        if (index >= 0) rats[index] = change(rats[index])
    }
}
