package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences

/**
 * Every step-driven game rule, in one place.
 *
 * This exists because the step sensor moved into [StepTrackerService]. If both
 * the service and the Activity processed sensor events they would each read,
 * modify and write the same SharedPreferences keys, double-counting EXP or
 * losing a hatched rat. The service is now the only caller of [onSteps]; the
 * Activity reads state through the accessors below and never mutates progress.
 */
object GameEngine {

    const val KEY_LEVEL = "PLAYER_LEVEL"
    const val KEY_EXP = "CURRENT_EXP"
    const val KEY_SCRAP = "SCRAP"

    /**
     * Cumulative sensor reading at the last time steps were banked.
     *
     * Public because save import has to clear it: a baseline restored from
     * another device - or from before a reboot reset the counter - would make
     * the next reading look like thousands of steps taken at once.
     */
    const val KEY_BASELINE = "STEP_BASELINE"

    /** Last cumulative reading seen, so the UI can show a session total. */
    const val KEY_TOTAL_STEPS = "STEP_TOTAL"

    /**
     * Every step ever banked, for the Achievements milestones.
     *
     * Not the same thing as [KEY_TOTAL_STEPS], which holds the sensor's own
     * reading and restarts at zero on every reboot. This is a running total the
     * game keeps itself, so it only ever climbs.
     */
    const val KEY_LIFETIME_STEPS = "LIFETIME_STEPS"

    const val KEY_MUTAGEN = "MUTAGEN_ACTIVE"
    const val KEY_POLISH = "POLISH_ACTIVE"

    // Public so [ActiveContract] can read the same keys this resolves against,
    // rather than a second copy of the spellings drifting out of step.
    const val KEY_BOUNTY_ACTIVE = "BOUNTY_ACTIVE"
    const val KEY_BOUNTY_TARGET = "BOUNTY_TARGET"
    const val KEY_BOUNTY_END = "BOUNTY_END_TIME"
    const val KEY_BOUNTY_REWARD = "BOUNTY_REWARD"

    private const val EXP_PER_LEVEL = 50

    // --- combat encounters, entirely separate from hatching ---

    /** Per-step chance of meeting a Rustbot; ~1 encounter per 400 steps. */
    private const val ENCOUNTER_CHANCE_PER_STEP = 0.0025

    /** Minimum steps between encounters, so they cannot cluster. */
    private const val ENCOUNTER_MIN_GAP_STEPS = 150f

    private const val KEY_LAST_ENCOUNTER_STEPS = "LAST_ENCOUNTER_STEPS"

    /** What a batch of steps produced. All fields are "nothing happened" by default. */
    data class Outcome(
        val hatched: RatEntity? = null,
        val newLevel: Int = 0,
        val bountyReward: Int = 0,
        val bountyFailed: Boolean = false,
        val encounter: Encounter? = null,
        /** A boss was banked. Deliberately not a fight - see [Bosses]. */
        val bossBanked: BossSpec? = null,
        val changed: Boolean = false
    )

    fun levelOf(prefs: SharedPreferences): Int = prefs.getInt(KEY_LEVEL, 1)
    fun expOf(prefs: SharedPreferences): Int = prefs.getInt(KEY_EXP, 0)
    fun scrapOf(prefs: SharedPreferences): Int = prefs.getInt(KEY_SCRAP, 0)
    fun totalStepsOf(prefs: SharedPreferences): Float = prefs.getFloat(KEY_TOTAL_STEPS, 0f)
    fun maxExpFor(level: Int): Int = level * EXP_PER_LEVEL

    fun lifetimeStepsOf(prefs: SharedPreferences): Long = prefs.getLong(KEY_LIFETIME_STEPS, 0L)

    /**
     * The steps a save at [level] must have walked, at minimum.
     *
     * Lifetime counting was added after the game shipped, so an existing save
     * has no history to restore - the sensor reading it did keep is not a total.
     * Rather than showing a long-standing player zero, the total is seeded from
     * the only evidence there is: levelling costs 50 EXP times the level, and
     * one step is one EXP, so reaching level L took at least this many steps.
     *
     * A floor, not a reconstruction. Overflow EXP is discarded on each level, so
     * the real figure is higher, and the seed cannot know by how much.
     */
    fun seedLifetimeFor(level: Int): Long =
        EXP_PER_LEVEL.toLong() * level * (level - 1) / 2

