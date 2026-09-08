package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences

/**
 * Why a craft cannot go ahead - the same shape [RelicRefusal] already gives
 * the Relic Trader, so a caller can say why rather than a bare failure.
 */
sealed interface GearCraftRefusal {
    data class NotEnough(val relic: Relic, val held: Int, val needed: Int) : GearCraftRefusal
}

/**
 * The gear a player owns, has equipped, and what either means at the point
 * something reads it - the same three-part shape [ShopEffects] already keeps
 * for the Binder frame cosmetic (own/equip/read), and [PermanentBuffs] keeps
 * for its own four buffs (latch/read).
 *
 * Account-wide, not per-rat - see [GearPieces]' own class comment for why.
 * Two equipped ids, one per [GearSlot], both nullable. Owning a piece and it
 * being equipped are the same fact here, unlike a Binder frame: there is
 * nothing to browse-but-not-wear a second copy of, since crafting one is
 * already a real, spent cost.
 */
object GearEffects {

    private const val KEY_OWNED_PREFIX = "GEAR_OWNED_"
    private const val KEY_EQUIPPED_TOOL = "GEAR_EQUIPPED_TOOL"
    private const val KEY_EQUIPPED_TRINKET = "GEAR_EQUIPPED_TRINKET"

    // ---- ownership and equip ---------------------------------------------------

    fun owns(prefs: SharedPreferences, id: String): Boolean =
        prefs.getBoolean(KEY_OWNED_PREFIX + id, false)

    fun equippedTool(prefs: SharedPreferences): String? = prefs.getString(KEY_EQUIPPED_TOOL, null)
    fun equippedTrinket(prefs: SharedPreferences): String? = prefs.getString(KEY_EQUIPPED_TRINKET, null)

    fun isEquipped(prefs: SharedPreferences, id: String): Boolean =
        equippedTool(prefs) == id || equippedTrinket(prefs) == id

    private fun keyFor(slot: GearSlot): String = when (slot) {
        GearSlot.TOOL -> KEY_EQUIPPED_TOOL
        GearSlot.TRINKET -> KEY_EQUIPPED_TRINKET
    }

    /**
     * Why crafting [piece] cannot go ahead, or null when it can - checked
     * before anything is spent, the same "nothing leaves until the effect
     * has been accepted" rule [RelicTrader.refusalFor]/[ShopActivity]'s own
     * purchase path already keep. Every line of [GearPiece.craftCost] is
     * checked before any of them are spent, so a two-relic recipe can never
     * take the first relic and then fail on the second.
     */
    fun craftRefusalFor(prefs: SharedPreferences, piece: GearPiece): GearCraftRefusal? {
        for ((relicId, needed) in piece.craftCost) {
            val relic = Relics.byId(relicId) ?: continue
            val held = Relics.countOf(prefs, relic)
            if (held < needed) return GearCraftRefusal.NotEnough(relic, held, needed)
        }
        return null
    }

    /**
     * Crafts [piece]: spends every relic in its own [GearPiece.craftCost],
     * marks it owned, and equips it into its own slot - equipping on craft
     * for the same reason [ShopEffects.grantCosmetic] does: crafting
     * something and seeing nothing change would read as a broken
     * transaction. Returns the refusal that stopped it, or null once it has
     * gone through.
     */
    fun craft(prefs: SharedPreferences, piece: GearPiece): GearCraftRefusal? {
        craftRefusalFor(prefs, piece)?.let { return it }

        for ((relicId, needed) in piece.craftCost) {
            val relic = Relics.byId(relicId) ?: continue
            Relics.spend(prefs, relic, needed)
        }

        prefs.edit()
            .putBoolean(KEY_OWNED_PREFIX + piece.id, true)
            .putString(keyFor(piece.slot), piece.id)
            .apply()
        return null
    }

    /** Equipping the piece already worn in its own slot stands it down again - mirrors [ShopEffects.toggleEquipped]. */
    fun toggleEquipped(prefs: SharedPreferences, piece: GearPiece) {
        val key = keyFor(piece.slot)
        val next = if (prefs.getString(key, null) == piece.id) null else piece.id
        prefs.edit().putString(key, next).apply()
    }

