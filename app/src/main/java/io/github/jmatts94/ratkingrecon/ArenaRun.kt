package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One Arena depth milestone - see [ArenaRun.MILESTONES].
 *
 * The same shape [Milestone] and [BossSpec] already carry an id/name/icon in,
 * so [AchievementsActivity]'s existing row renderer can draw these exactly
 * the way it draws a boss badge, with no new layout logic of its own.
 * [tierColorRes], [glowAlpha] and [glowScale] additionally drive the
 * medallion treatment both there and on the home screen's Arena tile - see
 * [ArenaBadgeMedallion] - bronze/silver/gold, with the glow strengthening
 * as the badge gets harder to earn.
 */
data class ArenaMilestone(
    val fight: Int,
    @param:StringRes val nameRes: Int,
    @param:DrawableRes val iconRes: Int,
    @param:ColorRes val tierColorRes: Int,
    /** Peak alpha (0..255) of the glow behind an earned medallion; 0 for none. */
    val glowAlpha: Int,
    /**
     * How much bigger than the medallion itself the glow renders, on top of
     * [glowAlpha] - the fight-15 badge is meant to read as clearly the most
     * premium of the three, not merely the brightest, so its glow is
     * physically larger as well as stronger.
     */
    val glowScale: Float = 1f
)

/**
 * What a cleared fight handed back, for whichever popup [BattleActivity] shows
 * next - the ordinary "onward" breather, or the fight-15 "Arena Cleared"
 * celebration when [cleared] is true.
 */
data class ArenaFightOutcome(
    val fightCleared: Int,
    val scrapThisFight: Int,
    val scrapRunTotal: Int,
    val relicEarned: Relic?,
    val milestoneFightNewlyEarned: Int?,
    val cleared: Boolean,
    /** Set only when [cleared] and a frame was still left to give. */
    val cosmeticFrame: CardFrame? = null,
    /** Set only when [cleared] and every frame was already owned. */
    val cosmeticScrapFallback: Int = 0
)

/** What a loss hands back - everything banked before the run ended. */
data class ArenaLossSummary(
    val fightReached: Int,
    val scrapRunTotal: Int,
    val relicsRunTotal: Int
)

/**
 * Tracks progress through a Battle Arena run once [Arena.raiseFirstFight] has
 * raised its first fight - which fight the player is on, the rat's HP carried
 * in with no heal between fights, and the Scrap/relics banked so far this run.
 *
 * One run at a time, the same way [Encounter] holds one pending fight at a
 * time: [begin] is only ever called once [Arena.raiseFirstFight] has already
 * confirmed no fight is outstanding.
 */
object ArenaRun {

    const val TOTAL_FIGHTS = 15

    val MILESTONES: List<ArenaMilestone> = listOf(
        ArenaMilestone(5, R.string.arena_milestone_5_name, R.drawable.ic_settings, R.color.copper, glowAlpha = 0),
        ArenaMilestone(10, R.string.arena_milestone_10_name, R.drawable.ic_gear_double, R.color.pewter, glowAlpha = 90),
        ArenaMilestone(
            15, R.string.arena_milestone_15_name, R.drawable.ic_gear_triple, R.color.brass_bright,
            glowAlpha = 225, glowScale = 1.4f
        )
    )
    val MILESTONE_FIGHTS: Set<Int> = MILESTONES.map { it.fight }.toSet()

    private const val KEY_ACTIVE = "ARENA_RUN_ACTIVE"
    private const val KEY_RAT_ID = "ARENA_RUN_RAT_ID"
    private const val KEY_FIGHT = "ARENA_RUN_FIGHT"
    private const val KEY_CARRIED_HP = "ARENA_RUN_CARRIED_HP"
    private const val KEY_SCRAP_EARNED = "ARENA_RUN_SCRAP_EARNED"
    private const val KEY_RELICS_EARNED = "ARENA_RUN_RELICS_EARNED"

    private const val MILESTONE_LATCH_PREFIX = "ARENA_MILESTONE_FIGHT_"

    // ---- reward curve ----------------------------------------------------

