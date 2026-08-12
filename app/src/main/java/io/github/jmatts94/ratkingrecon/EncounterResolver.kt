package io.github.jmatts94.ratkingrecon

import android.content.Context

/**
 * Applies a finished battle to the save.
 *
 * Both the manual screen and Auto-Resolve funnel through here, so a win pays
 * the same and a loss costs the same however the fight was played.
 */
object EncounterResolver {

    /** How long a beaten rat is out of action once the roster can spare one. */
    const val RECOVERY_MS = 30L * 60L * 1000L

    /**
     * The floor, applied while the roster is a single rat.
     *
     * A knockout takes the only fighter out of play, and GameEngine will not
     * raise an encounter without one, so at roster size 1 the full wait stops
     * combat outright rather than merely costing a rat.
     */
    const val MIN_RECOVERY_MS = 10L * 60L * 1000L

    /** Roster size from which the full [RECOVERY_MS] applies. */
    const val FULL_RECOVERY_ROSTER = 6

    /**
     * How long a beaten rat is out of action, given the size of the roster.
     *
     * Scales straight from [MIN_RECOVERY_MS] at one rat to [RECOVERY_MS] at
     * [FULL_RECOVERY_ROSTER], so the penalty grows as losing a fighter stops
     * being the same as losing combat entirely: 10, 14, 18, 22, 26, then 30
     * minutes. Deliberately keyed to roster size rather than to how many rats
     * happen to be awake, so the wait a player is quoted does not change while
     * they are serving it.
     */
    fun recoveryMsFor(rosterSize: Int): Long {
        if (rosterSize >= FULL_RECOVERY_ROSTER) return RECOVERY_MS
        if (rosterSize <= 1) return MIN_RECOVERY_MS

        val progress = (rosterSize - 1).toDouble() / (FULL_RECOVERY_ROSTER - 1).toDouble()
        return MIN_RECOVERY_MS + ((RECOVERY_MS - MIN_RECOVERY_MS) * progress).toLong()
    }

    data class Resolution(
        val won: Boolean,
        val ratName: String,
        val botName: String,
        val reward: Int,
        val ratHpLeft: Int,
        val recoveringUntil: Long,
        /** True when a Shop Revive Token was spent to skip the knockout. */
        val revivedByToken: Boolean = false,
        /** Whole minutes the rat is out for, for the message shown to the player. */
        val recoveryMinutes: Int = 0,
        /** Which boss this was, if it was one. */
        val bossId: String? = null,
        /** True only on the win that first unlocked the badge. */
        val badgeEarned: Boolean = false
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
        var recoveryMs = 0L
        var revivedByToken = false
        var badgeEarned = false

        if (won) {
            dao.recordWin(rat.id)
            prefs.edit()
                .putInt(GameEngine.KEY_SCRAP, GameEngine.scrapOf(prefs) + encounter.reward)
                .apply()

            // The badge is the first win only; the Scrap is paid every time, at
            // the reduced rate Bosses.rewardFor already worked into the amount
            // banked above when the encounter was built.
            encounter.bossId?.let { badgeEarned = Bosses.markDefeated(prefs, it) }

            // The single point every win passes through, hand-played or
            // auto-resolved, which is why the quest is told about it here
            // rather than in either screen.
            DailyQuest.record(prefs, QuestType.WIN_FIGHT)
        } else {
            // A Shop Revive Token is spent here rather than offered: it was
            // bought ahead of time precisely so the loss does not cost 30
            // minutes. The pay-now dialog in BattleActivity is untouched and
            // still covers a player holding no token.
            revivedByToken = ShopEffects.spendCharge(prefs, ShopEffects.KEY_REVIVE_TOKENS)

            if (!revivedByToken) {
                recoveryMs = recoveryMsFor(dao.count())
                recoveringUntil = System.currentTimeMillis() + recoveryMs
            }
            dao.recordLoss(rat.id, recoveringUntil)
        }

        // One-shot buffs are burned by the fight they were carried into, win or
        // lose, and here rather than at the start so leaving a fight does not
        // eat them.
        ShopEffects.clearOneShotBuffs(prefs)

        Encounter.clear(prefs)

        return Resolution(
            won = won,
            ratName = rat.name,
            botName = encounter.botName,
            reward = if (won) encounter.reward else 0,
            ratHpLeft = battle.ratHp,
            recoveringUntil = recoveringUntil,
            revivedByToken = revivedByToken,
            recoveryMinutes = (recoveryMs / 60_000L).toInt(),
            bossId = encounter.bossId,
            badgeEarned = badgeEarned
        )
    }

    /**
     * The sentence shown after a loss, whichever path played the fight.
     *
     * Shared for the same reason [apply] is: the battle screen and the
     * notification must not drift over how a knockout is described, and only
     * one of them knows whether a Revive Token was spent.
     */
    fun lossMessage(context: Context, resolution: Resolution): String =
        if (resolution.revivedByToken) {
            context.getString(
                R.string.battle_lost_revived, resolution.ratName, resolution.botName
            )
        } else {
            context.getString(
                R.string.battle_lost,
                resolution.ratName, resolution.botName, resolution.recoveryMinutes
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
