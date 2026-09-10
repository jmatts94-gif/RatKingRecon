package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import androidx.annotation.StringRes

/**
 * What an exchange hands back.
 *
 * [ItemVoucher] cannot be settled by the trader alone - it comes in more than
 * one kind and the player picks - so it carries no payload here. The screen
 * asks, then calls back in with the choice.
 */
sealed interface RelicReward {

    /** A random payout inside [range]. */
    data class Scrap(val range: IntRange) : RelicReward

    /** One Revive Token, added to the stack the Shop already sells. */
    data object ReviveToken : RelicReward

    /** One combat item, chosen, added to the held count the Shop's own cap governs. */
    data object ItemVoucher : RelicReward

    /** Waives the Scrap cost of the player's next Arena entry. */
    data object ArenaEntryVoucher : RelicReward

    /** Doubles the EXP the player's next batch of steps banks - see ShopEffects.KEY_TRAIL_RATIONS. */
    data object TrailRations : RelicReward
}

/**
 * Why an exchange cannot go ahead.
 *
 * Refusals are a type rather than a bare false, because each one needs to say
 * something different on screen and none of them may take the relics.
 */
sealed interface RelicRefusal {
    data class NotEnough(val held: Int, val needed: Int) : RelicRefusal

    /** Every combat item is already at the Shop's own hold cap. */
    data object EveryItemFull : RelicRefusal
}

data class RelicExchange(
    val id: String,
    val relic: Relic,
    val cost: Int,
    val reward: RelicReward,
    @param:StringRes val titleRes: Int,
    @param:StringRes val bodyRes: Int,
    /**
     * Filled into [bodyRes] where it carries placeholders, and empty otherwise.
     *
     * Only the Scrap trade needs it, and only so the price it advertises is the
     * one [RelicTrader.SCRAP_PAYOUT] actually pays rather than a second copy of
     * the same numbers written into the string.
     */
    val bodyArgs: List<Any> = emptyList()
)

/**
 * The Relic Trader's stock, and the rules for trading with it.
 *
 * Kept out of the Activity in the way [Bounties] and [LedgerTasks] are, so the
 * one rule that matters can be tested: an exchange that cannot deliver must not
 * take the relics. That is the same guarantee the Shop already makes about
 * Scrap - nothing leaves until the effect has been accepted. No exchange below
 * grants [RelicReward.ItemVoucher] any more, but the type - and the refusal it
 * can hand back when every combat item already sits at the Shop's own hold cap
 * - stays live: [availableItemChoices] backs [AchievementRewards.SalvageCache]
 * directly, the same cap either path would hit.
 */
object RelicTrader {

    /** Every exchange costs the same number of one relic. */
    const val COST = 3

    val SCRAP_PAYOUT = 150..250

    val exchanges: List<RelicExchange> = listOf(
        RelicExchange(
            id = "gear_scrap",
            relic = Relics.ALL[0],
            cost = COST,
            reward = RelicReward.Scrap(SCRAP_PAYOUT),
            titleRes = R.string.trade_gear_title,
            bodyRes = R.string.trade_gear_body,
            bodyArgs = listOf(SCRAP_PAYOUT.first, SCRAP_PAYOUT.last)
        ),
        RelicExchange(
            id = "vial_revive",
            relic = Relics.ALL[1],
            cost = COST,
            reward = RelicReward.ReviveToken,
            titleRes = R.string.trade_vial_title,
            bodyRes = R.string.trade_vial_body
        ),
        RelicExchange(
            id = "blueprint_rations",
            relic = Relics.ALL[2],
            cost = COST,
            reward = RelicReward.TrailRations,
            titleRes = R.string.trade_blueprint_title,
            bodyRes = R.string.trade_blueprint_body
        ),
        RelicExchange(
            id = "wrench_arena_entry",
            relic = Relics.ALL[3],
            cost = COST,
            reward = RelicReward.ArenaEntryVoucher,
            titleRes = R.string.trade_wrench_title,
            bodyRes = R.string.trade_wrench_body
        )
    )