    /**
     * How much a fight's base Scrap reward is multiplied by, ramping from
     * 1.0x at fight 1 to 3.0x at fight 15 - the same shape [Bosses.rewardFor]
     * scales a boss's reward by, just spread across the whole run instead of
     * a single tier.
     */
    fun scrapMultiplierFor(fight: Int): Double =
        1.0 + (fight - 1).toDouble() / (TOTAL_FIGHTS - 1) * 2.0

    /**
     * Chance a fight's win rolls a relic, climbing from 10% at fight 1 to 50%
     * at fight 15 - the same spread [LedgerTaskTier.relicChance] uses across
     * its short/medium/long tiers, applied here across depth instead.
     */
    fun relicChanceFor(fight: Int): Double =
        0.10 + (fight - 1).toDouble() / (TOTAL_FIGHTS - 1) * 0.40

    // ---- difficulty curve ------------------------------------------------

    /** Where the curve begins - already past what a typical early ordinary encounter risks. */
    private const val START_RATIO = 0.90

    /**
     * Where the curve ends, at fight 15.
     *
     * [RustbotFactory.MAX_RATIO]'s own documented sweep only runs to 1.20 (27%
     * win rate), and playtesting past that ceiling confirmed why it was too
     * low for the Arena specifically: fight 15 is fought on the rat's
     * *effective* stats (rarity bonus, any Shop Loadout) against a bot still
     * sized off the ratio alone, and every fight in between hands back 30% of
     * max HP (see [ARENA_RELIEF_FRACTION]) - both deliberately, but together
     * they left a well-prepared rat with real runway left at 1.20. Past that
     * documented data now, but the win rate fell smoothly rather than in
     * cliffs all the way out to 1.20, so 1.35 is a continuation of the same
     * curve rather than a guess in the dark - meant to make the last fight a
     * genuine coin flip even for a rat carrying every edge the mode gives it,
     * not just the average pairing the old ceiling was tuned against.
     */
    private const val MAX_RATIO = 1.35

    /**
     * How close to - or past - the rat's own stats [fight] gets.
     *
     * Deliberately independent of player level, unlike [RustbotFactory.rampFor]
     * - the Arena has no level gate the way a boss does, so its difficulty
     * cannot lean on one either, or grinding levels would trivialise the run
     * the same way it was doing before this curve existed at all. Every
     * player meets the same fifteen fights.
     */
    fun ratioFor(fight: Int): Double =
        START_RATIO + (MAX_RATIO - START_RATIO) * (fight - 1).toDouble() / (TOTAL_FIGHTS - 1)

    /**
     * Share of the rat's own max HP topped up after every fight cleared -
     * not a full heal, which would flatten the "no safety net" carried-HP
     * design this run is built around, just enough that a run's losses come
     * from being out-fought rather than from HP quietly compounding downward
     * across several individually-winnable fights in a row with nothing
     * between them to break the slide. Never applies after fight 15 - the
     * run is already over by then, settled through the `cleared` branch
     * [recordWin] takes instead of this one.
     *
     * Every fight now, not every second or third: even with the curve (see
     * [ratioFor]) and the Arena's own high-stat bonus (see
     * [arenaPowerBonusFor]) both accounted for, a fight that is individually
     * winnable can still leave a rat too wounded to survive the next one
     * un-recovered - and with [RELIEF_EVERY_N_FIGHTS] at 2 or 3, a bad run of
     * back-to-back close fights had nothing between them to stop that from
     * compounding all the way to zero. This is still a *partial* top-up, so
     * a fight that goes badly enough still costs real ground - it just no
     * longer stacks silently on top of the last one.
     */
    private const val RELIEF_EVERY_N_FIGHTS = 1
    const val ARENA_RELIEF_FRACTION = 0.30

    /**
     * The Rustbot for [fight], scaled by [ratioFor] alone.
     *
     * Uncapped on both Power and HP - the same treatment [Bosses.rustbotFor]
     * gives a boss, and for the same reason: past the curve's midpoint this is
     * meant to be allowed to out-hit the rat outright, not merely catch up to
     * it. An ordinary Rustbot's Power stops at parity; this one does not.
     */
    fun rustbotFor(fight: Int, rat: RatEntity): Rustbot {
        val ratio = ratioFor(fight)
        return Rustbot(
            name = RustbotFactory.randomVariantName(),
            power = max(1, (rat.power * ratio).roundToInt()),
            maxHp = max(1, (rat.maxHp * ratio).roundToInt())
        )
    }

