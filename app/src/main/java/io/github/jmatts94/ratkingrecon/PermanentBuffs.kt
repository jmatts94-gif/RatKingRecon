package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * One permanent, account-wide buff - see [PermanentBuffs].
 *
 * [id] reaches the save as the latch behind the badge, so it must never
 * change once shipped - the same rule [Milestone.id] follows.
 */
data class PermanentBuff(
    val id: String,
    @param:StringRes val nameRes: Int,
    @param:StringRes val effectRes: Int,
    @param:DrawableRes val iconRes: Int
)

/**
 * Four fixed, permanent, account-wide combat/economy buffs, unlocked once and
 * never spent - a different lifecycle from [ShopEffects], which arms and then
 * burns whatever the Shop sold. Latched the same way [Milestones] latches a
 * badge: once earned, a boolean in the save that is only ever set, never
 * cleared, so the Achievements screen can render these with the exact same
 * checkmark/highlight treatment.
 *
 * No purchase step and no economy interaction - earning one applies it
 * immediately and for good. Each `check*` function is called from the one
 * place its trigger event already happens (a hatch banked, a streak
 * completed, an Arena fight cleared, lifetime steps banked) and is a no-op
 * once the buff is already earned or the threshold is not yet met.
 */
object PermanentBuffs {

    private const val LATCH_PREFIX = "BUFF_"

    // ---- unlock thresholds -----------------------------------------------

    const val COLLECTORS_INSTINCT_HATCH_TARGET = 5L
    const val STEADFAST_MOMENTUM_STREAK_TARGET = 10
    const val RUSTED_FANG_ARENA_FIGHT = 10
    const val IRON_BOOTS_STEP_TARGET = 30_000L

    // ---- fixed effect magnitudes -------------------------------------------

    /**
     * Extra width added to the Fusion Pot's combined Rare-or-better band -
     * see [Fusion.speciesFor]. A flat 10 percentage points onto the existing
     * cutoff, not a multiplier on the existing odds - "fixed value, no
     * stacking, no scaling" ruled out a relative boost that would itself
     * scale with whatever LEGENDARY_ROLL/RARE_ROLL happen to be tuned to.
     * Legendary's own 5% is left untouched; the extra ten points widen the
     * Rare band instead, at Common's expense.
     */
    const val COLLECTORS_INSTINCT_ODDS_BONUS = 0.10

    /** Multiplies both the Contract Board's Scrap payout and steps' own EXP bank. */
    const val STEADFAST_MOMENTUM_MULTIPLIER = 1.15

    /**
     * Share of damage dealt healed back, on every hit that lands - see
     * [Battle.advance].
     *
     * Doubled from the 5% this shipped with once the Smuggler faction's own
     * Special-only lifesteal was retired (see [FactionSpecials]'s own doc
     * comment on why) - this is the only source of combat sustain left in the
     * game, so it needed to carry more of the load than it did when it was
     * one of two.
     */
    const val RUSTED_FANG_LIFESTEAL_FRACTION = 0.10

    /** Share of incoming damage shaved off, Arena fights only - see [Battle.advance]. */
    const val IRON_BOOTS_DAMAGE_REDUCTION = 0.10

    // ---- the four buffs -----------------------------------------------------

    val COLLECTORS_INSTINCT = PermanentBuff(
        "collectors_instinct", R.string.buff_collectors_instinct_name,
        R.string.buff_collectors_instinct_effect, R.drawable.ic_sparkle
    )
    val STEADFAST_MOMENTUM = PermanentBuff(
        "steadfast_momentum", R.string.buff_steadfast_momentum_name,
        R.string.buff_steadfast_momentum_effect, R.drawable.ic_footprint_lifetime
    )
    val RUSTED_FANG = PermanentBuff(
        "rusted_fang", R.string.buff_rusted_fang_name,
        R.string.buff_rusted_fang_effect, R.drawable.ic_toughness
    )
    val IRON_BOOTS = PermanentBuff(
        "iron_boots", R.string.buff_iron_boots_name,
        R.string.buff_iron_boots_effect, R.drawable.ic_gear_double
    )

