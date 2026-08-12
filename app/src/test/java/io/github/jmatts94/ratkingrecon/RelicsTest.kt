package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Relic counts, and the migration off the old Set<String>.
 *
 * The migration is the part worth pinning down. Relics used to be stored as
 * their display names in a set, so every existing save holds one, and getting
 * this wrong silently deletes something a player earned rather than failing
 * loudly.
 */
class RelicsTest {

    private val gear = Relics.ALL[0]
    private val vial = Relics.ALL[1]

    // ---- counting ------------------------------------------------------------

    @Test
    fun `duplicates accumulate instead of being discarded`() {
        val prefs = FakePrefs()

        repeat(5) {
            val editor = prefs.edit()
            Relics.grant(prefs, editor, gear)
            editor.apply()
        }

        assertEquals(5, Relics.countOf(prefs, gear))
        assertEquals(5, Relics.total(prefs))
        assertEquals("five of one kind is still one kind", 1, Relics.typesHeld(prefs))
    }

    @Test
    fun `spending takes only what it needs and refuses when short`() {
        val prefs = FakePrefs()
        val editor = prefs.edit()
        Relics.grant(prefs, editor, gear, amount = 4)
        editor.apply()

        assertTrue(Relics.spend(prefs, gear, 3))
        assertEquals(1, Relics.countOf(prefs, gear))

        assertFalse("should not go negative", Relics.spend(prefs, gear, 3))
        assertEquals("a refused spend must change nothing", 1, Relics.countOf(prefs, gear))
    }

    @Test
    fun `relics of different kinds are counted apart`() {
        val prefs = FakePrefs()
        val editor = prefs.edit()
        Relics.grant(prefs, editor, gear, amount = 2)
        Relics.grant(prefs, editor, vial, amount = 3)
        editor.apply()

        assertEquals(2, Relics.countOf(prefs, gear))
        assertEquals(3, Relics.countOf(prefs, vial))
        assertEquals(5, Relics.total(prefs))
        assertEquals(2, Relics.typesHeld(prefs))
    }

    // ---- migration -----------------------------------------------------------

    @Test
    fun `a legacy set becomes one of each relic it held`() {
        val prefs = FakePrefs()
        prefs.edit()
            .putStringSet(Relics.KEY_LEGACY, mutableSetOf(gear.legacyName, vial.legacyName))
            .apply()

        assertEquals(1, Relics.countOf(prefs, gear))
        assertEquals(1, Relics.countOf(prefs, vial))
        assertEquals(2, Relics.total(prefs))
    }

    @Test
    fun `the legacy key is cleared, so the migration cannot run twice`() {
        val prefs = FakePrefs()
        prefs.edit()
            .putStringSet(Relics.KEY_LEGACY, mutableSetOf(gear.legacyName))
            .apply()

        Relics.migrateIfNeeded(prefs)
        assertFalse(prefs.contains(Relics.KEY_LEGACY))

        // Every read runs the migration, so a stale key would keep adding.
        repeat(5) { Relics.total(prefs) }
        assertEquals(1, Relics.countOf(prefs, gear))
    }

    @Test
    fun `migration adds to counts already held rather than replacing them`() {
        val prefs = FakePrefs()
        val editor = prefs.edit()
        Relics.grant(prefs, editor, gear, amount = 2)
        editor.apply()

        // What importing an old export on top of a migrated save looks like.
        prefs.edit()
            .putStringSet(Relics.KEY_LEGACY, mutableSetOf(gear.legacyName))
            .apply()

        assertEquals(3, Relics.countOf(prefs, gear))
    }

    /**
     * The case a one-shot migration flag would have got wrong: SaveTransfer
     * round-trips raw preferences, so an old export carries the old set back in.
     */
    @Test
    fun `an old save imported after migrating is still migrated`() {
        val prefs = FakePrefs()
        prefs.edit().putStringSet(Relics.KEY_LEGACY, mutableSetOf(vial.legacyName)).apply()
        Relics.migrateIfNeeded(prefs)
        assertEquals(1, Relics.countOf(prefs, vial))

        prefs.edit().putStringSet(Relics.KEY_LEGACY, mutableSetOf(vial.legacyName)).apply()

        assertEquals(2, Relics.countOf(prefs, vial))
        assertFalse(prefs.contains(Relics.KEY_LEGACY))
    }

    @Test
    fun `an unrecognised legacy name is dropped rather than guessed at`() {
        val prefs = FakePrefs()
        prefs.edit()
            .putStringSet(Relics.KEY_LEGACY, mutableSetOf("🐀 Something Else", gear.legacyName))
            .apply()

        assertEquals(1, Relics.total(prefs))
        assertEquals(1, Relics.countOf(prefs, gear))
    }

    @Test
    fun `no legacy key means nothing to migrate`() {
        val prefs = FakePrefs()
        Relics.migrateIfNeeded(prefs)
        assertEquals(0, Relics.total(prefs))
    }

    // ---- identity ------------------------------------------------------------

    @Test
    fun `relic ids and save keys are the ones that shipped`() {
        assertEquals(
            listOf("rusted_gear", "glowing_vial", "tattered_blueprint", "heavy_wrench"),
            Relics.ALL.map { it.id }
        )
        assertEquals("RELIC_rusted_gear", Relics.countKey(gear))
        assertEquals("RELICS", Relics.KEY_LEGACY)
    }

    @Test
    fun `every relic has a distinct id and legacy name`() {
        assertEquals(Relics.ALL.size, Relics.ALL.map { it.id }.distinct().size)
        assertEquals(Relics.ALL.size, Relics.ALL.map { it.legacyName }.distinct().size)
    }

    // ---- drop rates ----------------------------------------------------------

    @Test
    fun `the drop chance rises with the tier`() {
        assertEquals(0.10, LedgerTasks.M1.relicChance, 0.0001)
        assertEquals(0.25, LedgerTasks.M2.relicChance, 0.0001)
        assertEquals(0.50, LedgerTasks.M3.relicChance, 0.0001)

        assertTrue(
            "a longer task must never be the worse relic source",
            LedgerTasks.M1.relicChance < LedgerTasks.M2.relicChance &&
                LedgerTasks.M2.relicChance < LedgerTasks.M3.relicChance
        )
    }

    @Test
    fun `a roll only ever returns a relic from the roster`() {
        repeat(500) {
            Relics.rollFor(LedgerTasks.M3)?.let { assertTrue(it in Relics.ALL) }
        }
    }
}
