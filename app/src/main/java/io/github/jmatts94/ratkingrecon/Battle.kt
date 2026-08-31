package io.github.jmatts94.ratkingrecon

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class BattleAction { ATTACK, DEFEND, SPECIAL, USE_ITEM }

/**
 * The four Shop-bought consumables usable mid-fight - see [Battle.applyItem].
 *
 * Names are unchanged since they reach the save as SharedPreferences keys
 * (ShopEffects.KEY_HP_TONIC, KEY_REINFORCED_PLATING) - renaming an entry here
 * would not itself break anything stored, but the display names shown for
 * HP_TONIC ("Regenerative Tonic") and REINFORCED_PLATING ("Protective
 * Bubble") have moved on from what these constants still spell out.
 */
enum class BattleItem { HP_TONIC, CORROSIVE_CHARGE, REINFORCED_PLATING, CLEANSE }

enum class BattleOutcome { ONGOING, PLAYER_WON, PLAYER_LOST }

/**
 * One still-ticking Corrosive Charge stack on the bot.
 *
 * A list rather than the single scalar Rusty Rake's own corrosion uses,
 * because a Charge is bought and applied deliberately and is meant to reward
 * stacking several - Rusty Rake's is a boss's free periodic hit and refires
 * on the same cadence, so it refreshes a single value instead. The two never
 * share a field, only the tick-and-decrement shape.
 *
 * [fromCorrosiveCharge] tells the two stacking sources that share this list
 * apart. A Foundry-born's Special (see [FactionSpecials]) arms a stack here
 * too, reusing the same tick-and-decrement machinery rather than a second
 * copy of it - but it must not also count as "a Corrosive Charge is active"
 * for the item's own attack-triggered stacking below, or a Foundry-born rat
 * would be piling on free Corrosive stacks it never bought a charge for.
 * Defaults true so every existing Corrosive Charge call site needs no change.
 */