    val all: List<PermanentBuff> = listOf(COLLECTORS_INSTINCT, STEADFAST_MOMENTUM, RUSTED_FANG, IRON_BOOTS)

    // ---- latching -------------------------------------------------------------

    fun isEarned(prefs: SharedPreferences, buff: PermanentBuff): Boolean =
        prefs.getBoolean(LATCH_PREFIX + buff.id, false)

    fun earnedCount(prefs: SharedPreferences): Int = all.count { isEarned(prefs, it) }

    private fun latch(prefs: SharedPreferences, buff: PermanentBuff) {
        if (isEarned(prefs, buff)) return
        prefs.edit().putBoolean(LATCH_PREFIX + buff.id, true).apply()
    }

    /** Checked wherever a hatch is banked - see [GameEngine.recordHatch]. */
    fun checkCollectorsInstinct(prefs: SharedPreferences, lifetimeHatches: Long) {
        if (lifetimeHatches >= COLLECTORS_INSTINCT_HATCH_TARGET) latch(prefs, COLLECTORS_INSTINCT)
    }

    /** Checked once a completed day's streak is banked - see [DailyQuest.complete]. */
    fun checkSteadfastMomentum(prefs: SharedPreferences, streakCount: Int) {
        if (streakCount >= STEADFAST_MOMENTUM_STREAK_TARGET) latch(prefs, STEADFAST_MOMENTUM)
    }

    /** Called only on the win that clears fight 10 - see [ArenaRun.recordWin]. */
    fun checkRustedFang(prefs: SharedPreferences) {
        latch(prefs, RUSTED_FANG)
    }

    /** Checked wherever lifetime steps are banked - see [GameEngine.onSteps]. */
    fun checkIronBoots(prefs: SharedPreferences, lifetimeSteps: Long) {
        if (lifetimeSteps >= IRON_BOOTS_STEP_TARGET) latch(prefs, IRON_BOOTS)
    }

    /**
     * Re-checks all four against the save's current state - the same safety
     * net [Milestones.refresh] is for its own badges, called from the same
     * place (AchievementsActivity.onResume). A trigger missed at the moment
     * it happened - most notably a save that cleared Arena fight 10 before
     * this feature shipped, so [checkRustedFang] never ran for it - would
     * otherwise be locked out of the buff forever, since none of the four
     * `check*` functions above run except from their own live trigger.
     */
    fun refresh(prefs: SharedPreferences) {
        checkCollectorsInstinct(prefs, GameEngine.lifetimeHatchesOf(prefs))
        checkSteadfastMomentum(prefs, Streak.count(prefs))
        if (ArenaRun.isMilestoneEarned(prefs, RUSTED_FANG_ARENA_FIGHT)) checkRustedFang(prefs)
        checkIronBoots(prefs, GameEngine.lifetimeStepsOf(prefs))
    }

    // ---- effect readers, read at the point of use --------------------------

    /** Extra points added to the Fusion Pot's Rare-or-better cutoff; 0 until earned. */
    fun spliceOddsBonusFor(prefs: SharedPreferences): Double =
        if (isEarned(prefs, COLLECTORS_INSTINCT)) COLLECTORS_INSTINCT_ODDS_BONUS else 0.0

    /** What Contract Board payouts and steps' own EXP are multiplied by; 1.0 until earned. */
    fun stepRewardMultiplierFor(prefs: SharedPreferences): Double =
        if (isEarned(prefs, STEADFAST_MOMENTUM)) STEADFAST_MOMENTUM_MULTIPLIER else 1.0

    /** Share of damage dealt healed back; 0 until earned. Applies in every fight, not Arena-only. */
    fun lifestealFractionFor(prefs: SharedPreferences): Double =
        if (isEarned(prefs, RUSTED_FANG)) RUSTED_FANG_LIFESTEAL_FRACTION else 0.0

    /** Share of incoming damage shaved off; 0 until earned. Callers gate this to Arena fights only. */
    fun arenaDamageReductionFor(prefs: SharedPreferences): Double =
        if (isEarned(prefs, IRON_BOOTS)) IRON_BOOTS_DAMAGE_REDUCTION else 0.0
}
