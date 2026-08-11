package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The Contract Board offer currently running, as the save holds it.
 *
 * The board used to record only what it needed to *resolve* a contract - the
 * absolute step count to beat, the deadline and the payout - which is enough to
 * pay out but not enough to describe. Showing a player what they accepted needs
 * two more things the save never kept: what it was called, and how far they had
 * walked when they took it.
 *
 * [requiredSteps] is that second thing. It is zero for a contract accepted
 * before it was recorded, which is why [knowsRequirement] exists rather than
 * this quietly reporting a distance of zero.
 *
 * All the arithmetic is here, free of Android, so it can be tested without a
 * device. [GameEngine] still owns resolving; this only describes.
 */
data class ActiveContract(
    val name: String,
    val requiredSteps: Float,
    val targetSteps: Float,
    val endsAt: Long,
    val reward: Int
) {

    /** False for a contract accepted before the requirement was recorded. */
    val knowsRequirement: Boolean get() = requiredSteps > 0f

    /** Cumulative reading at the moment the contract was accepted. */
    val startedAtSteps: Float get() = targetSteps - requiredSteps

    /**
     * Steps walked since accepting, clamped to the requirement.
     *
     * Clamped at the top so a finished-but-unresolved contract reads
     * "500 / 500" rather than overshooting, and at the bottom because a reboot
     * re-baselines the sensor and can briefly put the current reading behind
     * where the contract started.
     */
    fun stepsWalked(currentTotalSteps: Float): Int {
        if (!knowsRequirement) return 0
        val walked = currentTotalSteps - startedAtSteps
        return walked.coerceIn(0f, requiredSteps).roundToInt()
    }

    /** Steps still owed. Meaningful even without a recorded requirement. */
    fun stepsRemaining(currentTotalSteps: Float): Int =
        max(0f, targetSteps - currentTotalSteps).roundToInt()

    /** Progress towards the goal, 0..100, for the bar. */
    fun percentComplete(currentTotalSteps: Float): Int {
        if (!knowsRequirement) return 0
        return ((stepsWalked(currentTotalSteps) / requiredSteps) * 100)
            .toInt().coerceIn(0, 100)
    }

    fun millisRemaining(now: Long = System.currentTimeMillis()): Long = max(0L, endsAt - now)

    /**
     * Whole minutes left, rounded up.
     *
     * Up rather than down so a contract with forty seconds left reads "1 min"
     * instead of "0 min" while it is still winnable.
     */
    fun minutesRemaining(now: Long = System.currentTimeMillis()): Int {
        val ms = millisRemaining(now)
        return if (ms == 0L) 0 else ((ms + 59_999L) / 60_000L).toInt()
    }

    fun hasExpired(now: Long = System.currentTimeMillis()): Boolean = millisRemaining(now) == 0L

    companion object {

        /** Recorded alongside the resolving keys; see the class comment. */
        const val KEY_NAME = "BOUNTY_NAME"
        const val KEY_REQUIRED = "BOUNTY_REQUIRED"

        fun isActive(prefs: SharedPreferences): Boolean =
            prefs.getBoolean(GameEngine.KEY_BOUNTY_ACTIVE, false)

        /** The running contract, or null when the board is free. */
        fun load(prefs: SharedPreferences): ActiveContract? {
            if (!isActive(prefs)) return null
            return ActiveContract(
                name = prefs.getString(KEY_NAME, null).orEmpty(),
                requiredSteps = prefs.getFloat(KEY_REQUIRED, 0f),
                targetSteps = prefs.getFloat(GameEngine.KEY_BOUNTY_TARGET, 0f),
                endsAt = prefs.getLong(GameEngine.KEY_BOUNTY_END, 0L),
                reward = prefs.getInt(GameEngine.KEY_BOUNTY_REWARD, 0)
            )
        }

        /**
         * Writes an accepted offer.
         *
         * [currentTotalSteps] is the cumulative sensor reading, which is what
         * the target is measured against - not a step count starting at zero.
         */
        fun accept(
            prefs: SharedPreferences,
            offer: BountyOffer,
            currentTotalSteps: Float,
            now: Long = System.currentTimeMillis()
        ) {
            prefs.edit()
                .putBoolean(GameEngine.KEY_BOUNTY_ACTIVE, true)
                .putString(KEY_NAME, offer.name)
                .putFloat(KEY_REQUIRED, offer.steps)
                .putFloat(GameEngine.KEY_BOUNTY_TARGET, currentTotalSteps + offer.steps)
                .putLong(GameEngine.KEY_BOUNTY_END, now + offer.minutes * 60L * 1000L)
                .putInt(GameEngine.KEY_BOUNTY_REWARD, offer.reward)
                .apply()
        }
    }
}
