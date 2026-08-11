package com.example.ratkingrecon

import android.content.Context

/**
 * Applies a finished battle to the save.
 *
 * Both the manual screen and Auto-Resolve funnel through here, so a win pays
 * the same and a loss costs the same however the fight was played.
 */
object EncounterResolver {

    /** How long a beaten rat is out of action. */
    const val RECOVERY_MS = 30L * 60L * 1000L

    data class Resolution(
        val won: Boolean,
        val ratName: String,
        val botName: String,
        val reward: Int,
        val ratHpLeft: Int,
        val recoveringUntil: Long,
        /** True when a Shop Revive Token was spent to skip the knockout. */
        val revivedByToken: Boolean = false
    )

    /**
     * Banks the result and clears the pending encounter.
     *
     * Blocking, so call it off the main thread.
     */
    fun apply(context: Context, encounter: Encounter, rat: RatEntity, battle: Battle): Resolution {
        val app = context.applicationContext
        val dao = RatRepository.dao(app)
        val prefs = RatRepository.prefs(app)

        val won = battle.outcome == BattleOutcome.PLAYER_WON
        var recoveringUntil = 0L
        var revivedByToken = false

        if (won) {
            dao.recordWin(rat.id)
            prefs.edit()
                .putInt(GameEngine.KEY_SCRAP, GameEngine.scrapOf(prefs) + encounter.reward)
                .apply()
        } else {
            // A Shop Revive Token is spent here rather than offered: it was
            // bought ahead of time precisely so the loss does not cost 30
            // minutes. The pay-now dialog in BattleActivity is untouched and
            // still covers a player holding no token.
            revivedByToken = ShopEffects.spendCharge(prefs, ShopEffects.KEY_REVIVE_TOKENS)

            if (!revivedByToken) {
                recoveringUntil = System.currentTimeMillis() + RECOVERY_MS
            }
            dao.recordLoss(rat.id, recoveringUntil)
        }

        // A Power Surge is burned by the fight it was carried into, win or lose,
        // and here rather than at the start so leaving a fight does not eat it.
        prefs.edit().putBoolean(ShopEffects.KEY_POWER_SURGE, false).apply()

        Encounter.clear(prefs)

        return Resolution(
            won = won,
            ratName = rat.name,
            botName = encounter.botName,
            reward = if (won) encounter.reward else 0,
            ratHpLeft = battle.ratHp,
            recoveringUntil = recoveringUntil,
            revivedByToken = revivedByToken
        )
    }

    /**
     * Wakes a knocked-out rat early for Scrap.
     *
     * Returns false and changes nothing when the player cannot afford it, so
     * the caller can just report the failure.
     */
    fun revive(context: Context, ratId: Long): Boolean {
        val app = context.applicationContext
        val prefs = RatRepository.prefs(app)
        val cost = RustbotFactory.reviveCost(GameEngine.levelOf(prefs))
        val scrap = GameEngine.scrapOf(prefs)

        if (scrap < cost) return false

        prefs.edit().putInt(GameEngine.KEY_SCRAP, scrap - cost).apply()
        RatRepository.dao(app).revive(ratId)
        return true
    }
}
