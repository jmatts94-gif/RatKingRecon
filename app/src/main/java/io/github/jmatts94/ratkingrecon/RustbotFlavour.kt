package io.github.jmatts94.ratkingrecon

import androidx.annotation.StringRes

/**
 * The line that opens a fight, chosen for the thing you are fighting.
 *
 * Every Rustbot used to arrive identically - the log began at "Round 1" and the
 * only thing separating a Scrapper from the Rustbringer was a number. A short
 * pool per opponent is enough to stop that, without writing enough prose that
 * the second fight of the day starts reading like the first.
 *
 * Every line takes the opponent's name, so the standard pool works for all six
 * Rustbot variants and a boss line names the boss.
 */
object RustbotFlavour {

    /**
     * Any ordinary Rustbot. Written to fit whichever variant turned up, since
     * they are one enemy with six labels rather than six enemies.
     */
    private val STANDARD = intArrayOf(
        R.string.flavour_rustbot_1,
        R.string.flavour_rustbot_2,
        R.string.flavour_rustbot_3
    )

    private val BY_BOSS: Map<String, IntArray> = mapOf(
        "junk_golem" to intArrayOf(
            R.string.flavour_junk_golem_1,
            R.string.flavour_junk_golem_2,
            R.string.flavour_junk_golem_3
        ),
        "old_ironclaw" to intArrayOf(
            R.string.flavour_old_ironclaw_1,
            R.string.flavour_old_ironclaw_2,
            R.string.flavour_old_ironclaw_3
        ),
        "boiler_baron" to intArrayOf(
            R.string.flavour_boiler_baron_1,
            R.string.flavour_boiler_baron_2,
            R.string.flavour_boiler_baron_3
        ),
        "circuit_reaper" to intArrayOf(
            R.string.flavour_circuit_reaper_1,
            R.string.flavour_circuit_reaper_2,
            R.string.flavour_circuit_reaper_3
        ),
        "rustbringer" to intArrayOf(
            R.string.flavour_rustbringer_1,
            R.string.flavour_rustbringer_2,
            R.string.flavour_rustbringer_3
        )
    )

    /**
     * The opening line for [encounter], as a format string taking the
     * opponent's name.
     *
     * Derived from the encounter rather than rolled, so it is the same line
     * every time this particular fight is drawn - reopening the screen, or
     * turning the phone, must not rewrite what already happened. Two different
     * encounters against the same Rustbot still differ, because the rat and the
     * salvage on offer are part of what picks it.
     *
     * An unrecognised boss id falls back to the standard pool rather than
     * failing: a save naming a boss this build does not have is a strange thing
     * to meet, but not a reason to lose the fight.
     */
    @StringRes
    fun openingFor(encounter: Encounter): Int {
        val pool = encounter.bossId?.let { BY_BOSS[it] } ?: STANDARD

        val seed = encounter.ratId.toInt() * 31 +
            encounter.botName.hashCode() +
            encounter.reward

        return pool[Math.floorMod(seed, pool.size)]
    }
}
