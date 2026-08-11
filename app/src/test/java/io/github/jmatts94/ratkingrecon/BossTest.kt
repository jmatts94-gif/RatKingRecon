package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boss tiers, banking, and the badge that only the first win earns.
 */
class BossTest {

    // --- the roster -----------------------------------------------------------

    @Test
    fun `every level from 1 to 60 maps to at most one boss`() {
        (1..60).forEach { level ->
            val matches = Bosses.all.count { it.coversLevel(level) }
            assertTrue("level $level matched $matches bosses", matches <= 1)
        }
    }

    @Test
    fun `the tiers sit where the brief put them`() {
        assertNull("nothing before level 10", Bosses.forLevel(9))
        assertEquals("junk_golem", Bosses.forLevel(10)?.id)
        assertEquals("junk_golem", Bosses.forLevel(15)?.id)
        assertNull("a gap between tiers", Bosses.forLevel(16))
        assertEquals("old_ironclaw", Bosses.forLevel(20)?.id)
        assertEquals("boiler_baron", Bosses.forLevel(30)?.id)
        assertEquals("circuit_reaper", Bosses.forLevel(40)?.id)
        assertEquals("rustbringer", Bosses.forLevel(50)?.id)
        assertEquals("the last boss never runs out", "rustbringer", Bosses.forLevel(999)?.id)
    }

    @Test
    fun `ids are unique and stable`() {
        assertEquals(Bosses.all.size, Bosses.all.map { it.id }.distinct().size)
        assertEquals(
            listOf("junk_golem", "old_ironclaw", "boiler_baron", "circuit_reaper", "rustbringer"),
            Bosses.all.map { it.id }
        )
    }

    @Test
    fun `difficulty rises monotonically across the tiers`() {
        val powers = Bosses.all.map { it.powerMult }
        val hps = Bosses.all.map { it.hpMult }
        val rewards = Bosses.all.map { it.rewardMult }

        assertEquals(powers.sorted(), powers)
        assertEquals(hps.sorted(), hps)
        assertEquals(rewards.sorted(), rewards)
    }

    // --- stats ----------------------------------------------------------------

    @Test
    fun `a boss is never weaker than the standard bot it replaces`() {
        val rat = rat(power = 3, toughness = 3)
        Bosses.all.forEach { spec ->
            val level = spec.minLevel
            val standard = RustbotFactory.forEncounter(level, rat)
            val boss = Bosses.rustbotFor(spec, level, rat, "x")

            assertTrue(
                "${spec.id} power ${boss.power} < standard ${standard.power}",
                boss.power >= standard.power
            )
            assertTrue(
                "${spec.id} toughness ${boss.toughness} < standard ${standard.toughness}",
                boss.toughness >= standard.toughness
            )
        }
    }

    @Test
    fun `a boss scales off the rat, not the level alone`() {
        val weak = rat(power = 1, toughness = 1)
        val strong = rat(power = 10, toughness = 10)
        val spec = Bosses.all.last()

        val againstWeak = Bosses.rustbotFor(spec, 50, weak, "x")
        val againstStrong = Bosses.rustbotFor(spec, 50, strong, "x")

        assertTrue(
            "a spliced roster must face a bigger boss, or the ramp means nothing",
            againstStrong.power > againstWeak.power
        )
    }

    /**
     * The tuning regressions.
     *
     * Combat is deterministic, so these are exact statements about who beats
     * what, not statistical ones. They exist because an earlier set of
     * multipliers made every boss unwinnable without buying an item, and
     * nothing in the code said so.
     */
    @Test
    fun `the first boss is beatable with no purchase at all`() {
        val spec = Bosses.all.first()
        val rat = rat(power = 3, toughness = 3)
        val bot = Bosses.rustbotFor(spec, spec.minLevel, rat, "boss")
        val battle = AutoResolver.resolve(
            Encounter(1, bot.name, bot.power, bot.toughness, 0, spec.id).toBattle(rat)
        )

        assertEquals(
            "an average rat must be able to meet the first boss unaided",
            BattleOutcome.PLAYER_WON,
            battle.outcome
        )
    }

    @Test
    fun `every boss is beatable with a Golden Wrench`() {
        val wrench = Loadout(
            powerMultiplier = ShopEffects.WRENCH_MULTIPLIER,
            hpMultiplier = ShopEffects.WRENCH_MULTIPLIER
        )

        listOf(rat(3, 3), rat(5, 5), rat(8, 8)).forEach { rat ->
            Bosses.all.forEach { spec ->
                val bot = Bosses.rustbotFor(spec, spec.minLevel, rat, "boss")
                val battle = AutoResolver.resolve(
                    Encounter(1, bot.name, bot.power, bot.toughness, 0, spec.id)
                        .toBattle(rat, wrench)
                )
                assertEquals(
                    "${spec.id} unbeatable for a ${rat.power}/${rat.toughness} rat even with a Wrench",
                    BattleOutcome.PLAYER_WON,
                    battle.outcome
                )
            }
        }
    }

