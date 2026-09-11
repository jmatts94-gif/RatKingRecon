package io.github.jmatts94.ratkingrecon

import android.content.Context
import android.content.SharedPreferences

/**
 * What a one-time achievement reward actually grants.
 *
 * A different lifecycle from every other reward in the game: not bought (the
 * Shop), not rolled per-fight (a relic, a Special), not a permanent passive
 * once earned (see [PermanentBuffs]) - a single payout, the moment a Hatching,
 * Roster, Lifetime Steps or Combat Badge achievement latches for the first
 * time. See [AchievementRewards] for which achievement pays which of these.
 */
sealed interface AchievementReward {
    data class Scrap(val amount: Int) : AchievementReward

    /** Arms a free hatch at the Tinkerer's Serum's own stat range - see [GameEngine.KEY_MUTAGEN]. */
    data object FreeMutagen : AchievementReward

    /** One random combat item, the same shape the Relic Trader's Item Voucher already grants. */
    data object SalvageCache : AchievementReward

    /** One Revive Token, added to the stack the Shop already sells. */
    data object ReviveToken : AchievementReward

    data class Cosmetic(val frameId: String) : AchievementReward

    /**
     * TimeTail himself, minted straight into the Ledger - see
     * [Milestones.secret] and [Roster.SECRET]. The one reward here that
     * touches the database rather than a preference, which is why [grant]
     * is the only place in this file that takes a [RatDao].
     */
    data object TimeTail : AchievementReward
}

/**
 * Which achievement pays what, and the mechanics of actually paying it.
 *
 * Every reward here reuses an existing mechanic - Scrap, a Shop flag, a
 * combat-item charge, a cosmetic frame - rather than inventing new save state,
 * so nothing here needs its own migration or its own save-transfer handling.
 *
 * Combat Badges lean on [SalvageCache] and [ReviveToken] rather than flat
 * Scrap throughout: a boss badge is loot off a beaten Rustbot, not a tenth
 * income source, and five identical Scrap rows would have read as the same
 * reward five times over.
 */
object AchievementRewards {

    private val hatching = mapOf(
        "hatch_shiny" to AchievementReward.Scrap(25),
        "hatch_masterwork" to AchievementReward.Scrap(50),
        "hatch_stat15" to AchievementReward.FreeMutagen,
        "hatch_stat25" to AchievementReward.Cosmetic(Frames.IRON_GRIP.id)
    )

    private val roster = mapOf(
        "roster_10" to AchievementReward.Scrap(20),
        "roster_25" to AchievementReward.Scrap(40),
        "roster_50" to AchievementReward.Scrap(75),
        "roster_full" to AchievementReward.Cosmetic(Frames.NATURALISTS_COMPENDIUM.id)
    )

    private val steps = mapOf(
        "steps_10k" to AchievementReward.Scrap(20),
        "steps_50k" to AchievementReward.Scrap(40),
        "steps_100k" to AchievementReward.Scrap(60),
        "steps_250k" to AchievementReward.Scrap(100),
        "steps_500k" to AchievementReward.Scrap(150),
        "steps_1m" to AchievementReward.Cosmetic(Frames.RAT_KINGS_CROWN.id)
    )

    private val splicing = mapOf(
        "splice_first" to AchievementReward.Scrap(15),
        "splice_10" to AchievementReward.FreeMutagen,
        "splice_lucky" to AchievementReward.SalvageCache,
        "splice_25" to AchievementReward.Cosmetic(Frames.CHIMERAS_WEAVE.id)
    )

    /** Keyed on [BossSpec.id], one rung of the ladder at a time. */
    private val combat = mapOf(
        "junk_golem" to AchievementReward.SalvageCache,
        "old_ironclaw" to AchievementReward.SalvageCache,
        "boiler_baron" to AchievementReward.Scrap(60),
        "circuit_reaper" to AchievementReward.SalvageCache,
        // The last, hardest boss on the level-gated ladder pays the one
        // reward every other badge here does not: a Revive Token, so the
        // rat that just proved it can beat the Rustbringer is covered the
        // next time a fight this hard goes the other way.
        "rustbringer" to AchievementReward.ReviveToken
    )

