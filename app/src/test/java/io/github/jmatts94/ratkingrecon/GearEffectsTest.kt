package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gear ownership, equip state, and what every reader is worth with nothing
 * equipped at all - the same "true no-op by default" guarantee
 * [PermanentBuffsTest] already pins down for its own four readers.
 */
class GearEffectsTest {

    // ---- defaults: nothing equipped changes nothing -----------------------------

    @Test
    fun `every reader is a true no-op with nothing equipped`() {
        val prefs = FakePrefs()

        assertEquals(Loadout.NONE, GearEffects.combatLoadoutFor(prefs))
        assertEquals(GearEffects.FactionBonuses(), GearEffects.factionBonusesFor(prefs, Roster.BRAWLERS))
        assertEquals(1.0, GearEffects.scrapRunDurationMultiplierFor(prefs), 0.0001)
        assertEquals(1.0, GearEffects.stepExpMultiplierFor(prefs), 0.0001)
        assertEquals(0.0, GearEffects.passiveHealFractionFor(prefs), 0.0001)
    }

    @Test
    fun `nothing is owned or equipped on a fresh save`() {
        val prefs = FakePrefs()
        for (piece in GearPieces.all) {
            assertFalse(GearEffects.owns(prefs, piece.id))
            assertFalse(GearEffects.isEquipped(prefs, piece.id))
        }
        assertNull(GearEffects.equippedTool(prefs))
        assertNull(GearEffects.equippedTrinket(prefs))
    }

    // ---- crafting -----------------------------------------------------------------

    @Test
    fun `crafting refuses without enough relics, and spends nothing`() {
        val prefs = FakePrefs()
        val refusal = GearEffects.craft(prefs, GearPieces.RUSTED_GAUNTLET)

        assertTrue(refusal is GearCraftRefusal.NotEnough)
        assertFalse(GearEffects.owns(prefs, GearPieces.RUSTED_GAUNTLET.id))
        assertEquals(0, Relics.countOf(prefs, Relics.byId("rusted_gear")!!))
    }

    @Test
    fun `crafting with enough relics spends them, owns the piece, and equips it`() {
        val prefs = FakePrefs()
        val editor = prefs.edit()
        Relics.grant(prefs, editor, Relics.byId("rusted_gear")!!, 4)
        editor.apply()

        val refusal = GearEffects.craft(prefs, GearPieces.RUSTED_GAUNTLET)

        assertNull(refusal)
        assertTrue(GearEffects.owns(prefs, GearPieces.RUSTED_GAUNTLET.id))
        assertEquals(GearPieces.RUSTED_GAUNTLET.id, GearEffects.equippedTool(prefs))
        assertEquals(0, Relics.countOf(prefs, Relics.byId("rusted_gear")!!))
    }

    @Test
    fun `a two-relic recipe takes neither relic if either is short`() {
        // Smuggler's Lockpick costs 3 Tattered Blueprint alone, so borrow a
        // multi-relic shape by checking the same all-or-nothing rule against
        // a piece with exactly one ingredient short by one.
        val prefs = FakePrefs()
        val editor = prefs.edit()
        Relics.grant(prefs, editor, Relics.byId("tattered_blueprint")!!, 2)
        editor.apply()

        val refusal = GearEffects.craft(prefs, GearPieces.SMUGGLERS_LOCKPICK)

        assertTrue(refusal is GearCraftRefusal.NotEnough)
        assertEquals(2, Relics.countOf(prefs, Relics.byId("tattered_blueprint")!!))
    }

    @Test
    fun `crafting a second TOOL replaces the first, not stacks it`() {
        val prefs = FakePrefs()
        val editor = prefs.edit()
        Relics.grant(prefs, editor, Relics.byId("rusted_gear")!!, 4)
        Relics.grant(prefs, editor, Relics.byId("tattered_blueprint")!!, 3)
        editor.apply()

        GearEffects.craft(prefs, GearPieces.RUSTED_GAUNTLET)
        GearEffects.craft(prefs, GearPieces.SMUGGLERS_LOCKPICK)

        // Both owned - crafting never revokes ownership - but only the most
        // recently crafted TOOL is worn, the same one-equipped-frame rule
        // ShopEffects.grantCosmetic already follows.
        assertTrue(GearEffects.owns(prefs, GearPieces.RUSTED_GAUNTLET.id))
        assertTrue(GearEffects.owns(prefs, GearPieces.SMUGGLERS_LOCKPICK.id))
        assertEquals(GearPieces.SMUGGLERS_LOCKPICK.id, GearEffects.equippedTool(prefs))
    }

    // ---- toggling -----------------------------------------------------------------

