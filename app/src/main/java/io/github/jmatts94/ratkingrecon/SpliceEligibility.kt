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

    /** Why a rat is missing from [excludedIds]'s complement, for the tap that lands on it anyway. */
    sealed class ExclusionReason {
        object BattleRat : ExclusionReason()
        object ScrapRun : ExclusionReason()
        data class LedgerTask(val title: String) : ExclusionReason()
        object Protected : ExclusionReason()
    }

    /**
     * Which of [excludedIds]'s reasons applies to [ratId], for a tap that lands
     * on an excluded card. Re-checks the same prefs rather than caching a
     * verdict from [excludedIds], since a courtesy exclusion can lapse between
     * that call and this tap (a task claimed elsewhere, an Expedition landing).
     * Returns null if [ratId] is not actually excluded any more.
     */
    fun reasonFor(prefs: SharedPreferences, ratId: Long, roster: List<RatEntity>): ExclusionReason? {
        if (roster.any { it.id == ratId && it.artKey == TIMETAIL_ART_KEY }) return ExclusionReason.Protected

        if (BattleRat.idOf(prefs) == ratId) return ExclusionReason.BattleRat

        if (prefs.getBoolean(ShopEffects.KEY_EXPEDITION_ACTIVE, false) &&
            prefs.getLong("DEPLOYED_RAT_ID", -1L) == ratId
        ) return ExclusionReason.ScrapRun

        LedgerTasks.all.forEach { tier ->
            if (LedgerTasks.isRunning(prefs, tier) &&
                prefs.getLong(LedgerTasks.assignedRatKey(tier.id), LedgerTasks.NO_RAT) == ratId
            ) {
                return ExclusionReason.LedgerTask(LedgerTasks.stored(prefs, tier).title)
            }
        }

        return null
    }
}
