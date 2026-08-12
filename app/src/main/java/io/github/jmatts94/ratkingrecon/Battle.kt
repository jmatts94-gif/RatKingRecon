package io.github.jmatts94.ratkingrecon

import kotlin.math.max
import kotlin.math.roundToInt

enum class BattleAction { ATTACK, DEFEND, SPECIAL }

enum class BattleOutcome { ONGOING, PLAYER_WON, PLAYER_LOST }

/**
 * What the Shop has armed for one fight.
 *
 * Takes plain numbers rather than a rat so this file stays free of Android and
 * Room, the same reason [Battle] does.
 *
 * Both buffs are proportions, never flat amounts. Rustbots are scaled off the
 * rat that meets them, so a flat bonus shrank in value exactly as the roster
 * improved; a multiplier holds its worth at every rat size.
 *
 * There is no defence stat in [Battle] to multiply - DEFEND simply halves the
 * incoming hit - so a defensive buff is expressed as extra maximum HP, which is
 * the only durability lever the simulator actually has.
 */
data class Loadout(
    val powerMultiplier: Double = 1.0,
    val hpMultiplier: Double = 1.0
) {
    companion object {
        val NONE = Loadout()
    }

    fun powerFor(basePower: Int): Int = max(1, (basePower * powerMultiplier).roundToInt())

    fun maxHpFor(baseMaxHp: Int): Int = max(1, (baseMaxHp * hpMultiplier).roundToInt())
}

/** What one round did, enough to drive both a UI log and a result summary. */
data class RoundResult(
    val round: Int,
    val action: BattleAction,
    val damageDealt: Int,
    val damageTaken: Int,
    val ratHp: Int,
    val botHp: Int,
    val outcome: BattleOutcome,
    /** Whether the Rustbot's reply this round was its Special. */
    val botUsedSpecial: Boolean = false
)

/**
 * The combat simulator.
 *
 * Deliberately free of Android imports and of randomness: the manual battle
 * screen and Auto-Resolve both drive this same class, so the two can never
 * disagree about the maths. Everything variable about an encounter - which rat,
 * which Rustbot, what it pays - is decided when the encounter triggers and
 * handed in here already fixed.
 *
 * The player always acts first in a round.
 */
