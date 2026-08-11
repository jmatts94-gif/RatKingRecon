package com.example.ratkingrecon

import android.content.SharedPreferences
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A scrap-machine opponent. Beaten for salvage. */
data class Rustbot(
    val name: String,
    val power: Int,
    val toughness: Int
) {
    val maxHp: Int get() = toughness * 10
}

/**
 * Builds the Rustbot for an encounter.
 *
 * Scaled against the rat being sent rather than against player level alone.
 * Rat stats never grow with level - Power and Toughness always roll 1..5, and
 * only the Fusion Pot raises them - so level-only scaling would leave anyone
 * who does not splice facing unwinnable fights. Level instead decides how close
 * to parity the Rustbot gets: a gentle slope from 45% at level 1 to an even
 * match from level 12 on, with no step anywhere along it.
 */
object RustbotFactory {

    private const val START_RATIO = 0.45
    private const val RATIO_PER_LEVEL = 0.05
    private const val PARITY_LEVEL = 12

    /** Provisional naming - the species is "Rustbot", these are variants. */
    private val VARIANTS = listOf(
        "Rustbot Scrapper",
        "Rustbot Crawler",
        "Rustbot Sentry",
        "Rustbot Welder",
        "Rustbot Hauler",
        "Rustbot Piston"
    )

    /** How close to the rat's own stats a Rustbot gets at [playerLevel]. */
    fun rampFor(playerLevel: Int): Double =
        min(1.0, START_RATIO + RATIO_PER_LEVEL * (playerLevel - 1))

    fun forEncounter(playerLevel: Int, rat: RatEntity): Rustbot {
        val ramp = rampFor(playerLevel)
        return Rustbot(
            name = VARIANTS.random(),
            power = max(1, (rat.power * ramp).roundToInt()),
            toughness = max(1, (rat.toughness * ramp).roundToInt())
        )
    }

    /**
     * Salvage for a win.
     *
     * Sized against the existing Scrap sources: at roughly one encounter per
     * 400 steps this lands between a short bounty (8-15 for 50 steps) and a
     * medium one (25-40 for 250), and never rivals the 4-hour expedition.
     */
    fun rewardFor(playerLevel: Int): Int {
        val base = 10 + playerLevel * 2
        return max(1, (base * (85..115).random() / 100.0).roundToInt())
    }

    /** Cost to wake a knocked-out rat immediately instead of waiting. */
    fun reviveCost(playerLevel: Int): Int = 15 + playerLevel * 3
}

/**
 * An encounter that has triggered but not yet been resolved.
 *
 * Persisted the moment it triggers so the fight offered in a notification is
 * exactly the fight that gets played, even if the process dies in between, and
 * so Fight and Auto-Resolve cannot disagree about the opponent.
 */
data class Encounter(
    val ratId: Long,
    val botName: String,
    val botPower: Int,
    val botToughness: Int,
    val reward: Int
) {
    companion object {
        private const val KEY_PENDING = "ENCOUNTER_PENDING"
        private const val KEY_RAT_ID = "ENCOUNTER_RAT_ID"
        private const val KEY_BOT_NAME = "ENCOUNTER_BOT_NAME"
        private const val KEY_BOT_POWER = "ENCOUNTER_BOT_POWER"
        private const val KEY_BOT_TOUGH = "ENCOUNTER_BOT_TOUGH"
        private const val KEY_REWARD = "ENCOUNTER_REWARD"

        fun isPending(prefs: SharedPreferences): Boolean =
            prefs.getBoolean(KEY_PENDING, false)

        fun load(prefs: SharedPreferences): Encounter? {
            if (!isPending(prefs)) return null
            return Encounter(
                ratId = prefs.getLong(KEY_RAT_ID, -1L),
                botName = prefs.getString(KEY_BOT_NAME, "Rustbot") ?: "Rustbot",
                botPower = prefs.getInt(KEY_BOT_POWER, 1),
                botToughness = prefs.getInt(KEY_BOT_TOUGH, 1),
                reward = prefs.getInt(KEY_REWARD, 0)
            ).takeIf { it.ratId >= 0 }
        }

        fun save(prefs: SharedPreferences, encounter: Encounter) {
            prefs.edit()
                .putBoolean(KEY_PENDING, true)
                .putLong(KEY_RAT_ID, encounter.ratId)
                .putString(KEY_BOT_NAME, encounter.botName)
                .putInt(KEY_BOT_POWER, encounter.botPower)
                .putInt(KEY_BOT_TOUGH, encounter.botToughness)
                .putInt(KEY_REWARD, encounter.reward)
                .apply()
        }

        fun clear(prefs: SharedPreferences) {
            prefs.edit().putBoolean(KEY_PENDING, false).apply()
        }
    }

    val botMaxHp: Int get() = botToughness * 10

    /**
     * Builds the simulator for this encounter against [rat].
     *
     * [bonusPower] is a Shop Power Surge, passed in rather than read here so the
     * simulator stays a pure function of the numbers handed to it - which is
     * what keeps the manual and Auto-Resolve paths impossible to drift apart.
     */
    fun toBattle(rat: RatEntity, bonusPower: Int = 0): Battle = Battle(
        ratName = rat.name,
        ratPower = rat.power + bonusPower,
        ratMaxHp = rat.maxHp,
        botName = botName,
        botPower = botPower,
        botMaxHp = botMaxHp
    )
}
