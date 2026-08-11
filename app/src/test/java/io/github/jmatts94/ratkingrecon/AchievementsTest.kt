package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifetime step counting, the milestones it drives, and the Golden Wrench.
 */
class AchievementsTest {

    // --- lifetime counting ----------------------------------------------------

    @Test
    fun `lifetime steps accumulate across batches`() {
        val prefs = FakePrefs()
        val dao = AchStubDao()

        GameEngine.onSteps(dao, prefs, 0f)
        GameEngine.onSteps(dao, prefs, 100f)
        GameEngine.onSteps(dao, prefs, 250f)

        assertEquals(250L, GameEngine.lifetimeStepsOf(prefs))
    }

    @Test
    fun `lifetime steps survive a reboot`() {
        val prefs = FakePrefs()
        val dao = AchStubDao()

        GameEngine.onSteps(dao, prefs, 40_000f)
        GameEngine.onSteps(dao, prefs, 40_500f)
        assertEquals(500L, GameEngine.lifetimeStepsOf(prefs))

        // The sensor restarts; the total must not.
        GameEngine.onSteps(dao, prefs, 0f)
        GameEngine.onSteps(dao, prefs, 300f)

        assertEquals(
            "the running total is the whole point - it must only ever climb",
            800L,
            GameEngine.lifetimeStepsOf(prefs)
        )
    }

    @Test
    fun `the sensor reading is not the lifetime total`() {
        val prefs = FakePrefs()
        val dao = AchStubDao()

        GameEngine.onSteps(dao, prefs, 90_000f)
        GameEngine.onSteps(dao, prefs, 90_100f)

        assertEquals(90_100f, GameEngine.totalStepsOf(prefs), 0.01f)
        assertEquals(100L, GameEngine.lifetimeStepsOf(prefs))
    }

    // --- the seed for saves that predate the counter --------------------------

    @Test
    fun `a fresh save seeds to zero`() {
        assertEquals(0L, GameEngine.seedLifetimeFor(1))
    }

    @Test
    fun `an existing save seeds from its level`() {
        // 50 EXP per level, one step per EXP: level 6 cost at least 50+100+150+200+250.
        assertEquals(750L, GameEngine.seedLifetimeFor(6))
        assertEquals(2_250L, GameEngine.seedLifetimeFor(10))
    }

    @Test
    fun `the seed is applied once and then left alone`() {
        val prefs = FakePrefs()
        val dao = AchStubDao()
        prefs.values[GameEngine.KEY_LEVEL] = 6

        GameEngine.onSteps(dao, prefs, 0f)
        assertEquals(750L, GameEngine.lifetimeStepsOf(prefs))

        GameEngine.onSteps(dao, prefs, 50f)
        assertEquals("the seed must not be reapplied", 800L, GameEngine.lifetimeStepsOf(prefs))
    }

    @Test
    fun `the seed never overwrites a real total`() {
        val prefs = FakePrefs()
        val dao = AchStubDao()
        prefs.values[GameEngine.KEY_LEVEL] = 40
        prefs.values[GameEngine.KEY_LIFETIME_STEPS] = 12L

        GameEngine.onSteps(dao, prefs, 0f)
        assertEquals(12L, GameEngine.lifetimeStepsOf(prefs))
    }

    // --- milestones -----------------------------------------------------------

    @Test
    fun `milestones are ordered and match the brief`() {
        assertEquals(
            listOf(10_000L, 50_000L, 100_000L, 500_000L, 1_000_000L),
            Milestones.all.map { it.steps }
        )
    }

    @Test
    fun `a milestone unlocks exactly at its threshold`() {
        val first = Milestones.all.first()
        assertFalse(Milestones.reached(9_999L, first))
        assertTrue(Milestones.reached(10_000L, first))
        assertTrue(Milestones.reached(10_001L, first))
    }

    @Test
    fun `reached count climbs with the total`() {
        assertEquals(0, Milestones.reachedCount(0L))
        assertEquals(1, Milestones.reachedCount(10_000L))
        assertEquals(3, Milestones.reachedCount(120_000L))
        assertEquals(5, Milestones.reachedCount(2_000_000L))
    }

    @Test
    fun `progress is clamped to a whole percent`() {
        val last = Milestones.all.last()
        assertEquals(0, Milestones.percentTowards(0L, last))
        assertEquals(50, Milestones.percentTowards(500_000L, last))
        assertEquals(100, Milestones.percentTowards(9_999_999L, last))
    }