    private val secret = mapOf(
        "timetail_found" to AchievementReward.TimeTail
    )

    fun forMilestone(id: String): AchievementReward? =
        hatching[id] ?: roster[id] ?: steps[id] ?: splicing[id] ?: secret[id]

    fun forBoss(id: String): AchievementReward? = combat[id]

    /**
     * Applies [reward]. Never fails silently: a Salvage Cache with nowhere to
     * land falls back to Scrap.
     *
     * [dao] is null for every caller but [Milestones.refresh] - see the note
     * on [AchievementReward.TimeTail] - and is only ever read in that one
     * branch below.
     */
    fun grant(prefs: SharedPreferences, reward: AchievementReward, dao: RatDao? = null) {
        when (reward) {
            is AchievementReward.Scrap ->
                prefs.edit().putInt(GameEngine.KEY_SCRAP, GameEngine.scrapOf(prefs) + reward.amount).apply()

            AchievementReward.FreeMutagen ->
                prefs.edit().putBoolean(GameEngine.KEY_MUTAGEN, true).apply()

            AchievementReward.SalvageCache -> {
                val item = RelicTrader.availableItemChoices(prefs).randomOrNull()
                if (item != null) {
                    ShopEffects.addCharge(prefs, keyFor(item))
                } else {
                    // Every combat item already at the Shop's own hold cap -
                    // the same edge RelicTrader.trade's own ItemVoucher
                    // refuses outright, but a badge that has already latched
                    // cannot simply refuse to pay. 40 Scrap either way.
                    prefs.edit().putInt(GameEngine.KEY_SCRAP, GameEngine.scrapOf(prefs) + 40).apply()
                }
            }

            AchievementReward.ReviveToken ->
                ShopEffects.addCharge(prefs, ShopEffects.KEY_REVIVE_TOKENS)

            is AchievementReward.Cosmetic ->
                ShopEffects.grantCosmetic(prefs, reward.frameId)

            AchievementReward.TimeTail -> {
                // Rolled at the Mutagen's own stat range rather than the
                // ordinary one - he is meant to arrive already strong, not
                // merely rare. Never shiny: the polish rolls a Ledger already
                // full of ordinary hatches, and he is not one of those.
                requireNotNull(dao) { "TimeTail's reward needs a RatDao - see Milestones.refresh" }
                dao.insert(
                    RatEntity(
                        artKey = "timetail_pic",
                        name = "TimeTail",
                        power = GameEngine.MUTAGEN_STAT.random(),
                        toughness = GameEngine.MUTAGEN_STAT.random(),
                        shiny = false
                    )
                )
            }
        }
    }

    /** The line an achievement row shows for [reward], earned or not yet. */
    fun describe(context: Context, reward: AchievementReward): String = when (reward) {
        is AchievementReward.Scrap -> context.getString(R.string.achievement_reward_scrap, reward.amount)
        AchievementReward.FreeMutagen -> context.getString(R.string.achievement_reward_mutagen)
        AchievementReward.SalvageCache -> context.getString(R.string.achievement_reward_salvage)
        AchievementReward.ReviveToken -> context.getString(R.string.achievement_reward_revive)
        is AchievementReward.Cosmetic -> context.getString(
            R.string.achievement_reward_cosmetic,
            context.getString(requireNotNull(Frames.byId(reward.frameId)) { "unknown frame ${reward.frameId}" }.nameRes)
        )
        AchievementReward.TimeTail -> context.getString(R.string.achievement_reward_timetail)
    }

    /** Same key [BattleItem]'s charge lives under everywhere else - see [ShopEffects]/[RelicTrader]. */
    private fun keyFor(item: BattleItem): String = when (item) {
        BattleItem.HP_TONIC -> ShopEffects.KEY_HP_TONIC
        BattleItem.CORROSIVE_CHARGE -> ShopEffects.KEY_CORROSIVE_CHARGE
        BattleItem.REINFORCED_PLATING -> ShopEffects.KEY_REINFORCED_PLATING
        BattleItem.CLEANSE -> ShopEffects.KEY_CLEANSE
    }
}