    private const val STAT_TIER_1 = 15
    private const val BONUS_PER_POINT = 1

    /**
     * A combat edge for a champion whose own stats have grown past what
     * [ratioFor] assumes, so real investment in a rat keeps pulling ahead of
     * the curve rather than being scaled back down to parity with it as the
     * run goes on - or, past a first flat +4/+8 tried here first, topping
     * out no matter how far past 20 a rat was pushed. [rustbotFor] above is
     * uncapped on both stats for the same reason (see its own doc comment);
     * this answers in kind, uncapped too, rather than capping only the
     * player's side of a curve that does not cap the other one.
     *
     * Safe as a flat amount specifically because of *when* it applies -
     * [rustbotFor] has already sized the bot off the rat's stats by the time
     * this is added to the player's own side in [Rustbot.toBattle], so
     * unlike a Shop [Loadout] (see its own doc comment on why those are
     * multipliers instead), this bonus never feeds back into the enemy's
     * math and cannot be scaled away by it.
     *
     * Power and Toughness are checked independently - [arenaPowerBonusFor]
     * off Power, [arenaMaxHpBonusFor] off Toughness - so a lopsided rat only
     * earns the half its stats actually cleared.
     */
    fun arenaPowerBonusFor(rat: RatEntity): Int = tierBonus(rat.effectivePower)

    /** See [arenaPowerBonusFor]; off [RatEntity.effectiveToughness] instead. */
    fun arenaMaxHpBonusFor(rat: RatEntity): Int = tierBonus(rat.effectiveToughness)

    private fun tierBonus(stat: Int): Int = max(0, (stat - STAT_TIER_1) * BONUS_PER_POINT)

    /**
     * Which of the five boss kits a milestone fight (5/10/15) borrows its
     * Special from - the named move, the faction weakness, the DOT - by
     * carrying its id on the [Encounter] the same way a real boss fight does.
     * Everything about the fight's own stats stays exactly [ratioFor]'s doing;
     * only the reply kit is borrowed, never [BossSpec.powerMult]/[hpMult] -
     * those are tuned against a player level this run deliberately ignores
     * (see [ratioFor]'s own doc comment), so mixing them in here would reopen
     * the very thing this curve exists to avoid.
     *
     * Three milestones, five kits: not every boss is used. Junk Golem, Boiler
     * Baron and Rustbringer were picked for the same low/mid/high escalation
     * their own [BossSpec.minLevel] order already gives, spread across this
     * run's own depth instead - and leaving Old Ironclaw and Circuit Reaper
     * out keeps something exclusive to the level-gated ladder too.
     *
     * Null for every other fight, same as before this existed.
     */
    fun bossIdFor(fight: Int): String? = when (fight) {
        5 -> "junk_golem"
        10 -> "boiler_baron"
        15 -> "rustbringer"
        else -> null
    }

    // ---- run state ---------------------------------------------------------

    fun isActive(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_ACTIVE, false)

    fun ratId(prefs: SharedPreferences): Long = prefs.getLong(KEY_RAT_ID, -1L)

    /** The fight currently pending - 1 through [TOTAL_FIGHTS]. */
    fun currentFight(prefs: SharedPreferences): Int = prefs.getInt(KEY_FIGHT, 1)

    /** HP the next fight should start with, or null for a full heal (fight 1). */
    fun carriedHpFor(prefs: SharedPreferences): Int? =
        if (prefs.contains(KEY_CARRIED_HP)) prefs.getInt(KEY_CARRIED_HP, 0) else null

    fun scrapRunTotal(prefs: SharedPreferences): Int = prefs.getInt(KEY_SCRAP_EARNED, 0)

    fun relicsRunTotal(prefs: SharedPreferences): Int = prefs.getInt(KEY_RELICS_EARNED, 0)