    @Test
    fun `no multiplier strays back into the unwinnable band`() {
        Bosses.all.forEach {
            assertTrue("${it.id} powerMult ${it.powerMult}", it.powerMult <= 1.5)
            assertTrue("${it.id} hpMult ${it.hpMult}", it.hpMult <= 1.5)
        }
    }

    // --- rewards --------------------------------------------------------------

    @Test
    fun `a repeat win pays about half`() {
        val spec = Bosses.all.first()
        // rewardFor has a random band, so compare the bounds rather than one roll.
        repeat(200) {
            val first = Bosses.rewardFor(spec, 12, alreadyDefeated = false)
            val repeat = Bosses.rewardFor(spec, 12, alreadyDefeated = true)
            assertTrue(first > 0 && repeat > 0)
        }

        val firstMean = (1..500).sumOf { Bosses.rewardFor(spec, 12, false) } / 500.0
        val repeatMean = (1..500).sumOf { Bosses.rewardFor(spec, 12, true) } / 500.0
        val ratio = repeatMean / firstMean

        assertTrue("repeat/first was $ratio", ratio in 0.4..0.6)
    }

    @Test
    fun `a boss pays more than a standard Rustbot`() {
        val spec = Bosses.all.first()
        val standard = (1..500).sumOf { RustbotFactory.rewardFor(12) } / 500.0
        val boss = (1..500).sumOf { Bosses.rewardFor(spec, 12, false) } / 500.0
        assertTrue("boss $boss vs standard $standard", boss > standard * 2)
    }

    // --- badges ---------------------------------------------------------------

    @Test
    fun `only the first win earns the badge`() {
        val prefs = FakePrefs()
        val id = Bosses.all.first().id

        assertFalse(Bosses.isDefeated(prefs, id))
        assertTrue("first win earns it", Bosses.markDefeated(prefs, id))
        assertTrue(Bosses.isDefeated(prefs, id))
        assertFalse("second win must not re-earn it", Bosses.markDefeated(prefs, id))
        assertFalse(Bosses.markDefeated(prefs, id))
    }

    @Test
    fun `badges are counted independently`() {
        val prefs = FakePrefs()
        assertEquals(0, Bosses.defeatedCount(prefs))
        Bosses.markDefeated(prefs, "junk_golem")
        Bosses.markDefeated(prefs, "rustbringer")
        assertEquals(2, Bosses.defeatedCount(prefs))
        assertFalse(Bosses.isDefeated(prefs, "old_ironclaw"))
    }

    // --- banking --------------------------------------------------------------

    @Test
    fun `walking in range eventually banks a boss`() {
        val prefs = FakePrefs()
        val dao = BossStubDao(mutableListOf(rat(3, 3)))

        var counter = 0f
        var banked: BossSpec? = null
        while (banked == null && counter < 200_000f) {
            counter += 1f
            // Pinned every batch. Left to run, the walk levels the player out of
            // the Junk Golem's band after about 2700 steps, which made this
            // assertion fail roughly one run in three.
            prefs.values[GameEngine.KEY_LEVEL] = 12
            prefs.values[GameEngine.KEY_EXP] = 0
            banked = GameEngine.onSteps(dao, prefs, counter).bossBanked
        }

        assertNotNull("no boss banked in 200000 steps at level 12", banked)
        assertEquals("junk_golem", banked?.id)
        assertEquals("junk_golem", Bosses.bankedId(prefs))
    }

    @Test
    fun `no boss is banked between tiers`() {
        val prefs = FakePrefs()
        val dao = BossStubDao(mutableListOf(rat(3, 3)))
        // Level 17 sits in the gap between the Junk Golem and Old Ironclaw. The
        // level is re-read each batch, so hold it there by keeping EXP low.
        prefs.values[GameEngine.KEY_LEVEL] = 17

        var counter = 0f
        repeat(20_000) {
            counter += 1f
            prefs.values[GameEngine.KEY_LEVEL] = 17
            prefs.values[GameEngine.KEY_EXP] = 0
            assertNull(GameEngine.onSteps(dao, prefs, counter).bossBanked)
        }
        assertNull(Bosses.bankedId(prefs))
    }

    @Test
    fun `only one boss banks at a time`() {
        val prefs = FakePrefs()
        val dao = BossStubDao(mutableListOf(rat(3, 3)))
        prefs.values[GameEngine.KEY_LEVEL] = 12
        Bosses.bank(prefs, Bosses.all.first())

        var counter = 0f
        repeat(20_000) {
            counter += 1f
            prefs.values[GameEngine.KEY_LEVEL] = 12
            prefs.values[GameEngine.KEY_EXP] = 0
            assertNull(
                "a second boss must not bank over the first",
                GameEngine.onSteps(dao, prefs, counter).bossBanked
            )
        }
    }

    @Test
    fun `banking is rarer than ordinary encounters`() {
        assertTrue(Bosses.CHANCE_PER_STEP < 0.0025)
    }

    // --- starting a banked boss ----------------------------------------------