    /**
     * Folds a cumulative step-counter reading into the save.
     *
     * [totalSteps] is TYPE_STEP_COUNTER's value, which counts from device boot
     * and resets to zero on reboot - handled below by re-baselining rather than
     * subtracting into a negative.
     */
    fun onSteps(dao: RatDao, prefs: SharedPreferences, totalSteps: Float): Outcome {
        seedLifetimeIfAbsent(prefs)

        val baseline = prefs.getFloat(KEY_BASELINE, -1f)

        // First reading ever, or the device rebooted and the counter restarted.
        if (baseline < 0f || totalSteps < baseline) {
            prefs.edit()
                .putFloat(KEY_BASELINE, totalSteps)
                .putFloat(KEY_TOTAL_STEPS, totalSteps)
                // Encounter spacing is measured against the same cumulative
                // counter, so it restarted too. Left in place it would hold a
                // reading from before the reboot - far ahead of anything the
                // sensor will report for a long while - and the gap check in
                // maybeTriggerEncounter would refuse every encounter until the
                // counter climbed all the way back to it. Dropping the key
                // restores its fresh-save default, which is "eligible now".
                .remove(KEY_LAST_ENCOUNTER_STEPS)
                .apply()
            return Outcome()
        }

        val gained = (totalSteps - baseline).toInt()
        if (gained <= 0) return Outcome()

        val editor = prefs.edit()
        editor.putFloat(KEY_BASELINE, totalSteps)
        editor.putFloat(KEY_TOTAL_STEPS, totalSteps)
        editor.putLong(KEY_LIFETIME_STEPS, lifetimeStepsOf(prefs) + gained)

        val bounty = resolveBounty(prefs, editor, totalSteps)

        var level = levelOf(prefs)
        var exp = expOf(prefs) + gained
        var hatched: RatEntity? = null
        var newLevel = 0

        if (exp >= maxExpFor(level)) {
            hatched = rollRat(prefs, editor)
            // Room assigns the instance id; keep the stored copy so callers see it.
            hatched = hatched.copy(id = dao.insert(hatched))
            level += 1
            newLevel = level
            exp = 0
        }

        editor.putInt(KEY_LEVEL, level)
        editor.putInt(KEY_EXP, exp)
        editor.apply()

        // Step milestones latch on every batch: they read a number already in
        // preferences, so it costs nothing on the sensor path. The collection
        // ones are only worth re-reading when the collection just changed, which
        // is exactly when a hatch has landed.
        Milestones.refreshSteps(prefs, lifetimeStepsOf(prefs))
        if (hatched != null) {
            Milestones.refresh(prefs, Milestones.readProgress(dao, prefs))
        }

        // Rolled after EXP is banked so a hatch and an encounter can both land
        // from one batch of steps without competing for it.
        val encounter = maybeTriggerEncounter(dao, prefs, totalSteps, gained, level)

        // Independent of the encounter above: a boss is banked, not fought, so
        // the two do not compete and neither blocks the other.
        val bossBanked = maybeBankBoss(prefs, gained, level)

        return Outcome(
            hatched = hatched,
            newLevel = newLevel,
            bossBanked = bossBanked,
            bountyReward = bounty.first,
            bountyFailed = bounty.second,
            encounter = encounter,
            changed = true
        )
    }

    /**
     * Seeds the lifetime total on the first walk after it was introduced.
     *
     * Absence is the migration signal: a save that predates the counter has no
     * key at all, while a fresh one is written a zero the moment it walks.
     */
    private fun seedLifetimeIfAbsent(prefs: SharedPreferences) {
        if (prefs.contains(KEY_LIFETIME_STEPS)) return
        prefs.edit().putLong(KEY_LIFETIME_STEPS, seedLifetimeFor(levelOf(prefs))).apply()
    }

    /**
     * Decides whether these steps banked a boss.
     *
     * Deliberately thin next to [maybeTriggerEncounter]: no fighter is chosen
     * and no fight is built, because a boss must not be resolvable from a
     * notification. All that is recorded is which boss is owed. MainActivity
     * turns that into an actual encounter the next time the app is opened.
     *
     * Skipped when one is already banked, and when the player's level sits
     * between tiers.
     */
    private fun maybeBankBoss(prefs: SharedPreferences, gained: Int, playerLevel: Int): BossSpec? {
        if (Bosses.bankedId(prefs) != null) return null

        val spec = Bosses.forLevel(playerLevel) ?: return null

        val chance = 1.0 - Math.pow(1.0 - Bosses.CHANCE_PER_STEP, gained.toDouble())
        if (Math.random() >= chance) return null

        Bosses.bank(prefs, spec)
        return spec
    }

