package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import androidx.annotation.StringRes

/** The things a day can ask for. */
enum class QuestType {
    /** Walk a rolled number of steps. The only one with partial progress. */
    STEPS,

    /** Win one Rustbot encounter. Bosses count, but nothing requires one. */
    WIN_FIGHT,

    /** Add one rat to the Ledger, by any route. */
    HATCH,

    /** Splice two rats into a mutant at the Fusion Pot. */
    SPLICE
}

/** What finishing a quest paid out, so the caller can say so. */
sealed interface QuestReward {
    data class Scrap(val amount: Int) : QuestReward
    data class Relic(val relic: io.github.jmatts94.ratkingrecon.Relic) : QuestReward
}

/**
 * One quest a day, rolled at the local midnight [DailySteps] already uses.
 *
 * Progress is deliberately not one counter. Steps are read straight from
 * [DailySteps], which is already counting them and already resets on the same
 * boundary - keeping a second tally of the same walk would be one more thing to
 * drift. Wins and hatches have nothing counting them, so those get a counter of
 * their own, incremented where they happen.
 */
object DailyQuest {

    /** Local midnight of the day the current quest belongs to. */
    const val KEY_DAY = "QUEST_DAY"
    const val KEY_TYPE = "QUEST_TYPE"
    const val KEY_TARGET = "QUEST_TARGET"

    /** Wins or hatches so far today. Unused by a steps quest. */
    const val KEY_EVENTS = "QUEST_EVENTS"

    const val KEY_DONE = "QUEST_DONE"

    /** Steps asked for, when the day rolls a walking quest. */
    val STEP_TARGET = 1_500..2_500

    /** Scrap paid when the day pays Scrap. */
    val SCRAP_REWARD = 20..40

    /**
     * Relics a streak has earned the right to be given.
     *
     * Ordered by what the Relic Trader pays for them, so a longer streak opens
     * up the better trades: the Rusted Gear buys Scrap, the middle two buy a
     * frame or a combat item, and the Heavy Wrench is the Masterwork discount.
     */
    private const val STREAK_MID_TIER = 7
    private const val STREAK_TOP_TIER = 14

    // ---- the day's quest -----------------------------------------------------

    /**
     * Rolls a new quest if the day has turned over, settling the streak first.
     *
     * Everything about a rollover happens in one commit: what the missed days
     * did to the streak, the new quest, and the cleared progress. A half-applied
     * rollover would either hand out a second quest for the same day or lose a
     * streak that was never actually broken.
     *
     * Safe to call as often as it is convenient - it does nothing at all on a
     * day it has already rolled.
     */
    fun ensureToday(prefs: SharedPreferences, now: Long = System.currentTimeMillis()) {
        val today = DailySteps.localMidnight(now)
        val storedDay = prefs.getLong(KEY_DAY, 0L)
        if (storedDay == today) return

        val editor = prefs.edit()

        // A first run has no previous day to judge, so the streak is left alone
        // rather than being handed a miss for days before the game existed.
        if (storedDay != 0L) {
            Streak.settleRollover(
                prefs = prefs,
                editor = editor,
                lastSeenDay = storedDay,
                today = today,
                completedLastSeenDay = prefs.getBoolean(KEY_DONE, false)
            )
        }

        val type = QuestType.entries.random()
        editor.putLong(KEY_DAY, today)
            .putString(KEY_TYPE, type.name)
            .putInt(KEY_TARGET, if (type == QuestType.STEPS) STEP_TARGET.random() else 1)
            .putInt(KEY_EVENTS, 0)
            .putBoolean(KEY_DONE, false)
            .apply()
    }

    fun type(prefs: SharedPreferences): QuestType =
        prefs.getString(KEY_TYPE, null)
            ?.let { name -> QuestType.entries.firstOrNull { it.name == name } }
            ?: QuestType.STEPS

    fun target(prefs: SharedPreferences): Int = prefs.getInt(KEY_TARGET, STEP_TARGET.first)

    fun isComplete(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_DONE, false)

