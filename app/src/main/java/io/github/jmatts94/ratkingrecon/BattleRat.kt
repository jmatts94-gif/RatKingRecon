package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences

/**
 * The rat the player has put on combat duty.
 *
 * Combat used to send whichever rat happened to be strongest, decided fresh
 * every time. A designation makes that a choice, and the choice costs
 * something: a rat on combat duty is barred from the Scrap Run and does not
 * count towards the Ledger Task stat gates.
 *
 * Changing it is a single preference write - no cost, no cooldown. The point is
 * a standing decision the player can revise, not a commitment they get punished
 * for making badly.
 */
object BattleRat {

    const val KEY_ID = "BATTLE_RAT_ID"

    /** No rat designated. Also the value passed to the DAO to exclude nobody. */
    const val NONE = -1L

    /**
     * Roster size below which the Ledger Task exclusion is not applied.
     *
     * With one rat, excluding it would gate every task against an empty
     * collection - the designation would read as the feature being broken
     * rather than as a trade. The lockout only means something once there is a
     * second rat to make it a choice.
     */
    const val MIN_ROSTER_FOR_EXCLUSION = 2

    fun idOf(prefs: SharedPreferences): Long = prefs.getLong(KEY_ID, NONE)

    fun isSet(prefs: SharedPreferences): Boolean = idOf(prefs) != NONE

    fun isBattleRat(prefs: SharedPreferences, ratId: Long): Boolean =
        ratId != NONE && idOf(prefs) == ratId

    fun set(prefs: SharedPreferences, ratId: Long) {
        prefs.edit().putLong(KEY_ID, ratId).apply()
    }

    fun clear(prefs: SharedPreferences) {
        prefs.edit().remove(KEY_ID).apply()
    }

    /**
     * Designating the rat already on duty stands it down again.
     *
     * Returns true when a rat is now designated. Mirrors how a Binder frame is
     * unequipped by buying it a second time.
     */
    fun toggle(prefs: SharedPreferences, ratId: Long): Boolean {
        if (isBattleRat(prefs, ratId)) {
            clear(prefs)
            return false
        }
        set(prefs, ratId)
        return true
    }

    /** Which rat fights, and whether it was the designated one. */
    data class Choice(val rat: RatEntity?, val usedBattleRat: Boolean)

    /**
     * Picks the rat for a fight about to start.
     *
     * The designation is a preference, not a guarantee: a knocked-out Battle Rat
     * steps aside for whoever can actually fight, because refusing the encounter
     * outright would punish the designation rather than reward it.
     *
     * Self-healing. A Battle Rat that has gone into the Fusion Pot leaves a
     * dangling id behind, which is cleared here rather than left to fail
     * silently on every future encounter.
     */
    fun fighterFor(
        dao: RatDao,
        prefs: SharedPreferences,
        now: Long = System.currentTimeMillis()
    ): Choice {
        val id = idOf(prefs)

        if (id != NONE) {
            val designated = dao.byId(id)
            when {
                designated == null -> clear(prefs)
                !designated.isRecovering(now) -> return Choice(designated, usedBattleRat = true)
            }
        }

        return Choice(dao.strongestAvailable(now), usedBattleRat = false)
    }

    /**
     * The rat id to leave out of the Ledger Task stat gates, or [NONE].
     *
     * [NONE] is -1, which no row can hold, so the DAO's "excluding" queries take
     * it as "exclude nobody" and the two cases stay one code path.
     */
    fun exclusionId(dao: RatDao, prefs: SharedPreferences): Long {
        val id = idOf(prefs)
        if (id == NONE) return NONE
        if (dao.count() < MIN_ROSTER_FOR_EXCLUSION) return NONE
        return id
    }
}