    @Test
    fun `equipping the piece already worn stands it down again`() {
        val prefs = FakePrefs()
        val editor = prefs.edit()
        Relics.grant(prefs, editor, Relics.byId("rusted_gear")!!, 4)
        editor.apply()
        GearEffects.craft(prefs, GearPieces.RUSTED_GAUNTLET)

        assertEquals(GearPieces.RUSTED_GAUNTLET.id, GearEffects.equippedTool(prefs))
        GearEffects.toggleEquipped(prefs, GearPieces.RUSTED_GAUNTLET)
        assertNull(GearEffects.equippedTool(prefs))
        GearEffects.toggleEquipped(prefs, GearPieces.RUSTED_GAUNTLET)
        assertEquals(GearPieces.RUSTED_GAUNTLET.id, GearEffects.equippedTool(prefs))
    }

    @Test
    fun `a TOOL and a TRINKET are independent slots`() {
        val prefs = FakePrefs()
        val editor = prefs.edit()
        Relics.grant(prefs, editor, Relics.byId("rusted_gear")!!, 4)
        Relics.grant(prefs, editor, Relics.byId("heavy_wrench")!!, 3)
        editor.apply()

        GearEffects.craft(prefs, GearPieces.RUSTED_GAUNTLET)
        GearEffects.craft(prefs, GearPieces.COMPASS_CHARM)

        assertEquals(GearPieces.RUSTED_GAUNTLET.id, GearEffects.equippedTool(prefs))
        assertEquals(GearPieces.COMPASS_CHARM.id, GearEffects.equippedTrinket(prefs))
        assertTrue(GearEffects.isEquipped(prefs, GearPieces.RUSTED_GAUNTLET.id))
        assertTrue(GearEffects.isEquipped(prefs, GearPieces.COMPASS_CHARM.id))
    }

    // ---- readers, once equipped -----------------------------------------------------

    @Test
    fun `an equipped Rusted Gauntlet raises the combat Loadout's power only`() {
        val prefs = FakePrefs()
        equip(prefs, GearPieces.RUSTED_GAUNTLET)

        val loadout = GearEffects.combatLoadoutFor(prefs)
        assertEquals(1.08, loadout.powerMultiplier, 0.0001)
        assertEquals(1.0, loadout.hpMultiplier, 0.0001)
    }

    @Test
    fun `an equipped Reinforced Plating raises the combat Loadout's HP only`() {
        val prefs = FakePrefs()
        equip(prefs, GearPieces.REINFORCED_PLATING_GEAR)

        val loadout = GearEffects.combatLoadoutFor(prefs)
        assertEquals(1.0, loadout.powerMultiplier, 0.0001)
        assertEquals(1.08, loadout.hpMultiplier, 0.0001)
    }

    @Test
    fun `faction synergy only applies to the matching faction`() {
        val prefs = FakePrefs()
        equip(prefs, GearPieces.SMUGGLERS_LOCKPICK)

        val onSmuggler = GearEffects.factionBonusesFor(prefs, Roster.SMUGGLERS)
        assertEquals(0.05, onSmuggler.windfallChanceBonus, 0.0001)
        assertEquals(0.0, onSmuggler.specialMultiplierBonus, 0.0001)
        assertEquals(0.0, onSmuggler.blockChanceBonus, 0.0001)

        val onBrawler = GearEffects.factionBonusesFor(prefs, Roster.BRAWLERS)
        assertEquals(GearEffects.FactionBonuses(), onBrawler)
    }

    @Test
    fun `Brawler's Knuckles strengthens the Special multiplier, not a chance`() {
        val prefs = FakePrefs()
        equip(prefs, GearPieces.BRAWLERS_KNUCKLES)

        val bonuses = GearEffects.factionBonusesFor(prefs, Roster.BRAWLERS)
        assertEquals(0.1, bonuses.specialMultiplierBonus, 0.0001)
    }

    @Test
    fun `Compass Charm shortens the Scrap Run, Worn Pedometer boosts step EXP, Cracked Vial heals`() {
        val prefs = FakePrefs()
        equip(prefs, GearPieces.COMPASS_CHARM)
        assertEquals(0.90, GearEffects.scrapRunDurationMultiplierFor(prefs), 0.0001)
        assertEquals(1.0, GearEffects.stepExpMultiplierFor(prefs), 0.0001)

        equip(prefs, GearPieces.WORN_PEDOMETER)
        assertEquals(1.05, GearEffects.stepExpMultiplierFor(prefs), 0.0001)
        // Same slot as Compass Charm - crafting Worn Pedometer replaced it.
        assertEquals(1.0, GearEffects.scrapRunDurationMultiplierFor(prefs), 0.0001)

        equip(prefs, GearPieces.CRACKED_VIAL)
        assertEquals(0.05, GearEffects.passiveHealFractionFor(prefs), 0.0001)
    }

    /** Grants exactly what [piece] costs and crafts it, for tests that only care about the effect afterwards. */
    private fun equip(prefs: FakePrefs, piece: GearPiece) {
        val editor = prefs.edit()
        for ((relicId, needed) in piece.craftCost) {
            Relics.grant(prefs, editor, Relics.byId(relicId)!!, needed)
        }
        editor.apply()
        val refusal = GearEffects.craft(prefs, piece)
        check(refusal == null) { "test setup failed to craft ${piece.id}: $refusal" }
    }
}
