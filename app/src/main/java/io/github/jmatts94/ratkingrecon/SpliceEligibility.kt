package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences

/**
 * Which rats the Splicing screen will not let the player pick.
 *
 * Every exclusion but one here is a courtesy: splicing away a rat that is on
 * combat duty, out on an Expedition, or named on a running Ledger Task's bonus
 * slot has always been handled gracefully rather than blocked elsewhere (see
 * [LedgerTasks.assignedRatKey] and the comment on `DEPLOYED_RAT_ID` in
 * MainActivity) - those three exist only so the Splicing screen, where the
 * player is choosing on purpose, does not let them do it by accident.
 *
 * TimeTail is the one rat excluded everywhere, unconditionally - see
 * [Roster.SECRET]. He cannot be fodder even mid-battle, mid-Expedition or
 * mid-Task, which the other three checks do not claim to guarantee.
 */
object SpliceEligibility {

    /** [Rat.artKey] for the one species this whole object exists to protect - see [Roster.SECRET]. */
    private const val TIMETAIL_ART_KEY = "timetail_pic"

    /**
     * [roster] is the same list the Splicing screen already loaded to show
     * its cards - passed in rather than queried again here, so checking
     * TimeTail's exclusion costs no second trip to the database.
     */
    fun excludedIds(prefs: SharedPreferences, roster: List<RatEntity>): Set<Long> {
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

        roster.forEach { if (it.artKey == TIMETAIL_ART_KEY) ids += it.id }

        return ids
    }
}