data class DotEffect(val roundsRemaining: Int, val perRound: Int, val fromCorrosiveCharge: Boolean = true)

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
    val botUsedSpecial: Boolean = false,
    /** Whether this round was a boss's telegraph turn - see [Battle.botCharging]. */
    val botCharged: Boolean = false,
    /** Which named boss move [damageTaken] came from, if this round's reply used one. */
    val bossMoveNameRes: Int? = null,
    /** Corrosion from a lingering Rusty Rake, applied this round on top of [damageTaken]. */
    val dotDamage: Int = 0,
    /** Whether [damageTaken] carried the boss's own bonus against the rat's faction. */
    val bossMoveBonusApplied: Boolean = false,
    /** Whether [damageDealt] carried the bonus from fighting a boss weak to the rat's faction. */
    val ratWeaknessBonusApplied: Boolean = false,
    /** Which item this round spent, if [action] was [BattleAction.USE_ITEM]. */
    val itemUsed: BattleItem? = null,
    /** Corrosive Charge damage the bot took this round, from every stack still active. */
    val enemyDotDamage: Int = 0,
    /** HP a Smuggler's Special healed back this round - see [FactionSpecials.SMUGGLER_LIFESTEAL_FRACTION]. */
    val specialLifesteal: Int = 0,
    /** Whether a Foundry-born's Special armed a fresh DOT stack on the bot this round. */
    val specialAppliedDot: Boolean = false,
    /** Whether a Tinkerer's Special rolled its block chance and armed one - see [Battle.bubbleActive]. */
    val specialArmedBlock: Boolean = false,
    /** Whether a Scavenger's Special rolled its refund chance and shaved a round off its own cooldown. */
    val specialRefundedCooldown: Boolean = false,
    /** HP Rusted Fang's lifesteal healed back this round - see [Battle.lifestealFraction]. */
    val buffLifesteal: Int = 0
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
    val botMaxHp: Int,
    /**
     * Which boss this is, so its Special can carry a named move rather than
     * the plain "overloads" every ordinary Rustbot's Special does.
     *
     * Null for every ordinary encounter, which is what keeps them byte-for-
     * byte unchanged - everything below this point in the class only runs
     * when a boss id is actually present.
     */
    private val bossId: String? = null,
    /** The fighting rat's faction, checked against a boss move's target. */
    private val ratFaction: String? = null,
    /**
     * HP the rat starts this fight with. Defaults to a full heal - every
     * fight but an Arena run's second one onward, which carries the rat's HP
     * in from how the last fight ended instead. See [Encounter.toBattle].
     */
    startingRatHp: Int = ratMaxHp,
    /**
     * Rusted Fang's own fixed share of damage dealt, healed back on every hit
     * that lands - see [PermanentBuffs.RUSTED_FANG_LIFESTEAL_FRACTION]. Zero
     * until earned. Account-wide once it is, so unlike [incomingDamageReduction]
     * this applies in every fight, not only an Arena one.
     *
     * A separate mechanic from the Smuggler faction's own Special-only
     * lifesteal below (see [FactionSpecials.SMUGGLER_LIFESTEAL_FRACTION]) -
     * different field, different formula (a share of *damage dealt* here,
     * against a share of *max HP* there, on Special rounds only) - so the two
     * stack rather than collide for a Smuggler-faction rat that has also
     * earned Rusted Fang.
     */
    private val lifestealFraction: Double = 0.0,
    /**
     * Iron Boots' own fixed share of incoming damage shaved off - see
     * [PermanentBuffs.IRON_BOOTS_DAMAGE_REDUCTION]. Zero until earned, and
     * left at zero by every caller outside an Arena run - see
     * [Encounter.toBattle]. Applied last, after DEFEND's own halving and a
     * Protective Bubble's outright negation, so it only ever helps on top of
     * whatever those already did rather than replacing them.
     */
    private val incomingDamageReduction: Double = 0.0
) {

    companion object {
        /** Rounds between uses of Special. */
        const val SPECIAL_COOLDOWN = 3

        /** Special deals this multiple of Power, for every faction but Brawlers - see [FactionSpecials]. */
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

        // --- the four Shop-bought combat items ---------------------------------

        /** HP Tonic: restores this share of max HP instantly, capped at max. */
        const val HP_TONIC_FRACTION = 0.70

        /**
         * HP Tonic's ongoing half: this share of max HP regenerated at the
         * start of every round *after* the one it was drunk in, for the rest of
         * the fight. A [Battle] is scoped to one fight already, so "the rest of
         * the fight" needs nothing more than never clearing the flag that turns
         * this on.
         */
        const val TONIC_REGEN_FRACTION = 0.12

        /**
         * Corrosive Charge: each use adds its own stack at this share of the
         * rat's own Power per round - a bit above Rusty Rake's 15%, since this
         * one costs a turn and an item rather than being a boss's free periodic
         * hit.
         */
        const val CORROSIVE_DOT_FRACTION = 0.20
        const val CORROSIVE_DOT_ROUNDS = 3

        /**
         * Corrosive Charge's passive half: while any stack from the item is
         * still ticking, a plain Attack piles on a stack of its own at half the
         * item's own rate - free (no charge spent, no turn given up otherwise),
         * so it is priced lower than a deliberate use. Self-limiting without a
         * separate cap: a stack only lives [CORROSIVE_DOT_ROUNDS] rounds, so
         * attacking every round can never hold more than that many
         * attack-triggered stacks alive at once.
         */
        const val CORROSIVE_ATTACK_DOT_FRACTION = CORROSIVE_DOT_FRACTION / 2
    }

    var ratHp = startingRatHp
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

    /** How many times the bot's Special has fired, for [BossMoves.forBoss]'s rotation. */
    private var botSpecialUses = 0

    /** Rounds of Rusty Rake corrosion left, its own round included. 0 means none active. */
    private var dotRoundsRemaining = 0
    private var dotPerRound = 0

    /** Every Corrosive Charge stack still ticking against the bot; see [DotEffect]. */
    private var botDots: List<DotEffect> = emptyList()

    /** Whether a Protective Bubble is up, waiting to negate the next hit taken. */
    private var bubbleActive = false

    /** Whether a Regenerative Tonic has been drunk this fight. Once true, stays true. */
    private var tonicActive = false

    val log = mutableListOf<RoundResult>()

    /** Special is ready when enough rounds have passed since it was last used. */
    val specialAvailable: Boolean
        get() = outcome == BattleOutcome.ONGOING &&
            (round + 1) - lastSpecialRound >= SPECIAL_COOLDOWN

    /** Rounds remaining before Special can be used again; 0 when ready. */
    val specialCooldownRemaining: Int
        get() = max(0, SPECIAL_COOLDOWN - ((round + 1) - lastSpecialRound))

    fun attackDamage(): Int = ratPower

    /**
     * The rat's own Special, at whatever multiplier [FactionSpecials] gives
     * its faction, carrying the same [BossMoves.BONUS_MULTIPLIER] bonus a
     * boss's move does when the matchup runs the other way - a rat whose
     * faction is this boss's weakness (see [BossMoves.weakFactionFor]) hits
     * harder still on its Special, the one hit the boss's own bonus is
     * scoped to as well. Scoped to Special rather than every plain Attack for
     * the same reason: a bounded, periodic bonus rather than a flat rescale
     * of the whole fight.
     */
    fun specialDamage(): Int {
        val base = (ratPower * FactionSpecials.multiplierFor(ratFaction)).roundToInt()
        return if (ratSpecialBonusApplies()) (base * BossMoves.BONUS_MULTIPLIER).roundToInt() else base
    }

    /** Whether the rat's faction is the boss it is fighting's own weakness. */
    private fun ratSpecialBonusApplies(): Boolean {
        val weakness = bossId?.let { BossMoves.weakFactionFor(it) } ?: return false
        return weakness.equals(ratFaction, ignoreCase = true)
    }

    /** Whether the Rustbot's reply next round will be its Special. */
    val botSpecialReady: Boolean
        get() = (round + 1) - botLastSpecialRound >= BOT_SPECIAL_COOLDOWN

    /**
     * Whether the Rustbot's reply next round will be its telegraph turn rather
     * than a swing - see the `isChargeRound` branch in [advance]. Only ever
     * true for a boss: an ordinary Rustbot has no [bossId] and so no Special
     * worth warning about, and its cadence is untouched by any of this.
     *
     * Read the same way [botSpecialReady] already is - before the round plays,
     * so a screen can offer the telegraph turn's choice (attack freely, or
     * block the Special it warns of) ahead of the round that decides it.
     */
    val botCharging: Boolean
        get() = outcome == BattleOutcome.ONGOING && bossId != null &&
            (round + 1) - botLastSpecialRound == BOT_SPECIAL_COOLDOWN - 1

    fun botSpecialDamage(): Int = (botPower * SPECIAL_MULTIPLIER).roundToInt()

    /**
     * What the Rustbot's next unblocked hit will be.
     *
     * Public so the auto-resolver can decide whether blocking is worth it
     * against the hit actually coming, rather than against a plain swing - a
     * block chosen against the wrong number is a wasted round. A charging
     * round is worth nothing to block: it deals no damage at all.
     */
    fun botNextDamage(): Int = when {
        botCharging -> 0
        botSpecialReady -> botSpecialDamage()
        else -> botPower
    }

    /** Whether any Corrosive Charge stack is still ticking against the bot. */
    val botDotActive: Boolean get() = botDots.isNotEmpty()

    /** Whether a boss's own corrosion (Rusty Rake) is still ticking against the rat. */
    val ratDotActive: Boolean get() = dotRoundsRemaining > 0

    /**
     * Applies one item's effect. Called from [advance] the round [BattleItem.USE_ITEM]
     * is requested; whether the player actually holds a charge of [item] is the
     * caller's concern (Battle stays free of the Shop's economy, same as it
     * stays free of Android), not this simulator's.
     */
    private fun applyItem(item: BattleItem) {
        when (item) {
            BattleItem.HP_TONIC -> {
                ratHp = min(ratMaxHp, ratHp + (ratMaxHp * HP_TONIC_FRACTION).roundToInt())
                // Never cleared once set - see TONIC_REGEN_FRACTION.
                tonicActive = true
            }

            BattleItem.CORROSIVE_CHARGE -> botDots = botDots + DotEffect(
                roundsRemaining = CORROSIVE_DOT_ROUNDS,
                perRound = max(1, (ratPower * CORROSIVE_DOT_FRACTION).roundToInt())
            )

            // A second Bubble while one is already up is simply wasted - there
            // is nothing to refresh, since it only ever guards a single hit.
            BattleItem.REINFORCED_PLATING -> bubbleActive = true

            BattleItem.CLEANSE -> dotRoundsRemaining = 0
        }
    }

    /**
     * Plays one round: the rat acts, then the Rustbot strikes back if it lives.
     *
     * Requesting SPECIAL while it is on cooldown falls back to ATTACK rather
     * than throwing, so a stale button tap cannot crash the screen; requesting
     * USE_ITEM with no [item] named does the same, for the same reason.
     *
     * An item spends the round exactly like DEFEND does - zero damage dealt -
     * but without DEFEND's own halving of the reply coming back, a Protective
     * Bubble aside. Letting an item also blunt the same round's hit for free
     * would make it strictly better than defending outright, which is the one
     * choice this is meant to cost something.
     */
    fun advance(requested: BattleAction, item: BattleItem? = null): RoundResult {
        check(outcome == BattleOutcome.ONGOING) { "Battle is already over" }

        val action = when {
            requested == BattleAction.SPECIAL && !specialAvailable -> BattleAction.ATTACK
            requested == BattleAction.USE_ITEM && item == null -> BattleAction.ATTACK
            else -> requested
        }

        round += 1

        // Regenerative Tonic's ongoing half, at the start of every round after
        // the one it was drunk in. tonicActive only flips true down in
        // applyItem below, so the round the Tonic is actually used never
        // double-dips with the instant heal it already gave.
        if (tonicActive) {
            ratHp = min(ratMaxHp, ratHp + (ratMaxHp * TONIC_REGEN_FRACTION).roundToInt())
        }

        val ratBonusHit = action == BattleAction.SPECIAL && ratSpecialBonusApplies()
        val dealt = when (action) {
            BattleAction.ATTACK -> attackDamage()
            BattleAction.SPECIAL -> {
                lastSpecialRound = round
                specialDamage()
            }
            BattleAction.DEFEND -> 0
            BattleAction.USE_ITEM -> {
                applyItem(item!!)
                0
            }
        }
        botHp = max(0, botHp - dealt)

        // Rusted Fang: a fixed share of whatever was just dealt, healed back
        // on the spot. Checked against `dealt` rather than `action`, so a
        // DEFEND or USE_ITEM round - both deal zero - simply heals nothing,
        // with no separate guard needed.
        var buffLifesteal = 0
        if (dealt > 0 && lifestealFraction > 0.0) {
            val healedTo = min(ratMaxHp, ratHp + (dealt * lifestealFraction).roundToInt())
            buffLifesteal = healedTo - ratHp
            ratHp = healedTo
        }

        // Faction Specials' own secondary effects - see FactionSpecials. Only
        // ever rolled on the round the rat actually used its Special; a
        // requested SPECIAL that fell back to ATTACK above never reaches
        // here. Mutually exclusive by construction - a rat has exactly one
        // faction - so a plain `when` with no else needs nothing more: a
        // faction that does not match, or a chance that does not land, just
        // falls through to no effect at all.
        var specialLifesteal = 0
        var specialAppliedDot = false
        var specialArmedBlock = false
        var specialRefundedCooldown = false
        if (action == BattleAction.SPECIAL) {
            when {
                FactionSpecials.isSmuggler(ratFaction) -> {
                    val healedTo = min(ratMaxHp, ratHp + (ratMaxHp * FactionSpecials.SMUGGLER_LIFESTEAL_FRACTION).roundToInt())
                    specialLifesteal = healedTo - ratHp
                    ratHp = healedTo
                }

                // A dead bot needs no DOT, the same guard Corrosive Charge's
                // own attack-trigger already uses below.
                FactionSpecials.isFoundryBorn(ratFaction) && botHp > 0 -> {
                    botDots = botDots + DotEffect(
                        roundsRemaining = FactionSpecials.FOUNDRY_BORN_DOT_ROUNDS,
                        perRound = max(1, (ratPower * FactionSpecials.FOUNDRY_BORN_DOT_FRACTION).roundToInt()),
                        fromCorrosiveCharge = false
                    )
                    specialAppliedDot = true
                }

                FactionSpecials.isTinkerer(ratFaction) &&
                    Math.random() < FactionSpecials.TINKERER_BLOCK_CHANCE -> {
                    bubbleActive = true
                    specialArmedBlock = true
                }

                FactionSpecials.isScavenger(ratFaction) &&
                    Math.random() < FactionSpecials.SCAVENGER_REFUND_CHANCE -> {
                    lastSpecialRound -= 1
                    specialRefundedCooldown = true
                }
            }
        }

        // Corrosive Charge's passive half: attacking while any of *its own*
        // stacks is still ticking piles on a lighter one of its own - see
        // CORROSIVE_ATTACK_DOT_FRACTION. Scoped to fromCorrosiveCharge stacks
        // specifically, or a Foundry-born's own DOT (see FactionSpecials,
        // added to this same list) would silently also trigger this - free
        // Corrosive stacking nobody bought a charge for. Checked against the
        // stacks as they stood before this round's own tick below, so a
        // stack about to expire this same round still counts as "active" for
        // the purpose of this Attack triggering another.
        if (action == BattleAction.ATTACK && botDots.any { it.fromCorrosiveCharge } && botHp > 0) {
            botDots = botDots + DotEffect(
                roundsRemaining = CORROSIVE_DOT_ROUNDS,
                perRound = max(1, (ratPower * CORROSIVE_ATTACK_DOT_FRACTION).roundToInt())
            )
        }

        // Every Corrosive Charge stack ticks against the bot each round it is
        // still standing, independent of whatever action was taken this round -
        // the same unconditional cadence Rusty Rake's own corrosion ticks the
        // rat's HP on below, just aimed the other way and able to hold more
        // than one stack at once. A bot this finishes off does not swing back,
        // the same as one an attack finishes off.
        var enemyDotTick = 0
        if (botHp > 0 && botDots.isNotEmpty()) {
            enemyDotTick = botDots.sumOf { it.perRound }
            botHp = max(0, botHp - enemyDotTick)
            botDots = botDots.map { it.copy(roundsRemaining = it.roundsRemaining - 1) }
                .filter { it.roundsRemaining > 0 }
        }

        // The Rustbot only swings if it survived the round.
        var taken = 0
        var botSpecial = false
        var botCharged = false
        var move: BossMove? = null
        var dotTick = 0
        var botBonusHit = false
        if (botHp > 0) {
            // A boss telegraphs its Special one round early: the round right
            // before the cooldown threshold is reached, it charges instead of
            // swinging - dealing no damage, but leaving the Special due to
            // still fire on schedule the round after, exactly as if this round
            // had never been inserted. Ordinary Rustbots have no bossId and so
            // never charge; their Special still lands with no warning, exactly
            // as it always has - see botCharging for the same check, read one
            // round ahead of play.
            val isChargeRound = bossId != null &&
                round - botLastSpecialRound == BOT_SPECIAL_COOLDOWN - 1
            botSpecial = !isChargeRound && round - botLastSpecialRound >= BOT_SPECIAL_COOLDOWN
            botCharged = isChargeRound

            if (botSpecial) {
                botLastSpecialRound = round
                botSpecialUses += 1
                move = bossId?.let { BossMoves.forBoss(it, botSpecialUses) }
            }

            if (!isChargeRound) {
                val base = if (botSpecial) botSpecialDamage() else botPower
                botBonusHit = move != null && BossMoves.bonusApplies(move, ratFaction)
                val bonused = if (botBonusHit) (base * BossMoves.BONUS_MULTIPLIER).roundToInt() else base

                // A Protective Bubble negates this hit outright, whatever it
                // was going to be - DEFEND's own halving never gets a chance
                // to run against it, the same way an item's own round already
                // skips DEFEND's block. Raised during a charge round, the flag
                // is untouched by that round (see the taken-stays-0 branch
                // below) and survives intact into the Special round it was
                // meant for; raised any other round, it guards the very next
                // hit instead.
                taken = if (bubbleActive) {
                    bubbleActive = false
                    0
                } else if (action == BattleAction.DEFEND) {
                    bonused / 2
                } else {
                    bonused
                }
                // Iron Boots, Arena fights only - shaves a fixed share off
                // whatever DEFEND/the Bubble already left, rather than
                // competing with either: zero stays zero, and a halved hit is
                // halved again on top.
                if (incomingDamageReduction > 0.0) {
                    taken = (taken * (1.0 - incomingDamageReduction)).roundToInt()
                }
                ratHp = max(0, ratHp - taken)

                if (move?.appliesDot == true) {
                    dotRoundsRemaining = BossMoves.DOT_ROUNDS
                    dotPerRound = max(1, (botPower * BossMoves.DOT_FRACTION).roundToInt())
                }
            }
            // A charge round deals no damage at all - taken stays 0, and there
            // is nothing this round to block or brace against, so a Bubble
            // raised here is saved whole for the Special it is warning of.

            // Corrosion is not a swing DEFEND can brace against - it ticks
            // whether or not the round's hit was blocked, and whether or not
            // the boss even swung this round. Cleanse pre-empts this: it
            // zeroes dotRoundsRemaining above, in applyItem, before this check
            // ever runs.
            if (dotRoundsRemaining > 0 && ratHp > 0) {
                dotTick = dotPerRound
                ratHp = max(0, ratHp - dotTick)
                dotRoundsRemaining -= 1
            }
        }

        outcome = when {
            botHp <= 0 -> BattleOutcome.PLAYER_WON
            ratHp <= 0 -> BattleOutcome.PLAYER_LOST
            round >= MAX_ROUNDS -> decideOnHpShare()
            else -> BattleOutcome.ONGOING
        }

        return RoundResult(
            round = round,
            action = action,
            damageDealt = dealt,
            damageTaken = taken,
            ratHp = ratHp,
            botHp = botHp,
            outcome = outcome,
            botUsedSpecial = botSpecial,
            botCharged = botCharged,
            bossMoveNameRes = move?.nameRes,
            dotDamage = dotTick,
            bossMoveBonusApplied = botBonusHit,
            ratWeaknessBonusApplied = ratBonusHit,
            itemUsed = if (action == BattleAction.USE_ITEM) item else null,
            enemyDotDamage = enemyDotTick,
            specialLifesteal = specialLifesteal,
            specialAppliedDot = specialAppliedDot,
            specialArmedBlock = specialArmedBlock,
            specialRefundedCooldown = specialRefundedCooldown,
            buffLifesteal = buffLifesteal
        ).also { log += it }
    }

    /**
     * Brings the rat back into a fight it had just lost, Scrap having paid for
     * it - see [EncounterResolver.revive].
     *
     * HP is restored to full and the fight reopens for play; everything else
     * carries over untouched. The round count, the bot's own Special cadence
     * and any Rusty Rake corrosion still in effect are exactly where they were,
     * because Scrap buys the rat back into the fight in progress, not a clean
     * restart against a bot that forgets what it already did.
     *
     * [EncounterResolver.revive] only ever clears the DB-side knockout timer -
     * there is no stored HP to restore, since a rat's HP has never lived
     * anywhere but here. This is the other half of the same action.
     */
    fun revive() {
        check(outcome == BattleOutcome.PLAYER_LOST) { "Only a lost battle can be revived" }
        ratHp = ratMaxHp
        outcome = BattleOutcome.ONGOING
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