    /**
     * Marks a run active for [ratId], starting at fight 1.
     *
     * Called right after [Arena.raiseFirstFight] has raised that fight - this
     * only records the run around it, it does not raise anything itself.
     */
    fun begin(prefs: SharedPreferences, ratId: Long) {
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_RAT_ID, ratId)
            .putInt(KEY_FIGHT, 1)
            .remove(KEY_CARRIED_HP)
            .putInt(KEY_SCRAP_EARNED, 0)
            .putInt(KEY_RELICS_EARNED, 0)
            .apply()
    }

    /** Clears every run-scoped key. Milestone latches are permanent and untouched. */
    fun end(prefs: SharedPreferences) {
        prefs.edit()
            .remove(KEY_ACTIVE)
            .remove(KEY_RAT_ID)
            .remove(KEY_FIGHT)
            .remove(KEY_CARRIED_HP)
            .remove(KEY_SCRAP_EARNED)
            .remove(KEY_RELICS_EARNED)
            .apply()
    }

    /** A loss ends the run; read the tally before calling, since this clears it. */
    fun endWithLossSummary(prefs: SharedPreferences): ArenaLossSummary {
        val summary = ArenaLossSummary(
            fightReached = currentFight(prefs),
            scrapRunTotal = scrapRunTotal(prefs),
            relicsRunTotal = relicsRunTotal(prefs)
        )
        end(prefs)
        return summary
    }

    // ---- milestones ----------------------------------------------------------

    fun isMilestoneEarned(prefs: SharedPreferences, fight: Int): Boolean =
        prefs.getBoolean(MILESTONE_LATCH_PREFIX + fight, false)

    fun milestonesEarnedCount(prefs: SharedPreferences): Int =
        MILESTONES.count { isMilestoneEarned(prefs, it.fight) }

    /** Latches [fight]'s badge and returns it, or null if already earned. */
    private fun markMilestone(editor: SharedPreferences.Editor, prefs: SharedPreferences, fight: Int): Int? {
        if (isMilestoneEarned(prefs, fight)) return null
        editor.putBoolean(MILESTONE_LATCH_PREFIX + fight, true)
        return fight
    }

    // ---- the Arena Cleared cosmetic -------------------------------------------

    /**
     * The frame Arena Cleared grants, or null once every frame is owned.
     *
     * [Frames.ARENA_CHAMPION] is guaranteed the first time - the flagship
     * prize a run this long is built around, not one more entry a weighted
     * pool might simply skip. Once owned, it drops out of contention the same
     * way every other frame already does, and a repeat clear falls back to
     * the ordinary pool.
     *
     * That pool is drawn from every remaining [CardFrame.arenaPool] frame.
     * Weighted by [CardFrame.price] instead of picked uniformly, so
     * the frames priced highest - the premium pair this is meant to feel like
     * a real payoff for reaching - come up markedly more often than Brass or
     * Ember, without making them a lock. Scoped to [CardFrame.arenaPool]
     * rather than all of [Frames.all] so a frame earned a specific other way
     * - see [AchievementRewards] - cannot also drop out of a lucky Arena
     * clear with the real thing it stands for never actually met.
     */
    private fun weightedClearedFrame(prefs: SharedPreferences): CardFrame? {
        if (!ShopEffects.ownsCosmetic(prefs, Frames.ARENA_CHAMPION.id)) return Frames.ARENA_CHAMPION

        val candidates = Frames.all.filter { it.arenaPool }.filterNot { ShopEffects.ownsCosmetic(prefs, it.id) }
        if (candidates.isEmpty()) return null

        var roll = (0 until candidates.sumOf { it.price }).random()
        for (frame in candidates) {
            roll -= frame.price
            if (roll < 0) return frame
        }
        return candidates.last()
    }

    // ---- settling a win --------------------------------------------------------

    /**
     * Banks a cleared fight's rewards, advances the run, and either raises the
     * next fight's [Encounter] or - at fight 15 - grants the Arena Cleared
     * cosmetic and ends the run.
     *
     * [ratHpAfterFight] is carried into the next fight untouched; there is no
     * heal between fights by design. Blocking: it may query the collection to
     * raise the next fight.
     */
    fun recordWin(
        dao: RatDao,
        prefs: SharedPreferences,
        fightJustCleared: Int,
        scrapEarnedThisFight: Int,
        ratHpAfterFight: Int,
        playerLevel: Int
    ): ArenaFightOutcome {
        val relic = Relics.rollFor(relicChanceFor(fightJustCleared))
        val editor = prefs.edit()

        // Fetched once, up front, rather than only where the next fight is
        // raised below - the fight-3/6/9/12 HP relief needs it just as much,
        // to know the rat's own true max rather than guessing at one.
        val fighter = dao.byId(ratId(prefs))

        val scrapTotal = scrapRunTotal(prefs) + scrapEarnedThisFight
        editor.putInt(KEY_SCRAP_EARNED, scrapTotal)

        relic?.let {
            Relics.grant(prefs, editor, it)
            editor.putInt(KEY_RELICS_EARNED, relicsRunTotal(prefs) + 1)
        }

        val milestone = if (fightJustCleared in MILESTONE_FIGHTS) {
            markMilestone(editor, prefs, fightJustCleared)
        } else {
            null
        }

        val cleared = fightJustCleared >= TOTAL_FIGHTS
        var cosmeticFrame: CardFrame? = null
        var cosmeticScrapFallback = 0

        if (cleared) {
            val frame = weightedClearedFrame(prefs)
            if (frame != null) {
                cosmeticFrame = frame
            } else {
                cosmeticScrapFallback = RelicTrader.SCRAP_PAYOUT.random()
                editor.putInt(GameEngine.KEY_SCRAP, GameEngine.scrapOf(prefs) + cosmeticScrapFallback)
            }
        } else {
            // Every fight cleared, the rat gets a breather: 30% of its own
            // max HP topped up before the next fight starts, on top of
            // whatever it carried out of this one. Capped at the rat's own
            // true max - not the next fight's, which a Loadout could inflate -
            // so this reads as a partial heal, not a licence to overheal
            // through a Golden Wrench.
            val carried = if (fightJustCleared % RELIEF_EVERY_N_FIGHTS == 0 && fighter != null) {
                // Cracked Vial's own passive heal tops up on top of the
                // relief every rat already gets, the same additive layering
                // Compass Charm/EnlargedRatDialog use for the Scrap Run.
                val reliefFraction = ARENA_RELIEF_FRACTION + GearEffects.passiveHealFractionFor(prefs)
                val relief = (fighter.effectiveMaxHp * reliefFraction).roundToInt()
                min(fighter.effectiveMaxHp, ratHpAfterFight + relief)
            } else {
                ratHpAfterFight
            }
            editor.putInt(KEY_CARRIED_HP, carried)
            editor.putInt(KEY_FIGHT, fightJustCleared + 1)
        }

        editor.apply()

        // Granted through ShopEffects rather than staged on the shared editor
        // above - it commits its own write, the same way ShopActivity's
        // purchase path already does.
        cosmeticFrame?.let { ShopEffects.grantCosmetic(prefs, it.id) }

        // Rusted Fang's own commit, the same way - see
        // PermanentBuffs.RUSTED_FANG_ARENA_FIGHT. Checked directly against
        // fight 10 rather than reusing the `milestone` result above: that one
        // is null once fight 10's own Arena badge is already earned, but a
        // save that somehow has the badge without the buff (an old save
        // imported after this shipped) must still be able to pick it up.
        if (fightJustCleared == PermanentBuffs.RUSTED_FANG_ARENA_FIGHT) {
            PermanentBuffs.checkRustedFang(prefs)
        }

        if (cleared) {
            end(prefs)
        } else {
            if (fighter != null) {
                val nextFight = fightJustCleared + 1
                val bot = rustbotFor(nextFight, fighter)
                val reward = max(
                    1,
                    (RustbotFactory.rewardFor(playerLevel) * scrapMultiplierFor(nextFight)).roundToInt()
                )
                Encounter.save(
                    prefs,
                    Encounter(
                        ratId = fighter.id,
                        botName = bot.name,
                        botPower = bot.power,
                        botMaxHp = bot.maxHp,
                        reward = reward,
                        bossId = bossIdFor(nextFight)
                    )
                )
            }
        }

        return ArenaFightOutcome(
            fightCleared = fightJustCleared,
            scrapThisFight = scrapEarnedThisFight,
            scrapRunTotal = scrapTotal,
            relicEarned = relic,
            milestoneFightNewlyEarned = milestone,
            cleared = cleared,
            cosmeticFrame = cosmeticFrame,
            cosmeticScrapFallback = cosmeticScrapFallback
        )
    }
}
