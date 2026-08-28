package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences

/**
 * Which rats the Splicing screen will not let the player pick.
 *
 * None of these are actually protected anywhere else in the game - splicing
 * away a rat that is on combat duty, out on an Expedition, or named on a
 * running Ledger Task's bonus slot has always been handled gracefully rather
 * than blocked (see [LedgerTasks.assignedRatKey] and the comment on
 * `DEPLOYED_RAT_ID` in MainActivity). This exists only so the Splicing screen,
 * where the player is choosing on purpose, does not let them do it by
 * accident.
 */
object SpliceEligibility {

    fun excludedIds(prefs: SharedPreferences): Set<Long> {
        val ids = mutableSetOf<Long>()

        val battleRatId = BattleRat.idOf(prefs)
        if (battleRatId != BattleRat.NONE) ids += battleRatId

        if (prefs.getBoolean(ShopEffects.KEY_EXPEDITION_ACTIVE, false)) {
            val deployedId = prefs.getLong("DEPLOYED_RAT_ID", -1L)
            if (deployedId != -1L) ids += deployedId
        }

        LedgerTasks.all.forEach { tier ->
            if (LedgerTasks.isRunning(prefs, tier)) {
                val ratId = prefs.getLong(LedgerTasks.assignedRatKey(tier.id), LedgerTasks.NO_RAT)
                if (ratId != LedgerTasks.NO_RAT) ids += ratId
            }
        }

        return ids
    }
}
