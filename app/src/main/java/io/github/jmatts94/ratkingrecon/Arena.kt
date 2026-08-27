package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences

/**
 * The Battle Arena's entry flow: what it costs, and how its first fight gets
 * raised.
 *
 * Fight one is built by [ArenaRun.rustbotFor], the same fight-indexed curve
 * every later fight uses - see there for why it ignores player level
 * entirely rather than reusing [RustbotFactory]'s own level ramp. Only the
 * choice of rat differs from an ordinary encounter: the player's own pick,
 * not [BattleRat.fighterFor]'s.
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

        val bot = ArenaRun.rustbotFor(1, fighter)
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
