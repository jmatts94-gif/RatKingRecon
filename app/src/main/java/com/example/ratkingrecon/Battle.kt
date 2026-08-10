package com.example.ratkingrecon

import kotlin.math.max
import kotlin.math.roundToInt

enum class BattleAction { ATTACK, DEFEND, SPECIAL }

enum class BattleOutcome { ONGOING, PLAYER_WON, PLAYER_LOST }

/** What one round did, enough to drive both a UI log and a result summary. */
data class RoundResult(
    val round: Int,
    val action: BattleAction,
    val damageDealt: Int,
    val damageTaken: Int,
    val ratHp: Int,
    val botHp: Int,
    val outcome: BattleOutcome
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
        if (botHp > 0) {
            taken = if (action == BattleAction.DEFEND) botPower / 2 else botPower
            ratHp = max(0, ratHp - taken)
        }

        outcome = when {
            botHp <= 0 -> BattleOutcome.PLAYER_WON
            ratHp <= 0 -> BattleOutcome.PLAYER_LOST
            round >= MAX_ROUNDS -> decideOnHpShare()
            else -> BattleOutcome.ONGOING
        }

        return RoundResult(round, action, dealt, taken, ratHp, botHp, outcome)
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
     * Roughly what a competent player does: lead with Special whenever it is
     * ready, block a hit that would otherwise be lethal, and swing the rest of
     * the time.
     *
     * Defending is only chosen when it actually changes the outcome of the
     * round - if the halved hit would still be fatal there is nothing to gain,
     * so the rat goes down attacking instead of stalling.
     */
    private fun choose(battle: Battle): BattleAction {
        if (battle.specialAvailable) return BattleAction.SPECIAL
        if (blockingSavesUs(battle)) return BattleAction.DEFEND
        return BattleAction.ATTACK
    }

    /**
     * True when the next hit is lethal but a halved one is not.
     *
     * If blocking would not save the rat either, there is nothing to gain by
     * stalling - it goes down attacking, which at least leaves the Rustbot
     * damaged and keeps the fight from dragging to the round cap.
     */
    private fun blockingSavesUs(battle: Battle): Boolean {
        val incoming = battle.botPower
        return incoming >= battle.ratHp && incoming / 2 < battle.ratHp
    }
}
