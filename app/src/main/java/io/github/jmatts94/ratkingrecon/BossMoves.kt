package io.github.jmatts94.ratkingrecon

import androidx.annotation.StringRes

/**
 * A boss's named Special, layered on top of the generic Rustbot Special every
 * bot already has (see [Battle.BOT_SPECIAL_COOLDOWN]).
 *
 * [targetFaction] is which [Roster] faction the move hits harder; null means
 * every faction takes the bonus, which is how Rustbringer's copies below read
 * "vs ALL factions" without a second code path.
 */
data class BossMove(
    val id: String,
    @param:StringRes val nameRes: Int,
    val targetFaction: String?,
    val appliesDot: Boolean = false
)

/**
 * The four named moves, and which boss owns which.
 *
 * Four moves rather than five: Rustbringer does not have a move of its own,
 * it borrows all four of the others - "any of the above four gimmicks" - with
 * every one of its own copies exempting no faction, since nothing is meant to
 * be safe against the last boss.
 */
object BossMoves {

    /** Extra multiplier a move's damage carries when it hits its target faction. */
    const val BONUS_MULTIPLIER = 1.10

    /** Rounds Rusty Rake's corrosion lingers for, its own round included. */
    const val DOT_ROUNDS = 3

    /** Corrosion per round, as a share of the boss's own Power. */
    const val DOT_FRACTION = 0.15

    val GEAR_SMASH = BossMove("gear_smash", R.string.boss_move_gear_smash, Roster.SMUGGLERS)
    val RUSTY_RAKE = BossMove(
        "rusty_rake", R.string.boss_move_rusty_rake, Roster.TINKERERS, appliesDot = true
    )
    val STEAM_JET = BossMove("steam_jet", R.string.boss_move_steam_jet, Roster.SCAVENGERS)
    val WIRE_MESH = BossMove("wire_mesh", R.string.boss_move_wire_mesh, Roster.BRAWLERS)

    /** Every named move, in the fixed order Rustbringer cycles through them. */
    private val ROTATION = listOf(GEAR_SMASH, RUSTY_RAKE, STEAM_JET, WIRE_MESH)

    /** Rustbringer's own copies: the same names and effects, but no faction is spared. */
    private val RUSTBRINGER_ROTATION = ROTATION.map { it.copy(targetFaction = null) }

    private val SINGLE: Map<String, BossMove> = mapOf(
        "junk_golem" to GEAR_SMASH,
        "old_ironclaw" to RUSTY_RAKE,
        "boiler_baron" to STEAM_JET,
        "circuit_reaper" to WIRE_MESH
    )

    /**
     * The move a boss's Special uses on the [useNumber]th time it triggers
     * this fight (1-based).
     *
     * Every boss but Rustbringer has exactly one move and always uses it.
     * Rustbringer rotates through all four in the fixed order above rather
     * than rolling one - [Battle] is deliberately free of randomness, so a
     * replayed or reopened fight must land on the same move every time.
     *
     * Null for a boss id this build does not recognise, the same fallback
     * [RustbotFlavour.openingFor] uses - a strange thing to meet, not a
     * reason to crash the fight.
     */
    fun forBoss(bossId: String, useNumber: Int): BossMove? =
        if (bossId == "rustbringer") {
            RUSTBRINGER_ROTATION[(useNumber - 1).mod(RUSTBRINGER_ROTATION.size)]
        } else {
            SINGLE[bossId]
        }

    /** Whether [move]'s bonus lands on a rat of [ratFaction]. */
    fun bonusApplies(move: BossMove, ratFaction: String?): Boolean =
        move.targetFaction == null || move.targetFaction.equals(ratFaction, ignoreCase = true)

    // ---- the other direction: what a boss is weak to --------------------------

    /**
     * The rock-paper-scissors order a boss's own weakness is drawn from -
     * Smugglers beats Tinkerers beats Scavengers beats Brawlers beats
     * Smugglers. [Roster.FOUNDRY_BORN] sits outside it entirely: a
     * Foundry-born rat is never anyone's target and never anyone's weakness,
     * rather than falling through to a default either way.
     */
    private val FACTION_CYCLE = listOf(Roster.SMUGGLERS, Roster.TINKERERS, Roster.SCAVENGERS, Roster.BRAWLERS)

    /**
     * Which faction hits each boss harder, derived from the same [SINGLE] move
     * a boss is itself strong against rather than a second hand-written table -
     * a boss is weak to whatever beats the faction it beats, one step back
     * around [FACTION_CYCLE]. Rustbringer has no entry and so no weakness,
     * matching "strong vs all factions, weak vs none": the last boss does not
     * sit anywhere on the wheel other bosses turn on.
     */
    private val WEAK_TO: Map<String, String> = SINGLE.mapValues { (_, move) ->
        val strong = requireNotNull(move.targetFaction) { "every single-move boss targets one faction" }
        val i = FACTION_CYCLE.indexOf(strong)
        FACTION_CYCLE[(i - 1).mod(FACTION_CYCLE.size)]
    }

    /** The faction a boss takes bonus damage from, or null for none (Rustbringer). */
    fun weakFactionFor(bossId: String): String? = WEAK_TO[bossId]

    // ---- the reverse lookups, for a per-faction summary --------------------

    /**
     * The boss whose own Special deals bonus damage to [faction], or null -
     * for [Roster.FOUNDRY_BORN], which no named move ever targets, and for
     * an unrecognised faction. Read straight off [SINGLE], the same map
     * [forBoss] itself resolves against, so a faction summary built from
     * this can never drift from what a fight actually does.
     */
    fun bossThatTargets(faction: String): String? =
        SINGLE.entries.firstOrNull { it.value.targetFaction.equals(faction, ignoreCase = true) }?.key

    /**
     * The boss [faction] itself deals bonus Special damage against, or null -
     * for Foundry-born, which sits outside [FACTION_CYCLE] entirely, and for
     * an unrecognised faction. Read straight off [WEAK_TO], the same map
     * [weakFactionFor] itself resolves against.
     */
    fun bossWeakTo(faction: String): String? =
        WEAK_TO.entries.firstOrNull { it.value.equals(faction, ignoreCase = true) }?.key
}