    /** Which [ShopEffects] charge key holds a [BattleItem]'s count - see [Battle.applyItem]. */
    private fun keyFor(item: BattleItem): String = when (item) {
        BattleItem.HP_TONIC -> ShopEffects.KEY_HP_TONIC
        BattleItem.CORROSIVE_CHARGE -> ShopEffects.KEY_CORROSIVE_CHARGE
        BattleItem.REINFORCED_PLATING -> ShopEffects.KEY_REINFORCED_PLATING
        BattleItem.CLEANSE -> ShopEffects.KEY_CLEANSE
    }

    /**
     * The combat items the player could still be given a voucher for.
     *
     * Not every item - one already sitting at the Shop's own
     * [ShopEffects.ITEM_CHARGE_CAP] has nowhere for a voucher to land.
     */
    fun availableItemChoices(prefs: SharedPreferences): List<BattleItem> =
        BattleItem.entries.filterNot { ShopEffects.charges(prefs, keyFor(it)) >= ShopEffects.ITEM_CHARGE_CAP }

    /**
     * Why [exchange] cannot go ahead, or null when it can.
     *
     * Checked before anything is spent, and checked again by [trade] - the
     * screen may have been sitting open while a fight armed a buff underneath
     * it.
     */
    fun refusalFor(prefs: SharedPreferences, exchange: RelicExchange): RelicRefusal? {
        val held = Relics.countOf(prefs, exchange.relic)
        if (held < exchange.cost) return RelicRefusal.NotEnough(held, exchange.cost)

        return when (exchange.reward) {
            is RelicReward.ItemVoucher ->
                if (availableItemChoices(prefs).isEmpty()) RelicRefusal.EveryItemFull else null

            else -> null
        }
    }

    fun canTrade(prefs: SharedPreferences, exchange: RelicExchange): Boolean =
        refusalFor(prefs, exchange) == null

    /**
     * Settles an exchange, taking the relics only once the reward has landed.
     *
     * [choiceId] carries the item the player picked, and is ignored by the
     * three exchanges that do not ask. Returns the refusal that stopped it, or
     * null when it went through.
     */
    fun trade(
        prefs: SharedPreferences,
        exchange: RelicExchange,
        choiceId: String? = null
    ): RelicRefusal? {
        refusalFor(prefs, exchange)?.let { return it }

        when (val reward = exchange.reward) {
            is RelicReward.Scrap ->
                prefs.edit()
                    .putInt(
                        GameEngine.KEY_SCRAP,
                        prefs.getInt(GameEngine.KEY_SCRAP, 0) + reward.range.random()
                    )
                    .apply()

            is RelicReward.ReviveToken ->
                ShopEffects.addCharge(prefs, ShopEffects.KEY_REVIVE_TOKENS)

            is RelicReward.ItemVoucher -> {
                // Falls back to the first item still short of the cap rather
                // than trusting the screen: a stale or unrecognised choice
                // would otherwise waste the relics on a refusal the screen
                // already thought it had avoided.
                val item = choiceId?.let { runCatching { BattleItem.valueOf(it) }.getOrNull() }
                    ?.takeIf { it in availableItemChoices(prefs) }
                    ?: availableItemChoices(prefs).firstOrNull()
                    ?: return RelicRefusal.EveryItemFull
                ShopEffects.addCharge(prefs, keyFor(item))
            }

            is RelicReward.ArenaEntryVoucher ->
                ShopEffects.addCharge(prefs, ShopEffects.KEY_ARENA_ENTRY_VOUCHER)

            is RelicReward.TrailRations ->
                prefs.edit().putBoolean(ShopEffects.KEY_TRAIL_RATIONS, true).apply()
        }

        Relics.spend(prefs, exchange.relic, exchange.cost)
        return null
    }
}
