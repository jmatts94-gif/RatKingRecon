package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import kotlin.math.max
import kotlin.math.roundToInt

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
    val MILESTONE_FIGHTS = setOf(5, 10, 15)

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

    /** Latches [fight]'s badge and returns it, or null if already earned. */
    private fun markMilestone(editor: SharedPreferences.Editor, prefs: SharedPreferences, fight: Int): Int? {
        if (isMilestoneEarned(prefs, fight)) return null
        editor.putBoolean(MILESTONE_LATCH_PREFIX + fight, true)
        return fight
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
            val frameId = RelicTrader.availableFrames(prefs).firstOrNull()
            if (frameId != null) {
                cosmeticFrame = Frames.byId(frameId)
            } else {
                cosmeticScrapFallback = RelicTrader.SCRAP_PAYOUT.random()
                editor.putInt(GameEngine.KEY_SCRAP, GameEngine.scrapOf(prefs) + cosmeticScrapFallback)
            }
        } else {
            editor.putInt(KEY_CARRIED_HP, ratHpAfterFight)
            editor.putInt(KEY_FIGHT, fightJustCleared + 1)
        }

        editor.apply()

        // Granted through ShopEffects rather than staged on the shared editor
        // above - it commits its own write, the same way ShopActivity's
        // purchase path already does.
        cosmeticFrame?.let { ShopEffects.grantCosmetic(prefs, it.id) }

        if (cleared) {
            end(prefs)
        } else {
            val fighter = dao.byId(ratId(prefs))
            if (fighter != null) {
                val nextFight = fightJustCleared + 1
                val bot = RustbotFactory.forEncounter(playerLevel, fighter)
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
                        reward = reward
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
