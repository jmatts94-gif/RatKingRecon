package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A boss-tier Rustbot.
 *
 * [id] reaches the save, in the banked marker and in the defeated flag behind
 * each badge, so it must never change once shipped. The display name is a
 * resource, which may.
 *
 * Both multipliers are applied on top of the ordinary parity ramp rather than
 * instead of it, so a boss is scaled against the rat being sent exactly as a
 * standard Rustbot is. That is not cosmetic: rat Power and Toughness never grow
 * with player level - only the Fusion Pot raises them - so a boss scaled to
 * level 50 alone would be unbeatable for anyone who does not splice.
 *
 * Both multipliers are small, and that is the whole tuning. Combat has no
 * randomness anywhere - fixed damage, a deterministic auto-resolver, derived
 * boss stats - so a matchup is decided before the first round and every fight is
 * either always won or always lost. There is no difficulty curve to ride, only a
 * threshold to place, and bot Toughness is an integer worth ten HP a point, so
 * against a starting rat the whole range from 1.0 to 2.0 offers just four
 * distinct fights.
 *
 * An earlier pass used 1.6 to 2.5 here. Simulated across every rat the game can
 * produce, that made all five bosses unwinnable without buying an item - not
 * hard, impossible. These values were picked from the same simulation to put the
 * first boss inside reach unaided, the middle three inside reach of a 75-Scrap
 * Power Surge, and the last two behind the Golden Wrench.
 *
 * They came down by a fifth when every Rustbot gained a Special and the ramp was
 * allowed past parity. Bosses are built on top of the ordinary bot, so they
 * collected both buffs for free - roughly a sixth more damage from the Special
 * and a tenth more stats from the ramp - and the Rustbringer stopped being
 * beatable even with a Wrench, which is the one thing these numbers exist to
 * guarantee. A fifth is what the simulation says restores that without letting a
 * Power Surge alone carry the last boss.
 */
data class BossSpec(
    val id: String,
    @param:StringRes val nameRes: Int,
    @param:DrawableRes val badgeRes: Int,
    val minLevel: Int,
    val maxLevel: Int,
    val powerMult: Double,
    val hpMult: Double,
    val rewardMult: Int
) {
    fun coversLevel(level: Int): Boolean = level in minLevel..maxLevel
}

/**
 * The boss roster, and the save state behind it.
 *
 * Bosses do not use the ordinary encounter path. A standard Rustbot is raised,
 * announced and can be settled from the notification while the phone stays in a
 * pocket; a boss is *banked* instead - the walk only records that one is owed,
 * and the fight itself is built when the player next opens the app. That is
 * what stops a boss being auto-resolved unseen.
 */
object Bosses {

    /**
     * Per-step chance of banking a boss: about one per 2500 steps, against one
     * per 400 for a standard Rustbot.
     */
    const val CHANCE_PER_STEP = 0.0004

    /** Share of the reward paid for beating a boss that is already on the wall. */
    const val REPEAT_REWARD_SHARE = 0.5

    private const val KEY_BANKED = "BOSS_BANKED_ID"
    private const val KEY_DEFEATED_PREFIX = "BOSS_DEFEATED_"

    val all: List<BossSpec> = listOf(
        BossSpec(
            id = "junk_golem",
            nameRes = R.string.boss_junk_golem,
            badgeRes = R.drawable.ic_hexagon,
            minLevel = 10, maxLevel = 15,
            powerMult = 1.12, hpMult = 1.08, rewardMult = 3
        ),
        BossSpec(
            id = "old_ironclaw",
            nameRes = R.string.boss_old_ironclaw,
            badgeRes = R.drawable.ic_toughness,
            minLevel = 20, maxLevel = 25,
            powerMult = 1.16, hpMult = 1.16, rewardMult = 4
        ),
        BossSpec(
            id = "boiler_baron",
            nameRes = R.string.boss_boiler_baron,
            badgeRes = R.drawable.ic_flask,
            minLevel = 30, maxLevel = 35,
            powerMult = 1.20, hpMult = 1.24, rewardMult = 5
        ),
        BossSpec(
            id = "circuit_reaper",
            nameRes = R.string.boss_circuit_reaper,
            badgeRes = R.drawable.ic_power,
            minLevel = 40, maxLevel = 45,
            powerMult = 1.24, hpMult = 1.32, rewardMult = 6
        ),
        BossSpec(
            id = "rustbringer",
            nameRes = R.string.boss_rustbringer,
            badgeRes = R.drawable.ic_star,
            // Open-ended: the last boss stays available for the rest of the game.
            minLevel = 50, maxLevel = Int.MAX_VALUE,
            powerMult = 1.32, hpMult = 1.40, rewardMult = 8
        )
    )