    /**
     * Decides whether these steps ran into a Rustbot.
     *
     * Independent of hatching: its own probability, its own spacing, and it
     * consumes no EXP. Skipped when an encounter is already waiting, when the
     * last one was too recent, or when every rat is knocked out.
     */
    private fun maybeTriggerEncounter(
        dao: RatDao,
        prefs: SharedPreferences,
        totalSteps: Float,
        gained: Int,
        playerLevel: Int
    ): Encounter? {
        if (Encounter.isPending(prefs)) return null

        val lastAt = prefs.getFloat(KEY_LAST_ENCOUNTER_STEPS, -ENCOUNTER_MIN_GAP_STEPS)
        if (totalSteps - lastAt < ENCOUNTER_MIN_GAP_STEPS) return null

        // Probability that at least one of the steps in this batch triggered.
        val chance = 1.0 - Math.pow(1.0 - ENCOUNTER_CHANCE_PER_STEP, gained.toDouble())
        if (Math.random() >= chance) return null

        val encounter = raiseEncounter(dao, prefs, playerLevel) ?: return null

        prefs.edit().putFloat(KEY_LAST_ENCOUNTER_STEPS, totalSteps).apply()
        return encounter
    }

    /**
     * Builds and stores an encounter against the best rat currently available.
     *
     * Split out of [maybeTriggerEncounter] so the debug trigger in Settings can
     * raise the same fight the roll above would have: same scaling against the
     * same rat, same reward band, same pending record. A second construction
     * site would drift from this one the first time either changed.
     *
     * Deliberately does *not* touch the encounter spacing marker - that belongs
     * to the walk, and a forced encounter should not delay the next real one.
     *
     * Returns null when every rat is knocked out, the one condition that makes
     * an encounter impossible rather than merely unlikely.
     */
    fun raiseEncounter(dao: RatDao, prefs: SharedPreferences, playerLevel: Int): Encounter? {
        val fighter = dao.strongestAvailable(System.currentTimeMillis()) ?: return null
        val bot = RustbotFactory.forEncounter(playerLevel, fighter)

        val encounter = Encounter(
            ratId = fighter.id,
            botName = bot.name,
            botPower = bot.power,
            botToughness = bot.toughness,
            reward = RustbotFactory.rewardFor(playerLevel)
        )

        Encounter.save(prefs, encounter)
        return encounter
    }

    /** Rolls a rat, consuming any active consumables. */
    private fun rollRat(prefs: SharedPreferences, editor: SharedPreferences.Editor): RatEntity {
        val mutagen = prefs.getBoolean(KEY_MUTAGEN, false)
        val polish = prefs.getBoolean(KEY_POLISH, false)

        val species = Roster.all.random()
        val card = RatEntity(
            artKey = species.artKey,
            power = if (mutagen) (6..10).random() else (1..5).random(),
            toughness = if (mutagen) (6..10).random() else (1..5).random(),
            name = species.name,
            shiny = polish || (1..10).random() == 1
        )

        // Consumables are spent by the hatch they applied to.
        editor.putBoolean(KEY_MUTAGEN, false).putBoolean(KEY_POLISH, false)
        return card
    }

    /** Returns (rewardPaid, failed). Both are zero/false when no bounty resolved. */
    private fun resolveBounty(
        prefs: SharedPreferences,
        editor: SharedPreferences.Editor,
        totalSteps: Float
    ): Pair<Int, Boolean> {
        if (!prefs.getBoolean(KEY_BOUNTY_ACTIVE, false)) return 0 to false

        val now = System.currentTimeMillis()
        if (now > prefs.getLong(KEY_BOUNTY_END, 0L)) {
            editor.putBoolean(KEY_BOUNTY_ACTIVE, false)
            return 0 to true
        }

        if (totalSteps >= prefs.getFloat(KEY_BOUNTY_TARGET, Float.MAX_VALUE)) {
            val reward = prefs.getInt(KEY_BOUNTY_REWARD, 0)
            editor.putBoolean(KEY_BOUNTY_ACTIVE, false)
            editor.putInt(KEY_SCRAP, scrapOf(prefs) + reward)
            return reward to false
        }

        return 0 to false
    }
}
