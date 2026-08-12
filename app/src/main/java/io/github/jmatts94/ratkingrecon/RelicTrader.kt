package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import androidx.annotation.StringRes

/**
 * What an exchange hands back.
 *
 * Two of the four cannot be settled by the trader alone - a frame and a combat
 * buff both come in two kinds and the player picks - so those carry no payload
 * here. The screen asks, then calls back in with the choice.
 */
sealed interface RelicReward {

    /** A random payout inside [range]. */
    data class Scrap(val range: IntRange) : RelicReward

    /** One Binder frame, chosen from whichever are not already owned. */
    data object Frame : RelicReward

    /** A Power Surge or a Golden Wrench, chosen. */
    data object CombatBuff : RelicReward

    /** Money off the next Masterwork Hatchery pull. */
    data object MasterworkVoucher : RelicReward
}

/**
 * Why an exchange cannot go ahead.
 *
 * Refusals are a type rather than a bare false, because each one needs to say
 * something different on screen and none of them may take the relics.
 */
sealed interface RelicRefusal {
    data class NotEnough(val held: Int, val needed: Int) : RelicRefusal

    /** Both frames already owned, so there is nothing left to hand over. */
    data object OwnsEveryFrame : RelicRefusal

    /** A Surge or Wrench is already armed, and only one can ride a fight. */
    data object BuffAlreadyArmed : RelicRefusal
}

data class RelicExchange(
    val id: String,
    val relic: Relic,
    val cost: Int,
    val reward: RelicReward,
    @param:StringRes val titleRes: Int,
    @param:StringRes val bodyRes: Int
)

/**
 * The Relic Trader's stock, and the rules for trading with it.
 *
 * Kept out of the Activity in the way [Bounties] and [LedgerTasks] are, so the
 * one rule that matters can be tested: an exchange that cannot deliver must not
 * take the relics. That is the same guarantee the Shop already makes about
 * Scrap - nothing leaves until the effect has been accepted - and it is easy to
 * get wrong here, because two of the four rewards can be unavailable for
 * reasons that have nothing to do with what the player is holding.
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
            bodyRes = R.string.trade_gear_body
        ),
        RelicExchange(
            id = "vial_frame",
            relic = Relics.ALL[1],
            cost = COST,
            reward = RelicReward.Frame,
            titleRes = R.string.trade_vial_title,
            bodyRes = R.string.trade_vial_body
        ),
        RelicExchange(
            id = "blueprint_buff",
            relic = Relics.ALL[2],
            cost = COST,
            reward = RelicReward.CombatBuff,
            titleRes = R.string.trade_blueprint_title,
            bodyRes = R.string.trade_blueprint_body
        ),
        RelicExchange(
            id = "wrench_voucher",
            relic = Relics.ALL[3],
            cost = COST,
            reward = RelicReward.MasterworkVoucher,
            titleRes = R.string.trade_wrench_title,
            bodyRes = R.string.trade_wrench_body
        )
    )

    /** The frames the player could still be given, in catalogue order. */
    fun availableFrames(prefs: SharedPreferences): List<String> =
        listOf(Shop.FRAME_BRASS, Shop.FRAME_EMBER)
            .filterNot { ShopEffects.ownsCosmetic(prefs, it) }

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
            is RelicReward.Frame ->
                if (availableFrames(prefs).isEmpty()) RelicRefusal.OwnsEveryFrame else null

            is RelicReward.CombatBuff ->
                if (ShopEffects.powerSurgeArmed(prefs) || ShopEffects.wrenchArmed(prefs)) {
                    RelicRefusal.BuffAlreadyArmed
                } else {
                    null
                }

            else -> null
        }
    }

    fun canTrade(prefs: SharedPreferences, exchange: RelicExchange): Boolean =
        refusalFor(prefs, exchange) == null

    /**
     * Settles an exchange, taking the relics only once the reward has landed.
     *
     * [choiceId] carries the frame or buff the player picked, and is ignored by
     * the two exchanges that do not ask. Returns the refusal that stopped it, or
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

            is RelicReward.Frame -> {
                // Falls back to the first frame still available rather than
                // trusting the screen: a stale choice would otherwise re-grant
                // something already owned and waste the relics.
                val frame = choiceId?.takeIf { it in availableFrames(prefs) }
                    ?: availableFrames(prefs).firstOrNull()
                    ?: return RelicRefusal.OwnsEveryFrame
                ShopEffects.grantCosmetic(prefs, frame)
            }

            is RelicReward.CombatBuff -> {
                val key = when (choiceId) {
                    ShopEffects.KEY_GOLDEN_WRENCH -> ShopEffects.KEY_GOLDEN_WRENCH
                    else -> ShopEffects.KEY_POWER_SURGE
                }
                prefs.edit().putBoolean(key, true).apply()
            }

            is RelicReward.MasterworkVoucher ->
                ShopEffects.addCharge(prefs, ShopEffects.KEY_MASTERWORK_VOUCHER)
        }

        Relics.spend(prefs, exchange.relic, exchange.cost)
        return null
    }
}