    fun byId(id: String?): BossSpec? = id?.let { key -> all.firstOrNull { it.id == key } }

    /** The boss whose range covers [level], or null between tiers. */
    fun forLevel(level: Int): BossSpec? = all.firstOrNull { it.coversLevel(level) }

    // ---- banked state --------------------------------------------------------

    fun bankedId(prefs: SharedPreferences): String? = prefs.getString(KEY_BANKED, null)

    fun banked(prefs: SharedPreferences): BossSpec? = byId(bankedId(prefs))

    fun bank(prefs: SharedPreferences, spec: BossSpec) {
        prefs.edit().putString(KEY_BANKED, spec.id).apply()
    }

    fun clearBanked(prefs: SharedPreferences) {
        prefs.edit().remove(KEY_BANKED).apply()
    }

    // ---- badges --------------------------------------------------------------

    fun isDefeated(prefs: SharedPreferences, id: String): Boolean =
        prefs.getBoolean(KEY_DEFEATED_PREFIX + id, false)

    /** Returns true when this was the first win, which is what earns the badge. */
    fun markDefeated(prefs: SharedPreferences, id: String): Boolean {
        if (isDefeated(prefs, id)) return false
        prefs.edit().putBoolean(KEY_DEFEATED_PREFIX + id, true).apply()
        return true
    }

    fun defeatedCount(prefs: SharedPreferences): Int = all.count { isDefeated(prefs, it.id) }

    // ---- building the fight --------------------------------------------------

    /**
     * The Rustbot for [spec], scaled against [rat] the way a standard one is.
     *
     * Rounded up to at least the standard bot's stats, so a boss can never come
     * out weaker than the ordinary Rustbot the player would have met instead.
     */
    fun rustbotFor(spec: BossSpec, playerLevel: Int, rat: RatEntity, name: String): Rustbot {
        val ramp = RustbotFactory.rampFor(playerLevel)
        val standardPower = max(1, (rat.power * ramp).roundToInt())
        val standardTough = max(1, (rat.toughness * ramp).roundToInt())

        return Rustbot(
            name = name,
            power = max(standardPower, (rat.power * ramp * spec.powerMult).roundToInt()),
            toughness = max(standardTough, (rat.toughness * ramp * spec.hpMult).roundToInt())
        )
    }

    /**
     * Turns a banked boss into a real, pending encounter.
     *
     * Called when the app comes to the front, which is the whole point of
     * banking: the fight is built here, in front of the player, rather than
     * while the phone is in a pocket where a notification could settle it
     * unseen.
     *
     * Returns null - leaving the boss banked for next time - when there is an
     * ordinary Rustbot still waiting, so the two never overwrite each other, or
     * when every rat is knocked out and there is nobody to send.
     *
     * [nameOf] resolves the display name, so this stays free of Context.
     */
    fun startBanked(
        dao: RatDao,
        prefs: SharedPreferences,
        nameOf: (BossSpec) -> String
    ): BossSpec? {
        val spec = banked(prefs) ?: return null
        if (Encounter.isPending(prefs)) return null

        // Same fighter rule as an ordinary encounter: the Battle Rat meets the
        // boss if it can, and a knocked-out one steps aside.
        val fighter = BattleRat.fighterFor(dao, prefs).rat ?: return null
        val level = GameEngine.levelOf(prefs)
        val bot = rustbotFor(spec, level, fighter, nameOf(spec))

        Encounter.save(
            prefs,
            Encounter(
                ratId = fighter.id,
                botName = bot.name,
                botPower = bot.power,
                botToughness = bot.toughness,
                reward = rewardFor(spec, level, isDefeated(prefs, spec.id)),
                bossId = spec.id
            )
        )
        clearBanked(prefs)
        return spec
    }

    /**
     * Salvage for beating [spec].
     *
     * A repeat win pays [REPEAT_REWARD_SHARE] of the first, so a beaten boss is
     * still worth meeting without becoming the best Scrap source in the game.
     */
    fun rewardFor(spec: BossSpec, playerLevel: Int, alreadyDefeated: Boolean): Int {
        val full = RustbotFactory.rewardFor(playerLevel) * spec.rewardMult
        return if (alreadyDefeated) max(1, (full * REPEAT_REWARD_SHARE).roundToInt()) else full
    }
}