    @Test
    fun `distance is derived from steps`() {
        assertEquals(7.62, Milestones.kilometresFor(10_000L), 0.01)
    }

    // --- the Golden Wrench ----------------------------------------------------

    @Test
    fun `no buffs leaves the rat untouched`() {
        val prefs = FakePrefs()
        val loadout = ShopEffects.loadoutFor(prefs)
        assertEquals(3, loadout.powerFor(3))
        assertEquals(30, loadout.maxHpFor(30))
    }

    @Test
    fun `the wrench multiplies power and staying power`() {
        val prefs = FakePrefs()
        prefs.values[ShopEffects.KEY_GOLDEN_WRENCH] = true

        val loadout = ShopEffects.loadoutFor(prefs)
        assertEquals(5, loadout.powerFor(3))
        assertEquals(45, loadout.maxHpFor(30))
    }

    @Test
    fun `the wrench stacks on top of a power surge`() {
        val prefs = FakePrefs()
        prefs.values[ShopEffects.KEY_POWER_SURGE] = true
        prefs.values[ShopEffects.KEY_GOLDEN_WRENCH] = true

        val loadout = ShopEffects.loadoutFor(prefs)
        // The flat bonus lands first, then the multiplier: (3 + 3) * 1.5.
        assertEquals(9, loadout.powerFor(3))
        assertEquals(45, loadout.maxHpFor(30))
    }

    @Test
    fun `a surge alone is still flat`() {
        val prefs = FakePrefs()
        prefs.values[ShopEffects.KEY_POWER_SURGE] = true

        val loadout = ShopEffects.loadoutFor(prefs)
        assertEquals(6, loadout.powerFor(3))
        assertEquals("HP is untouched without a wrench", 30, loadout.maxHpFor(30))
    }

    @Test
    fun `a finished fight burns both buffs`() {
        val prefs = FakePrefs()
        prefs.values[ShopEffects.KEY_POWER_SURGE] = true
        prefs.values[ShopEffects.KEY_GOLDEN_WRENCH] = true

        ShopEffects.clearOneShotBuffs(prefs)

        assertFalse(ShopEffects.powerSurgeArmed(prefs))
        assertFalse(ShopEffects.wrenchArmed(prefs))
    }

    @Test
    fun `a loadout never drops a stat below one`() {
        val crushing = Loadout(bonusPower = -99, powerMultiplier = 0.0, hpMultiplier = 0.0)
        assertEquals(1, crushing.powerFor(3))
        assertEquals(1, crushing.maxHpFor(30))
    }

    @Test
    fun `the wrench is priced above the power surge`() {
        val combat = Shop.categories.first { cat ->
            cat.items.any { it.effect == ShopEffect.Flag(ShopEffects.KEY_GOLDEN_WRENCH) }
        }
        val wrench = combat.items.first { it.effect == ShopEffect.Flag(ShopEffects.KEY_GOLDEN_WRENCH) }
        val surge = combat.items.first { it.effect == ShopEffect.Flag(ShopEffects.KEY_POWER_SURGE) }

        assertTrue("wrench ${wrench.price} vs surge ${surge.price}", wrench.price > surge.price)
    }
}

/** Minimal DAO: these tests never look at the collection. */
private class AchStubDao : RatDao {
    private val rats = mutableListOf<RatEntity>()
    private var nextId = 1L
    override fun insert(rat: RatEntity): Long {
        val id = nextId++; rats += rat.copy(id = id); return id
    }
    override fun insertAll(rats: List<RatEntity>) { rats.forEach { insert(it) } }
    override fun all(): List<RatEntity> = rats.toList()
    override fun byPowerDesc(): List<RatEntity> = rats
    override fun shinyOnly(): List<RatEntity> = rats.filter { it.shiny }
    override fun byId(id: Long): RatEntity? = rats.firstOrNull { it.id == id }
    override fun count(): Int = rats.size
    override fun distinctSpeciesFound(rosterKeys: List<String>): Int = 0
    override fun maxPower(): Int = 0
    override fun maxToughness(): Int = 0
    override fun ownsShiny(): Boolean = false
    override fun weakest(limit: Int): List<RatEntity> = emptyList()
    override fun strongestAvailable(now: Long): RatEntity? = null
    override fun availableCount(now: Long): Int = 0
    override fun recordWin(id: Long) = Unit
    override fun recordLoss(id: Long, until: Long) = Unit
    override fun revive(id: Long) = Unit
    override fun update(rat: RatEntity) = Unit
    override fun delete(rats: List<RatEntity>) = Unit
    override fun deleteAll() = rats.clear()
}