    // ---- effect readers, read at the point of use ------------------------------

    /**
     * The equipped TOOL's flat combat bonus, as a [Loadout] - meant to be
     * stacked with whatever [ShopEffects.loadoutFor] already built via
     * [Loadout.combinedWith], not to replace it. Only reads a
     * [GearEffect.CombatStat] TOOL (Rusted Gauntlet, Reinforced Plating
     * Gear); a [GearEffect.FactionSynergy] TOOL's own bonus is not a flat
     * multiplier and is read by [factionBonusesFor] instead.
     */
    fun combatLoadoutFor(prefs: SharedPreferences): Loadout {
        val stat = (GearPieces.byId(equippedTool(prefs))?.effect as? GearEffect.CombatStat) ?: return Loadout.NONE
        return if (stat.boostsPower) {
            Loadout(powerMultiplier = stat.statMultiplier)
        } else {
            Loadout(hpMultiplier = stat.statMultiplier)
        }
    }

    /** The three faction-Special bonuses [Battle]'s constructor now takes, resolved for [rat]'s own faction. */
    data class FactionBonuses(
        val specialMultiplierBonus: Double = 0.0,
        val windfallChanceBonus: Double = 0.0,
        val blockChanceBonus: Double = 0.0
    )

    /**
     * Folds in whichever equipped piece - TOOL or TRINKET, gear does not
     * care which slot a [GearEffect.FactionSynergy] piece sits in - matches
     * [faction]. Takes the faction directly rather than a [RatEntity], the
     * same way [FactionSpecials]/[TaskBonuses] already do - the only thing
     * either ever needed off a rat for this. At most one of the three
     * launch synergy pieces can ever apply to a single rat, since a rat has
     * exactly one faction, but this is written as three independent checks
     * rather than an early-return `when` so a future faction with more
     * than one gear option is not blocked from stacking them.
     */
    fun factionBonusesFor(prefs: SharedPreferences, faction: String?): FactionBonuses {
        val pieces = listOfNotNull(GearPieces.byId(equippedTool(prefs)), GearPieces.byId(equippedTrinket(prefs)))
        var special = 0.0
        var windfall = 0.0
        var block = 0.0

        for (piece in pieces) {
            val synergy = piece.effect as? GearEffect.FactionSynergy ?: continue
            if (!synergy.faction.equals(faction, ignoreCase = true)) continue

            when (synergy.faction) {
                Roster.BRAWLERS -> special += synergy.chanceOrMultiplierBonus
                Roster.SMUGGLERS -> windfall += synergy.chanceOrMultiplierBonus
                Roster.TINKERERS -> block += synergy.chanceOrMultiplierBonus
            }
        }

        return FactionBonuses(special, windfall, block)
    }

    /** What the equipped TRINKET multiplies a Scrap Run's own duration by; 1.0 (no change) otherwise. */
    fun scrapRunDurationMultiplierFor(prefs: SharedPreferences): Double =
        (GearPieces.byId(equippedTrinket(prefs))?.effect as? GearEffect.ScrapRunDuration)?.multiplier ?: 1.0

    /**
     * What the equipped TRINKET multiplies a step batch's own banked EXP by;
     * 1.0 (no change) otherwise. Never read by [GameEngine.lifetimeStepsOf]/
     * [Milestones] - see [GearPieces.WORN_PEDOMETER]'s own comment.
     */
    fun stepExpMultiplierFor(prefs: SharedPreferences): Double =
        (GearPieces.byId(equippedTrinket(prefs))?.effect as? GearEffect.StepExp)?.multiplier ?: 1.0

    /** Extra share of max HP the equipped TRINKET tops up on top of [ArenaRun.ARENA_RELIEF_FRACTION]; 0.0 otherwise. */
    fun passiveHealFractionFor(prefs: SharedPreferences): Double =
        (GearPieces.byId(equippedTrinket(prefs))?.effect as? GearEffect.PassiveHeal)?.fraction ?: 0.0
}
