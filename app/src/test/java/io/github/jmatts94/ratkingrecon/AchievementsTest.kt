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
        // 250 EXP per level, one step per EXP: level 6 cost at least 250+500+750+1000+1250.
        assertEquals(3_750L, GameEngine.seedLifetimeFor(6))
        assertEquals(11_250L, GameEngine.seedLifetimeFor(10))
    }

    @Test
    fun `the seed is applied once and then left alone`() {
        val prefs = FakePrefs()
        val dao = AchStubDao()
        prefs.values[GameEngine.KEY_LEVEL] = 6

        GameEngine.onSteps(dao, prefs, 0f)
        assertEquals(3_750L, GameEngine.lifetimeStepsOf(prefs))

        GameEngine.onSteps(dao, prefs, 50f)
        assertEquals("the seed must not be reapplied", 3_800L, GameEngine.lifetimeStepsOf(prefs))
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
    fun `the three categories match the brief`() {
        assertEquals(
            // The last two share a target on purpose now - "steps_1m" (Master
            // Courier's own milestone) was lowered from 1,000,000 to 100,000,
            // the same target "steps_100k" (Wayfarer) already uses. Both fire
            // together at 100,000 steps rather than one gating the other.
            listOf(10_000L, 50_000L, 100_000L, 250_000L, 500_000L, 100_000L),
            Milestones.steps.map { it.target }
        )
        assertEquals(
            // Roster.hatchable, not Roster.all - Full Collection must stay
            // reachable by walking and splicing alone, so TimeTail's Secret-tier
            // entry does not count towards it. See Roster.hatchable.
            listOf(10L, 25L, 50L, Roster.hatchable.size.toLong()),
            Milestones.roster.map { it.target }
        )
        assertEquals(
            listOf("hatch_shiny", "hatch_masterwork", "hatch_stat15", "hatch_stat25"),
            Milestones.hatching.map { it.id }
        )
        assertEquals(
            listOf("splice_first", "splice_10", "splice_lucky", "splice_25"),
            Milestones.splicing.map { it.id }
        )
        // 18 ordinary milestones plus Milestones.secret's one hidden entry.
        assertEquals(19, Milestones.all.size)
    }

    @Test
    fun `milestone ids are unique - they are save keys`() {
        assertEquals(Milestones.all.size, Milestones.all.map { it.id }.distinct().size)
    }

    @Test
    fun `Full Collection counts species, not rats held`() {
        val full = Milestones.roster.first { it.id == "roster_full" }
        assertEquals(MilestoneKind.SPECIES, full.kind)
        assertEquals(Roster.hatchable.size.toLong(), full.target)

        // A hoard of duplicates is not a full collection.
        val hoarder = MilestoneProgress(ratsHeld = 500, speciesFound = 4)
        assertFalse(Milestones.isMet(full, hoarder))
    }

    @Test
    fun `a milestone is met exactly at its threshold`() {
        val first = Milestones.steps.first()
        assertFalse(Milestones.isMet(first, MilestoneProgress(lifetimeSteps = 9_999L)))
        assertTrue(Milestones.isMet(first, MilestoneProgress(lifetimeSteps = 10_000L)))
        assertTrue(Milestones.isMet(first, MilestoneProgress(lifetimeSteps = 10_001L)))
    }

    @Test
    fun `progress is clamped to a whole percent`() {
        val last = Milestones.steps.last()
        assertEquals(0, Milestones.percentTowards(last, MilestoneProgress(lifetimeSteps = 0L)))
        assertEquals(50, Milestones.percentTowards(last, MilestoneProgress(lifetimeSteps = 50_000L)))
        assertEquals(100, Milestones.percentTowards(last, MilestoneProgress(lifetimeSteps = 9_999_999L)))
    }

    // --- latching, which is the whole point -----------------------------------

    @Test
    fun `refresh latches what has been met and returns only the new ones`() {
        val prefs = FakePrefs()
        val first = Milestones.refresh(prefs, MilestoneProgress(lifetimeSteps = 60_000L), AchStubDao())

        assertEquals(listOf("steps_10k", "steps_50k"), first.map { it.id })
        assertTrue(Milestones.isEarned(prefs, Milestones.steps[0]))
        assertEquals(2, Milestones.earnedCount(prefs))

        // Same progress again: nothing is newly earned.
        assertTrue(Milestones.refresh(prefs, MilestoneProgress(lifetimeSteps = 60_000L), AchStubDao()).isEmpty())
        assertEquals(2, Milestones.earnedCount(prefs))
    }

    /**
     * The reason milestones are latched rather than recomputed.
     *
     * The Fusion Pot burns two rats to mint one, so a roster shrinks as it is
     * used. Recomputing would take an earned badge back off a player for
     * playing the game.
     */
    @Test
    fun `a roster badge survives the roster shrinking`() {
        val prefs = FakePrefs()
        Milestones.refresh(prefs, MilestoneProgress(ratsHeld = 25), AchStubDao())

        val badge = Milestones.roster.first { it.id == "roster_25" }
        assertTrue(Milestones.isEarned(prefs, badge))

        // Splice it down to 11 rats.
        Milestones.refresh(prefs, MilestoneProgress(ratsHeld = 11), AchStubDao())
        assertTrue("an earned badge must never be revoked", Milestones.isEarned(prefs, badge))
    }

    @Test
    fun `First Shiny survives splicing the only shiny away`() {
        val prefs = FakePrefs()
        val shiny = Milestones.hatching.first { it.id == "hatch_shiny" }

        Milestones.refresh(prefs, MilestoneProgress(ownsShiny = true), AchStubDao())
        assertTrue(Milestones.isEarned(prefs, shiny))

        Milestones.refresh(prefs, MilestoneProgress(ownsShiny = false), AchStubDao())
        assertTrue(Milestones.isEarned(prefs, shiny))
    }

    @Test
    fun `a masterwork pull is recorded and latches its badge`() {
        val prefs = FakePrefs()
        val badge = Milestones.hatching.first { it.id == "hatch_masterwork" }
        assertFalse(Milestones.isEarned(prefs, badge))

        Milestones.recordMasterworkPull(prefs)
        Milestones.refresh(prefs, MilestoneProgress(masterworkPulled = true), AchStubDao())
        assertTrue(Milestones.isEarned(prefs, badge))
    }

    // --- the two stat milestones -----------------------------------------------

    @Test
    fun `readProgress finds a rat that clears the stat thresholds`() {
        val dao = AchStubDao()
        val prefs = FakePrefs()
        dao.insert(RatEntity(artKey = "no_such_species", name = "Test", power = 15, toughness = 15, shiny = false))

        val progress = Milestones.readProgress(dao, prefs)
        assertTrue(progress.hasStat15Rat)
        assertFalse("15/15 does not clear the higher 25/25 bar", progress.hasStat25Rat)
    }

    @Test
    fun `a stat milestone needs both stats at the threshold, not just one`() {
        val dao = AchStubDao()
        val prefs = FakePrefs()
        dao.insert(RatEntity(artKey = "no_such_species", name = "Lopsided", power = 25, toughness = 5, shiny = false))

        val progress = Milestones.readProgress(dao, prefs)
        assertFalse("power alone must not satisfy it", progress.hasStat15Rat)
        assertFalse(progress.hasStat25Rat)
    }

    @Test
    fun `hatch_stat15 and hatch_stat25 latch independently`() {
        val prefs = FakePrefs()
        val stat15 = Milestones.hatching.first { it.id == "hatch_stat15" }
        val stat25 = Milestones.hatching.first { it.id == "hatch_stat25" }

        Milestones.refresh(prefs, MilestoneProgress(hasStat15Rat = true, hasStat25Rat = false), AchStubDao())

        assertTrue(Milestones.isEarned(prefs, stat15))
        assertFalse(Milestones.isEarned(prefs, stat25))
    }

    @Test
    fun `the cheap step refresh latches steps and nothing else`() {
        val prefs = FakePrefs()
        // Deliberately passed a progress that would satisfy roster badges too;
        // the sensor-path refresh must not need or use them.
        Milestones.refreshSteps(prefs, 100_000L)

        assertTrue(Milestones.isEarned(prefs, Milestones.steps[2]))
        assertFalse(Milestones.isEarned(prefs, Milestones.roster[0]))
        // 10k/50k/100k, plus steps_1m - lowered to the same 100k target as
        // steps_100k, so both latch together here rather than one at a time.
        assertEquals(4, Milestones.earnedCount(prefs))
    }

    @Test
    fun `walking latches step milestones through onSteps`() {
        val prefs = FakePrefs()
        val dao = AchStubDao()

        GameEngine.onSteps(dao, prefs, 0f)
        GameEngine.onSteps(dao, prefs, 10_000f)

        assertTrue(
            "walking past 10k should have latched the first milestone",
            Milestones.isEarned(prefs, Milestones.steps.first())
        )
    }

    @Test
    fun `distance is derived from steps`() {
        assertEquals(7.62, Milestones.kilometresFor(10_000L), 0.01)
    }

    // --- splicing ---------------------------------------------------------------

    @Test
    fun `lifetime splices accumulate and never decrement`() {
        val prefs = FakePrefs()
        repeat(3) { Milestones.recordSplice(prefs) }

        assertEquals(3L, Milestones.readProgress(AchStubDao(), prefs).lifetimeSplices)
    }

    @Test
    fun `splice count milestones latch off the lifetime total`() {
        val prefs = FakePrefs()
        repeat(10) { Milestones.recordSplice(prefs) }

        val progress = Milestones.readProgress(AchStubDao(), prefs)
        Milestones.refresh(prefs, progress, AchStubDao())

        assertTrue(Milestones.isEarned(prefs, Milestones.splicing.first { it.id == "splice_first" }))
        assertTrue(Milestones.isEarned(prefs, Milestones.splicing.first { it.id == "splice_10" }))
        assertFalse(Milestones.isEarned(prefs, Milestones.splicing.first { it.id == "splice_25" }))
    }

    @Test
    fun `Lucky Break latches once a Tinkerer roll has fired, and survives the roster shrinking`() {
        val prefs = FakePrefs()
        val badge = Milestones.splicing.first { it.id == "splice_lucky" }
        assertFalse(Milestones.isEarned(prefs, badge))

        Milestones.recordTinkererTrigger(prefs)
        Milestones.refresh(prefs, Milestones.readProgress(AchStubDao(), prefs), AchStubDao())

        assertTrue(Milestones.isEarned(prefs, badge))
    }

    // --- TimeTail, the one secret achievement ----------------------------------

    @Test
    fun `TimeTail Found is the one hidden milestone`() {
        val secret = Milestones.secret.single()
        assertEquals("timetail_found", secret.id)
        assertTrue("must stay hidden until earned - see Milestone.hidden", secret.hidden)
        assertEquals(MilestoneKind.BOSS_LADDER, secret.kind)
        assertEquals(
            "gated on the full boss ladder, not some smaller number",
            Bosses.all.size.toLong(),
            secret.target
        )
    }

    @Test
    fun `TimeTail Found does not latch before every boss is beaten`() {
        val prefs = FakePrefs()
        val dao = AchStubDao()
        val progress = MilestoneProgress(bossesDefeated = Bosses.all.size - 1)

        Milestones.refresh(prefs, progress, dao)

        assertFalse(Milestones.isEarned(prefs, Milestones.secret.single()))
        assertTrue("no rat should have been granted early", dao.all().isEmpty())
    }

    @Test
    fun `defeating every boss latches TimeTail Found and mints the rat`() {
        val prefs = FakePrefs()
        val dao = AchStubDao()
        val progress = MilestoneProgress(bossesDefeated = Bosses.all.size)

        val newlyEarned = Milestones.refresh(prefs, progress, dao)

        assertEquals(listOf("timetail_found"), newlyEarned.map { it.id })
        assertTrue(Milestones.isEarned(prefs, Milestones.secret.single()))

        val minted = dao.all().singleOrNull { it.artKey == "timetail_pic" }
        assertTrue("TimeTail should have been inserted into the Ledger", minted != null)
        assertFalse("TimeTail is not one of the polish-roll shinies", requireNotNull(minted).shiny)
        assertTrue(
            "TimeTail should arrive at the Mutagen's own stat range, not the ordinary one",
            minted.power in GameEngine.MUTAGEN_STAT && minted.toughness in GameEngine.MUTAGEN_STAT
        )
    }

    @Test
    fun `TimeTail carries the Secret tier's stat bonus and gear count`() {
        val timetail = Roster.all.first { it.artKey == "timetail_pic" }
        assertEquals(Roster.SECRET, timetail.rarity)
        assertEquals("a step above Legendary's own +2", 3, Roster.statBonusFor(timetail.rarity))
        assertEquals(3, Roster.gearCountFor(timetail.rarity))
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
    fun `the two buffs are mutually exclusive, and the wrench wins`() {
        val prefs = FakePrefs()
        prefs.values[ShopEffects.KEY_POWER_SURGE] = true
        prefs.values[ShopEffects.KEY_GOLDEN_WRENCH] = true

        val loadout = ShopEffects.loadoutFor(prefs)
        // Not compounded: a save carrying both - imported from the build where
        // they stacked - gets the dearer item, not the product of the two.
        assertEquals(5, loadout.powerFor(3))
        assertEquals(45, loadout.maxHpFor(30))
    }

    @Test
    fun `the Shop refuses to sell a buff that could not apply`() {
        val prefs = FakePrefs()
        assertFalse(ShopEffects.conflictsWithArmedBuff(prefs, ShopEffects.KEY_POWER_SURGE))

        prefs.values[ShopEffects.KEY_GOLDEN_WRENCH] = true
        assertTrue(ShopEffects.conflictsWithArmedBuff(prefs, ShopEffects.KEY_POWER_SURGE))

        prefs.values.clear()
        prefs.values[ShopEffects.KEY_POWER_SURGE] = true
        assertTrue(ShopEffects.conflictsWithArmedBuff(prefs, ShopEffects.KEY_GOLDEN_WRENCH))
        assertFalse(
            "unrelated flags are not combat buffs",
            ShopEffects.conflictsWithArmedBuff(prefs, GameEngine.KEY_MUTAGEN)
        )
    }

    @Test
    fun `a surge raises Power by half and leaves HP alone`() {
        val prefs = FakePrefs()
        prefs.values[ShopEffects.KEY_POWER_SURGE] = true

        val loadout = ShopEffects.loadoutFor(prefs)
        assertEquals(5, loadout.powerFor(3))
        assertEquals("HP is untouched without a wrench", 30, loadout.maxHpFor(30))
    }

    /**
     * The reason the flat bonus was replaced.
     *
     * A flat +3 was worth +100% to a Power 3 rat and +38% to a Power 8 one, so
     * the better the roster got the less a Surge did - against bosses scaled off
     * that same rat, it got actively worse. A proportion cannot drift that way.
     */
    @Test
    fun `a surge is worth the same proportion at every rat size`() {
        val prefs = FakePrefs()
        prefs.values[ShopEffects.KEY_POWER_SURGE] = true
        val loadout = ShopEffects.loadoutFor(prefs)

        (2..10).forEach { base ->
            val ratio = loadout.powerFor(base).toDouble() / base
            assertTrue(
                "power $base scaled by $ratio, outside rounding tolerance of 1.5",
                ratio > 1.3 && ratio < 1.75
            )
        }
    }

    @Test
    fun `a surge is never rounded away to nothing`() {
        val prefs = FakePrefs()
        prefs.values[ShopEffects.KEY_POWER_SURGE] = true
        val loadout = ShopEffects.loadoutFor(prefs)

        (1..10).forEach { base ->
            assertTrue(
                "a Surge left a Power $base rat unchanged - it would read as broken",
                loadout.powerFor(base) > base
            )
        }
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
        val crushing = Loadout(powerMultiplier = 0.0, hpMultiplier = 0.0)
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

/** Minimal DAO. Most of these tests never look at the collection; the TimeTail ones do, via [all]. */
private class AchStubDao : RatDao {
    private val rats = mutableListOf<RatEntity>()
    private var nextId = 1L
    override fun insert(rat: RatEntity): Long {
        val id = nextId++; rats += rat.copy(id = id); return id
    }
    override fun insertAll(rats: List<RatEntity>) { rats.forEach { insert(it) } }
    override fun all(): List<RatEntity> = rats.toList()
    override fun byPowerDesc(): List<RatEntity> = rats
    override fun byId(id: Long): RatEntity? = rats.firstOrNull { it.id == id }
    override fun count(): Int = rats.size
    override fun distinctSpeciesFound(rosterKeys: List<String>): Int = 0
    override fun maxPowerExcluding(excludedId: Long): Int =
        0
    override fun maxToughnessExcluding(excludedId: Long): Int =
        0
    override fun ownsShiny(): Boolean = false
    override fun ownsShinyExcluding(excludedId: Long): Boolean =
        false
    override fun strongestAvailable(now: Long): RatEntity? = null
    override fun recordWin(id: Long) = Unit
    override fun recordLoss(id: Long, until: Long) = Unit
    override fun revive(id: Long) = Unit
    override fun delete(rats: List<RatEntity>) = Unit
    override fun deleteAll() = rats.clear()
}