    @Test
    fun `starting a banked boss makes it the pending encounter`() {
        val prefs = FakePrefs()
        val dao = BossStubDao(mutableListOf(rat(3, 3)))
        prefs.values[GameEngine.KEY_LEVEL] = 12
        Bosses.bank(prefs, Bosses.all.first())

        val started = Bosses.startBanked(dao, prefs) { "The Junk Golem" }

        assertEquals("junk_golem", started?.id)
        assertNull("the bank must be emptied", Bosses.bankedId(prefs))

        val pending = Encounter.load(prefs)
        assertNotNull(pending)
        assertTrue(pending!!.isBoss)
        assertEquals("junk_golem", pending.bossId)
        assertEquals("The Junk Golem", pending.botName)
    }

    @Test
    fun `a banked boss waits while an ordinary encounter is pending`() {
        val prefs = FakePrefs()
        val dao = BossStubDao(mutableListOf(rat(3, 3)))
        prefs.values[GameEngine.KEY_LEVEL] = 12

        Encounter.save(
            prefs,
            Encounter(ratId = 1, botName = "Rustbot Sentry", botPower = 3, botToughness = 3, reward = 10)
        )
        Bosses.bank(prefs, Bosses.all.first())

        assertNull(Bosses.startBanked(dao, prefs) { "x" })
        assertEquals("the boss stays banked", "junk_golem", Bosses.bankedId(prefs))
        assertFalse("the ordinary fight is untouched", Encounter.load(prefs)!!.isBoss)
    }

    @Test
    fun `a banked boss waits while every rat is knocked out`() {
        val prefs = FakePrefs()
        val dao = BossStubDao(mutableListOf(rat(3, 3)), allRecovering = true)
        prefs.values[GameEngine.KEY_LEVEL] = 12
        Bosses.bank(prefs, Bosses.all.first())

        assertNull(Bosses.startBanked(dao, prefs) { "x" })
        assertEquals("junk_golem", Bosses.bankedId(prefs))
    }

    @Test
    fun `a beaten boss can be banked and fought again`() {
        val prefs = FakePrefs()
        val dao = BossStubDao(mutableListOf(rat(3, 3)))
        prefs.values[GameEngine.KEY_LEVEL] = 12
        Bosses.markDefeated(prefs, "junk_golem")
        Bosses.bank(prefs, Bosses.all.first())

        assertNotNull(Bosses.startBanked(dao, prefs) { "x" })
        assertTrue(Encounter.load(prefs)!!.isBoss)
    }

    // --- the boss id must not leak -------------------------------------------

    @Test
    fun `an ordinary encounter saved over a boss is not a boss`() {
        val prefs = FakePrefs()
        Encounter.save(
            prefs,
            Encounter(1, "The Junk Golem", 5, 5, 90, bossId = "junk_golem")
        )
        assertTrue(Encounter.load(prefs)!!.isBoss)

        Encounter.save(prefs, Encounter(1, "Rustbot Crawler", 3, 3, 12))
        assertFalse(
            "a stale boss id would award a badge for an ordinary Rustbot",
            Encounter.load(prefs)!!.isBoss
        )
    }

    @Test
    fun `clearing an encounter drops the boss id`() {
        val prefs = FakePrefs()
        Encounter.save(prefs, Encounter(1, "The Junk Golem", 5, 5, 90, bossId = "junk_golem"))
        Encounter.clear(prefs)
        Encounter.save(prefs, Encounter(1, "Rustbot Crawler", 3, 3, 12))
        assertNull(Encounter.load(prefs)!!.bossId)
    }

    private fun rat(power: Int, toughness: Int) = RatEntity(
        id = 1, artKey = "bolt_pic", name = "Bolt",
        power = power, toughness = toughness, shiny = false
    )
}

private class BossStubDao(
    private val rats: MutableList<RatEntity>,
    private val allRecovering: Boolean = false
) : RatDao {
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
    override fun maxPower(): Int = rats.maxOfOrNull { it.power } ?: 0
    override fun maxToughness(): Int = rats.maxOfOrNull { it.toughness } ?: 0
    override fun ownsShiny(): Boolean = rats.any { it.shiny }
    override fun weakest(limit: Int): List<RatEntity> = rats.sortedBy { it.score }.take(limit)
    override fun strongestAvailable(now: Long): RatEntity? =
        if (allRecovering) null else rats.filter { !it.isRecovering(now) }.maxByOrNull { it.score }
    override fun recordWin(id: Long) = replace(id) { it.copy(wins = it.wins + 1) }
    override fun recordLoss(id: Long, until: Long) =
        replace(id) { it.copy(losses = it.losses + 1, recoveringUntil = until) }
    override fun revive(id: Long) = replace(id) { it.copy(recoveringUntil = 0) }
    override fun update(rat: RatEntity) = replace(rat.id) { rat }
    override fun delete(rats: List<RatEntity>) {
        val ids = rats.map { it.id }.toSet(); this.rats.removeAll { it.id in ids }
    }
    override fun deleteAll() { rats.clear() }
    private fun replace(id: Long, change: (RatEntity) -> RatEntity) {
        val i = rats.indexOfFirst { it.id == id }; if (i >= 0) rats[i] = change(rats[i])
    }
}
