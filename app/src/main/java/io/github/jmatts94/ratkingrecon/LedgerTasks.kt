package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences

/**
 * One rolled Ledger Task, ready to be written into a slot.
 *
 * [reward] is fixed the moment the task is rolled, so what the board advertises
 * is exactly what a claim pays - the same guarantee [BountyOffer] makes for the
 * Contract Board.
 */
data class LedgerTaskOffer(
    val title: String,
    val requirement: Int,
    val reward: Int
)

/**
 * A slot on the Ledger board: how long it runs for, and the bands it rolls in.
 *
 * [id] reaches the save - it prefixes every key a slot owns - so it must never
 * change once shipped. M1/M2/M3 are the original spellings and are deliberately
 * untouched by the Mission-to-Ledger-Task rename, because they hold tasks
 * players already have running.
 */
data class LedgerTaskTier(
    val id: String,
    val durationMs: Long,
    val requirements: IntRange,
    val rewards: IntRange,
    /**
     * Chance of a relic on claim, as a proportion.
     *
     * Belongs to the tier because it used to be one flat 25% for the whole
     * board, which made the two-hour task about twelve times the best
     * relic-per-hour rate in the game and gave nobody a reason to run the long
     * ones. See [Relics.rollFor].
     */
    val relicChance: Double
) {
    /** Whole hours, which is the only unit the board displays. */
    val hours: Int get() = (durationMs / (1000 * 60 * 60)).toInt()

    /**
     * Rolls a fresh job for this slot.
     *
     * Pure: it picks but stores nothing, so the caller owns the write and this
     * stays testable off-device - the same split [Masterwork.roll] uses.
     */
    fun roll(): LedgerTaskOffer = LedgerTaskOffer(
        title = LedgerTasks.rollTitle(),
        requirement = requirements.random(),
        reward = rewards.random()
    )
}

/**
 * The Ledger Task board: its three slots, and the rules for refreshing them.
 *
 * Lifted out of [TasksActivity] so the reroll rules can be tested without
 * a device. The Activity had two things worth protecting and no way to assert
 * either: that the daily pass never rerolls a task with an unclaimed reward
 * sitting on it, and that claiming one does reroll that slot rather than leaving
 * the finished job on the board until the next calendar day.
 */
object LedgerTasks {

    private val prefixes = listOf(
        "The Rust", "The Toxic", "The Abandoned", "The Glowing", "The Flooded", "The Iron"
    )

    private val suffixes = listOf(
        "Pipes", "Factory", "Sewer", "Crater", "Warehouse", "Subway", "Scrapyard"
    )

    fun rollTitle(): String = "${prefixes.random()} ${suffixes.random()}"

    val M1 = LedgerTaskTier(
        id = "M1",
        durationMs = 2 * 60 * 60 * 1000L,
        requirements = 2..4,
        rewards = 15..30,
        relicChance = 0.10
    )

    val M2 = LedgerTaskTier(
        id = "M2",
        durationMs = 8 * 60 * 60 * 1000L,
        requirements = 4..7,
        rewards = 40..70,
        // The rate the whole board used to run at, kept as the middle of the
        // new spread so the change reads as M1 down and M3 up.
        relicChance = 0.25
    )

    /**
     * The shiny task. Its requirement is not a stat threshold - the screen feeds
     * it 1 when a shiny exists and 0 when none does - so the band is a single
     * value rather than a range.
     */
    val M3 = LedgerTaskTier(
        id = "M3",
        durationMs = 24 * 60 * 60 * 1000L,
        requirements = 1..1,
        rewards = 100..200,
        relicChance = 0.50
    )

    val all: List<LedgerTaskTier> = listOf(M1, M2, M3)

    // ---- save keys -----------------------------------------------------------

    const val KEY_LAST_ROLL_DAY = "LAST_ROLL_DAY"

    fun titleKey(id: String): String = "${id}_TITLE"
    fun requirementKey(id: String): String = "${id}_REQ"
    fun rewardKey(id: String): String = "${id}_REWARD"
    fun activeKey(id: String): String = "${id}_ACTIVE"
    fun endTimeKey(id: String): String = "${id}_END_TIME"

    /** True while the slot is out on a job, claimed or not. */
    fun isRunning(prefs: SharedPreferences, tier: LedgerTaskTier): Boolean =
        prefs.getBoolean(activeKey(tier.id), false)

    /** Reads back whatever is currently on the board for [tier]. */
    fun stored(prefs: SharedPreferences, tier: LedgerTaskTier): LedgerTaskOffer =
        LedgerTaskOffer(
            title = prefs.getString(titleKey(tier.id), "") ?: "",
            requirement = prefs.getInt(requirementKey(tier.id), 1),
            reward = prefs.getInt(rewardKey(tier.id), 10)
        )

    /** Stages [offer] into [editor]; the caller owns the commit. */
    fun store(
        editor: SharedPreferences.Editor,
        tier: LedgerTaskTier,
        offer: LedgerTaskOffer
    ) {
        editor.putString(titleKey(tier.id), offer.title)
        editor.putInt(requirementKey(tier.id), offer.requirement)
        editor.putInt(rewardKey(tier.id), offer.reward)
    }

    /**
     * Rerolls one slot.
     *
     * Called on a claim, so the slot offers a new job the moment the old one is
     * banked. Safe against [rerollForNewDay]: that only touches slots which are
     * not running, and a claimed one has just been marked inactive, so at worst
     * it is rerolled again by the next daily pass.
     */
    fun reroll(editor: SharedPreferences.Editor, tier: LedgerTaskTier) {
        store(editor, tier, tier.roll())
    }

    /**
     * The day the board last refreshed on.
     *
     * Whole days since the epoch, which makes this a UTC boundary rather than a
     * local one - unlike [DailySteps], which rolls at the player's own midnight.
     * Kept as it was rather than quietly moved: changing it would shift when
     * every existing player's board refreshes.
     */
    fun dayIndexOf(now: Long): Int = (now / (1000 * 60 * 60 * 24)).toInt()

    /**
     * The daily pass: gives every idle slot a new job, once per day.
     *
     * Running slots are skipped, so a reward the player has finished earning but
     * not yet claimed cannot be rerolled out from under them.
     *
     * Returns whether the day had turned over, which is the only thing a caller
     * could act on. The day marker is written even when all three slots were
     * running and nothing was rolled - otherwise a board left busy over midnight
     * would reroll on the first idle slot it saw, at an arbitrary later time.
     */
    fun rerollForNewDay(
        prefs: SharedPreferences,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val today = dayIndexOf(now)
        if (today == prefs.getInt(KEY_LAST_ROLL_DAY, 0)) return false

        val editor = prefs.edit()
        all.filterNot { isRunning(prefs, it) }.forEach { reroll(editor, it) }
        editor.putInt(KEY_LAST_ROLL_DAY, today).apply()
        return true
    }
}
