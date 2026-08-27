package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences

/**
 * The Battle Arena's entry flow: what it costs, and how its first fight gets
 * raised.
 *
 * Deliberately small. This only clears the way into a run - the run itself
 * (fight 2 onward, HP carried in with no full heal, Scrap-revive disabled,
 * the Arena's own escalating difficulty and reward curve) is separate work
 * this does not attempt, per the investigation it shipped from. Fight one
 * plays as an ordinary Rustbot encounter in every way except which rat meets
 * it: the player's own choice, not [BattleRat.fighterFor]'s pick.
 */
object Arena {

    /** Paid once, on entry, regardless of how far the run goes. */
    const val ENTRY_COST = 150

    /**
     * Raises the Arena's first fight against [ratId].
     *
     * Null when a fight is already pending - this never overwrites one, the
     * same caution [Bosses.startBanked] takes - or when [ratId] no longer
     * names a real rat (spliced away between picking it and confirming, say).
     */
    fun raiseFirstFight(dao: RatDao, prefs: SharedPreferences, playerLevel: Int, ratId: Long): Encounter? {
        if (Encounter.isPending(prefs)) return null
        val fighter = dao.byId(ratId) ?: return null

        val bot = RustbotFactory.forEncounter(playerLevel, fighter)
        val encounter = Encounter(
            ratId = fighter.id,
            botName = bot.name,
            botPower = bot.power,
            botMaxHp = bot.maxHp,
            reward = RustbotFactory.rewardFor(playerLevel)
        )

        Encounter.save(prefs, encounter)
        return encounter
    }
}
