package io.github.jmatts94.ratkingrecon

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** Which of the two equip slots a piece takes - see [GearEffects]. */
enum class GearSlot { TOOL, TRINKET }

/**
 * What one piece of gear actually does, read at its own point of use rather
 * than centrally - the same split [CardFrame.style]/[FrameOverlayDrawable]
 * already draw between "what a thing is" (here) and "what happens because of
 * it" (the systems that read [GearEffects]'s own readers).
 */
sealed interface GearEffect {

    /**
     * A flat multiplier onto the rat's own Power or Toughness in battle -
     * folded into the same [Loadout] a Shop one-shot buff already builds,
     * via [Loadout.combinedWith].
     */
    data class CombatStat(val statMultiplier: Double, val boostsPower: Boolean) : GearEffect

    /**
     * A bonus onto one of the three faction-Special rolls [FactionSpecials]
     * already makes, only when the fighting rat's own faction is [faction] -
     * see [GearEffects.combatLoadoutFor] and the three bonus params
     * [Battle]'s constructor now takes.
     */
    data class FactionSynergy(val faction: String, val chanceOrMultiplierBonus: Double) : GearEffect

    /** A multiplier on top of [TaskBonuses.durationFor] for the Scrap Run specifically. */
    data class ScrapRunDuration(val multiplier: Double) : GearEffect

    /** A multiplier on the EXP a step batch banks - see [GameEngine.onSteps]. Never touches the step count itself. */
    data class StepExp(val multiplier: Double) : GearEffect

    /** A small share of max HP healed back between fights - see [EncounterResolver]/[Battle]. */
    data class PassiveHeal(val fraction: Double) : GearEffect
}

/**
 * One piece of gear.
 *
 * [id] reaches the save the same way [CardFrame.id]/[Milestone.id] do - as
 * both the ownership flag and (for [GearEffects.equippedTool]/
 * [GearEffects.equippedTrinket]) the equipped value - so it must never
 * change once shipped.
 *
 * [craftCost] is keyed on [Relic.id] rather than a [Relic] itself, the same
 * reason [ShopEffects]' keys are plain strings: it is what actually reaches
 * [SharedPreferences], and a [Relic] is a heavier thing to carry around for
 * that.
 */
data class GearPiece(
    val id: String,
    @param:StringRes val nameRes: Int,
    @param:StringRes val descRes: Int,
    @param:DrawableRes val iconRes: Int,
    val slot: GearSlot,
    val effect: GearEffect,
    val craftCost: Map<String, Int>
)

/**
 * Every gear piece, and the only place any of them is described - the same
 * shape [Frames] already keeps for Binder cosmetics.
 *
 * Account-wide, not per-rat: see the design note this shipped with. A TOOL's
 * combat bonus applies to whichever rat is actually fighting, the same way a
 * Shop-bought Loadout already does; a TRINKET's applies to whichever
 * activity it names (the Scrap Run, a step batch's own EXP).
 */
object GearPieces {

    // --- TOOL: combat-leaning ---

    val RUSTED_GAUNTLET = GearPiece(
        id = "rusted_gauntlet",
        nameRes = R.string.gear_name_rusted_gauntlet,
        descRes = R.string.gear_desc_rusted_gauntlet,
        iconRes = R.drawable.ic_power,
        slot = GearSlot.TOOL,
        effect = GearEffect.CombatStat(statMultiplier = 1.08, boostsPower = true),
        craftCost = mapOf("rusted_gear" to 4)
    )

    val REINFORCED_PLATING_GEAR = GearPiece(
        id = "reinforced_plating_gear",
        nameRes = R.string.gear_name_reinforced_plating,
        descRes = R.string.gear_desc_reinforced_plating,
        iconRes = R.drawable.ic_toughness,
        slot = GearSlot.TOOL,
        effect = GearEffect.CombatStat(statMultiplier = 1.08, boostsPower = false),
        craftCost = mapOf("rusted_gear" to 4)
    )