    /**
     * How far along today is.
     *
     * Capped at the target so a finished walking quest reads "2000 / 2000"
     * rather than overshooting into a number nobody asked for.
     *
     * [now] is threaded through rather than left to the wall clock, because a
     * walking quest reads its progress out of [DailySteps] and that has a
     * rollover of its own - the two have to be asked about the same day or a
     * quest can see zero steps on a day it was handed a target for.
     */
    fun progress(prefs: SharedPreferences, now: Long = System.currentTimeMillis()): Int {
        val target = target(prefs)
        val raw = when (type(prefs)) {
            QuestType.STEPS -> DailySteps.today(prefs, now)
            QuestType.WIN_FIGHT, QuestType.HATCH, QuestType.SPLICE -> prefs.getInt(KEY_EVENTS, 0)
        }
        return raw.coerceIn(0, target)
    }

    /** Progress as a percentage, for the lantern. */
    fun percent(prefs: SharedPreferences, now: Long = System.currentTimeMillis()): Int {
        val target = target(prefs)
        if (target <= 0) return 0
        return (progress(prefs, now) * 100 / target).coerceIn(0, 100)
    }

    // ---- progress ------------------------------------------------------------

    /**
     * Records a win, a hatch, or simply that some steps were banked, and settles
     * the quest if that finished it.
     *
     * Returns what was paid out, or null when nothing completed - which is the
     * ordinary case, since this runs on every batch of steps.
     *
     * [forType] names what happened. A win does nothing on a day asking for
     * steps, which is why this takes the event rather than a bare "recheck".
     */
    fun record(
        prefs: SharedPreferences,
        forType: QuestType,
        now: Long = System.currentTimeMillis()
    ): QuestReward? {
        ensureToday(prefs, now)
        if (isComplete(prefs)) return null
        if (type(prefs) != forType) return null

        if (forType != QuestType.STEPS) {
            prefs.edit().putInt(KEY_EVENTS, prefs.getInt(KEY_EVENTS, 0) + 1).apply()
        }

        if (progress(prefs, now) < target(prefs)) return null

        return complete(prefs, now)
    }

    /**
     * Marks today done, banks the streak and pays out.
     *
     * The streak is incremented in the same commit as the completion flag, so a
     * process killed between the two cannot leave a day that counted as finished
     * without counting towards the streak, or the reverse.
     */
    private fun complete(prefs: SharedPreferences, now: Long): QuestReward {
        val editor = prefs.edit()
        editor.putBoolean(KEY_DONE, true)
        Streak.recordCompletion(prefs, editor, DailySteps.localMidnight(now))

        // Read after the streak is staged but before it is committed: the day
        // just banked is the one the reward tier should be measured against.
        val streakAfter = Streak.count(prefs) + 1
        val reward = rollReward(prefs, editor, streakAfter)

        editor.apply()
        PermanentBuffs.checkSteadfastMomentum(prefs, streakAfter)
        return reward
    }

    /**
     * Scrap or a relic, an even split.
     *
     * Even because there is no reason for it not to be: a relic is worth more
     * than 20-40 Scrap at the Trader, but only three of a kind buy anything, so
     * a relic is a step towards a reward where Scrap is the reward. Neither is
     * the obvious prize.
     */
    private fun rollReward(
        prefs: SharedPreferences,
        editor: SharedPreferences.Editor,
        streak: Int
    ): QuestReward {
        if ((1..2).random() == 1) {
            val amount = SCRAP_REWARD.random()
            editor.putInt(GameEngine.KEY_SCRAP, GameEngine.scrapOf(prefs) + amount)
            return QuestReward.Scrap(amount)
        }

        val relic = eligibleRelics(streak).random()
        Relics.grant(prefs, editor, relic)
        return QuestReward.Relic(relic)
    }

    /**
     * Which relics a streak of [streak] days has earned.
     *
     * Indexed off [Relics.ALL] rather than named, so a relic added to that list
     * joins the top tier by default rather than silently never being given.
     */
    fun eligibleRelics(streak: Int): List<Relic> = when {
        streak >= STREAK_TOP_TIER -> Relics.ALL
        streak >= STREAK_MID_TIER -> Relics.ALL.take(3)
        else -> Relics.ALL.take(1)
    }

    // ---- description ---------------------------------------------------------

    @StringRes
    fun titleRes(type: QuestType): Int = when (type) {
        QuestType.STEPS -> R.string.quest_steps_title
        QuestType.WIN_FIGHT -> R.string.quest_fight_title
        QuestType.HATCH -> R.string.quest_hatch_title
        QuestType.SPLICE -> R.string.quest_splice_title
    }
}