class Battle(
    val ratName: String,
    private val ratPower: Int,
    val ratMaxHp: Int,
    val botName: String,
    val botPower: Int,
    val botMaxHp: Int
) {

    companion object {
        /** Rounds between uses of Special. */
        const val SPECIAL_COOLDOWN = 3

        /** Special deals this multiple of Power. */
        const val SPECIAL_MULTIPLIER = 1.5

        /**
         * Rounds between the Rustbot's Special.
         *
         * The same cadence the rat has, deliberately out of phase with it: the
         * rat's lands on rounds 1, 4, 7 and the Rustbot's on 3, 6, 9, so the two
         * big hits never fall in the same round and a fight has a rhythm rather
         * than a pair of simultaneous spikes.
         *
         * The Rustbot had no Special at all before. Together with the rat
         * striking first and being the only side able to block, that was most of
         * why an ordinary encounter could not be lost.
         */
        const val BOT_SPECIAL_COOLDOWN = 3

        /**
         * Safety net. A pathological pairing - a Rustbot whose halved damage
         * rounds to zero against a rat that keeps defending - could otherwise
         * loop forever. Reaching this decides the fight on remaining HP share.
         */
        const val MAX_ROUNDS = 200
    }

    var ratHp = ratMaxHp
        private set

    var botHp = botMaxHp
        private set

    var round = 0
        private set

    var outcome = BattleOutcome.ONGOING
        private set

    private var lastSpecialRound = -SPECIAL_COOLDOWN

    /**
     * Zero rather than negative, which is what puts the Rustbot's Special on
     * round 3 while the rat's lands on round 1.
     */
    private var botLastSpecialRound = 0

    val log = mutableListOf<RoundResult>()

    /** Special is ready when enough rounds have passed since it was last used. */
    val specialAvailable: Boolean
        get() = outcome == BattleOutcome.ONGOING &&
            (round + 1) - lastSpecialRound >= SPECIAL_COOLDOWN

    /** Rounds remaining before Special can be used again; 0 when ready. */
    val specialCooldownRemaining: Int
        get() = max(0, SPECIAL_COOLDOWN - ((round + 1) - lastSpecialRound))

    fun attackDamage(): Int = ratPower

    fun specialDamage(): Int = (ratPower * SPECIAL_MULTIPLIER).roundToInt()

    /** Whether the Rustbot's reply next round will be its Special. */
    val botSpecialReady: Boolean
        get() = (round + 1) - botLastSpecialRound >= BOT_SPECIAL_COOLDOWN

    fun botSpecialDamage(): Int = (botPower * SPECIAL_MULTIPLIER).roundToInt()

    /**
     * What the Rustbot's next unblocked hit will be.
     *
     * Public so the auto-resolver can decide whether blocking is worth it
     * against the hit actually coming, rather than against a plain swing - a
     * block chosen against the wrong number is a wasted round.
     */
    fun botNextDamage(): Int = if (botSpecialReady) botSpecialDamage() else botPower

    /**
     * Plays one round: the rat acts, then the Rustbot strikes back if it lives.
     *
     * Requesting SPECIAL while it is on cooldown falls back to ATTACK rather
     * than throwing, so a stale button tap cannot crash the screen.
     */
    fun advance(requested: BattleAction): RoundResult {
        check(outcome == BattleOutcome.ONGOING) { "Battle is already over" }

        val action = if (requested == BattleAction.SPECIAL && !specialAvailable) {
            BattleAction.ATTACK
        } else {
            requested
        }

        round += 1

        val dealt = when (action) {
            BattleAction.ATTACK -> attackDamage()
            BattleAction.SPECIAL -> {
                lastSpecialRound = round
                specialDamage()
            }
            BattleAction.DEFEND -> 0
        }
        botHp = max(0, botHp - dealt)

        // The Rustbot only swings if it survived the round.
        var taken = 0
        var botSpecial = false
        if (botHp > 0) {
            botSpecial = round - botLastSpecialRound >= BOT_SPECIAL_COOLDOWN
            if (botSpecial) botLastSpecialRound = round

            val incoming = if (botSpecial) botSpecialDamage() else botPower
            taken = if (action == BattleAction.DEFEND) incoming / 2 else incoming
            ratHp = max(0, ratHp - taken)
        }

        outcome = when {
            botHp <= 0 -> BattleOutcome.PLAYER_WON
            ratHp <= 0 -> BattleOutcome.PLAYER_LOST
            round >= MAX_ROUNDS -> decideOnHpShare()
            else -> BattleOutcome.ONGOING
        }

        return RoundResult(round, action, dealt, taken, ratHp, botHp, outcome, botSpecial)
            .also { log += it }
    }

    /** Whoever has kept the larger share of their health takes a stalled fight. */
    private fun decideOnHpShare(): BattleOutcome {
        val ratShare = ratHp.toFloat() / ratMaxHp
        val botShare = botHp.toFloat() / botMaxHp
        return if (ratShare >= botShare) BattleOutcome.PLAYER_WON else BattleOutcome.PLAYER_LOST
    }
}

/**
 * Plays a whole battle without a player.
 *
 * Used by Auto-Resolve, and it drives the same [Battle] the manual screen does,
 * so a pocket fight and a hand-played one resolve by identical rules.
 */
object AutoResolver {

    fun resolve(battle: Battle): Battle {
        while (battle.outcome == BattleOutcome.ONGOING) {
            battle.advance(choose(battle))
        }
        return battle
    }

    /**
     * Roughly what a competent player does: kill if the swing finishes it,
     * block a hit that would otherwise be lethal, lead with Special when it is
     * ready, and swing the rest of the time.
     *
     * Finishing comes before everything, because a dead Rustbot does not reply
     * - spending the round blocking a hit that was never going to land throws
     * the fight away. That ordering matters far more now the Rustbot has a
     * Special of its own to survive.
     */
    private fun choose(battle: Battle): BattleAction {
        if (battle.specialAvailable && battle.specialDamage() >= battle.botHp) {
            return BattleAction.SPECIAL
        }
        if (battle.attackDamage() >= battle.botHp) return BattleAction.ATTACK
        if (blockingSavesUs(battle)) return BattleAction.DEFEND
        if (battle.specialAvailable) return BattleAction.SPECIAL
        return BattleAction.ATTACK
    }

    /**
     * True when the hit actually coming is lethal but a halved one is not.
     *
     * Measured against [Battle.botNextDamage] rather than the Rustbot's plain
     * Power, because the hit on a Special round is half again as big - blocking
     * against the wrong number either wastes a round or fails to save the rat
     * from the one hit worth blocking.
     *
     * If blocking would not save the rat either, there is nothing to gain by
     * stalling - it goes down attacking, which at least leaves the Rustbot
     * damaged and keeps the fight from dragging to the round cap.
     */
    private fun blockingSavesUs(battle: Battle): Boolean {
        val incoming = battle.botNextDamage()
        return incoming >= battle.ratHp && incoming / 2 < battle.ratHp
    }
}