    val SMUGGLERS_LOCKPICK = GearPiece(
        id = "smugglers_lockpick",
        nameRes = R.string.gear_name_smugglers_lockpick,
        descRes = R.string.gear_desc_smugglers_lockpick,
        iconRes = R.drawable.ic_faction_smugglers,
        slot = GearSlot.TOOL,
        effect = GearEffect.FactionSynergy(Roster.SMUGGLERS, chanceOrMultiplierBonus = 0.05),
        craftCost = mapOf("tattered_blueprint" to 3)
    )

    val BRAWLERS_KNUCKLES = GearPiece(
        id = "brawlers_knuckles",
        nameRes = R.string.gear_name_brawlers_knuckles,
        descRes = R.string.gear_desc_brawlers_knuckles,
        iconRes = R.drawable.ic_faction_brawlers,
        slot = GearSlot.TOOL,
        // A flat +0.1 onto FactionSpecials.BRAWLER_MULTIPLIER's own 1.8x -
        // Brawlers have no secondary Special roll for a synergy piece to
        // buff the way the other three factions' gear does, so this
        // strengthens the one lever they actually have instead.
        effect = GearEffect.FactionSynergy(Roster.BRAWLERS, chanceOrMultiplierBonus = 0.1),
        craftCost = mapOf("tattered_blueprint" to 3)
    )

    // --- TRINKET: utility/walking-leaning ---

    val COMPASS_CHARM = GearPiece(
        id = "compass_charm",
        nameRes = R.string.gear_name_compass_charm,
        descRes = R.string.gear_desc_compass_charm,
        iconRes = R.drawable.ic_expedition,
        slot = GearSlot.TRINKET,
        effect = GearEffect.ScrapRunDuration(multiplier = 0.90),
        craftCost = mapOf("heavy_wrench" to 3)
    )

    val WORN_PEDOMETER = GearPiece(
        id = "worn_pedometer",
        nameRes = R.string.gear_name_worn_pedometer,
        descRes = R.string.gear_desc_worn_pedometer,
        iconRes = R.drawable.ic_footprint,
        slot = GearSlot.TRINKET,
        // Multiplies the EXP a step batch banks, same as
        // PermanentBuffs.STEADFAST_MOMENTUM_MULTIPLIER already does - never
        // the step count itself, and never read by Milestones/lifetime
        // steps. See GameEngine.onSteps.
        effect = GearEffect.StepExp(multiplier = 1.05),
        craftCost = mapOf("worn_cog" to 4)
    )

    val CRACKED_VIAL = GearPiece(
        id = "cracked_vial",
        nameRes = R.string.gear_name_cracked_vial,
        descRes = R.string.gear_desc_cracked_vial,
        iconRes = R.drawable.ic_flask,
        slot = GearSlot.TRINKET,
        effect = GearEffect.PassiveHeal(fraction = 0.05),
        craftCost = mapOf("glowing_vial" to 3)
    )

    val TINKERERS_LOUPE = GearPiece(
        id = "tinkerers_loupe",
        nameRes = R.string.gear_name_tinkerers_loupe,
        descRes = R.string.gear_desc_tinkerers_loupe,
        iconRes = R.drawable.ic_faction_tinkerers,
        slot = GearSlot.TRINKET,
        effect = GearEffect.FactionSynergy(Roster.TINKERERS, chanceOrMultiplierBonus = 0.05),
        craftCost = mapOf("tattered_blueprint" to 3)
    )

    val all: List<GearPiece> = listOf(
        RUSTED_GAUNTLET, REINFORCED_PLATING_GEAR, SMUGGLERS_LOCKPICK, BRAWLERS_KNUCKLES,
        COMPASS_CHARM, WORN_PEDOMETER, CRACKED_VIAL, TINKERERS_LOUPE
    )

    fun byId(id: String?): GearPiece? = id?.let { key -> all.firstOrNull { it.id == key } }
}
