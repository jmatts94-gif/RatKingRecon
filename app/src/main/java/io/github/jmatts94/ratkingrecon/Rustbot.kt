package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A scrap-machine opponent. Beaten for salvage.
 *
 * Carries its HP rather than a Toughness to derive it from. Toughness was an
 * integer worth ten HP a point, which made difficulty a staircase: scaling a
 * rat's stat across a whole number moved the Rustbot's health by a fifth in one
 * step. HP is now scaled directly, so the same dial moves it by a point.
 */
data class Rustbot(
    val name: String,
    val power: Int,
    val maxHp: Int
)

/**
 * Builds the Rustbot for an encounter.
 *
 * Scaled against the rat being sent rather than against player level alone.
 * Rat stats never grow with level - Power and Toughness always roll 1..5, and
 * only the Fusion Pot raises them - so level-only scaling would leave anyone
 * who does not splice facing unwinnable fights. Level decides how close to the
 * rat the Rustbot gets: a slope from 75% at level 1, through an even match
 * around level 8, to [MAX_RATIO] at level 11.
 *
 * Past parity, which is the point. The ramp used to stop dead there, and a
 * mirror match is not an even fight: the rat swings first, the Rustbot does not
 * retaliate on the round it dies, and only the rat has a Special and a block. A
 * sweep of every stat pairing at every level found the player winning 4000 out
 * of 4000. Letting the ramp climb to [MAX_RATIO] is half the answer - the other
 * half is the Rustbot Special in [Battle].
 */
object RustbotFactory {

    /**
     * Where the ramp begins, and how fast it climbs.
     *
     * It used to begin at 0.45 and add 0.05 a level, which made the first dozen
     * levels a formality: measured across the stat range, a level 1 win left 82%
     * of the rat's health and a level 5 win left 64%. The endgame was a real
     * contest and almost nobody had walked far enough to see it - reaching the
     * top of the old ramp took some 4500 steps of cumulative levelling.
     *
     * Starting at 0.75 costs a new player about half their health for a first
     * win instead of a fifth. The slope is shallower to compensate, so the climb
     * from there is gentler than it was and still tops out sooner: parity lands
     * around level 8 and the cap at level 11.
     */
    private const val START_RATIO = 0.75
    private const val RATIO_PER_LEVEL = 0.035

    /**
     * How far past the rat a Rustbot can be scaled, at the top of the ramp.
     *
     * Reached at level 11 and flat from there, and deliberately unchanged by the
     * ramp being made steeper at the bottom: the endgame was already a contest,
     * and only the walk up to it was not. This is a real dial now that HP is
     * scaled a point at a time rather than in tens - swept across all 144 stat
     * pairings the player wins 100% of encounters at 1.00, 73% at 1.05, 53%
     * here, and 27% at 1.20, falling smoothly rather than in cliffs.
     *
     * Roughly even odds is the point. An encounter is a coin toss the player
     * can load: a Power Surge wins every pairing at every ratio tested, so a
     * fight that matters is always winnable for 75 Scrap, and only a fight the
     * player did not care about is left to chance.
     */
    private const val MAX_RATIO = 1.10

    /** Provisional naming - the species is "Rustbot", these are variants. */
    private val VARIANTS = listOf(
        "Rustbot Scrapper",
        "Rustbot Crawler",
        "Rustbot Sentry",
        "Rustbot Welder",
        "Rustbot Hauler",
        "Rustbot Piston"
    )

    /** How close to - or past - the rat's own stats a Rustbot gets at [playerLevel]. */
    fun rampFor(playerLevel: Int): Double =
        min(MAX_RATIO, START_RATIO + RATIO_PER_LEVEL * (playerLevel - 1))

    /**
     * The Rustbot for this encounter.
     *
     * Power and HP are scaled differently, on purpose.
     *
     * Power stops at parity however high the ramp goes. It is a single-digit
     * integer, so it cannot be scaled smoothly - one point either side is a
     * fifth of a rat's whole output - and letting the ramp push it across a
     * whole number is what used to make a 5/5 rat meet a harder Rustbot than a
     * 4/4 did. Capping it there means a Rustbot never out-hits the rat it was
     * built for, whatever size that rat is.
     *
     * HP carries the ramp instead, and carries all of it. Scaled from the rat's
     * own HP rather than from an integer Toughness, so the whole range is
     * available a point at a time rather than in tens.
     */
    fun forEncounter(playerLevel: Int, rat: RatEntity): Rustbot {
        val ramp = rampFor(playerLevel)
        return Rustbot(
            name = VARIANTS.random(),
            power = max(1, (rat.power * min(1.0, ramp)).roundToInt()),
            maxHp = max(1, (rat.maxHp * ramp).roundToInt())
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
    /**
     * The Rustbot's health, stored rather than derived.
     *
     * Saves written before this change hold a Toughness instead; see
     * [Companion.load], which converts on the way in.
     */
    val botMaxHp: Int,
    val reward: Int,
    /**
     * Set when this encounter is a boss, naming which one.
     *
     * Null for an ordinary Rustbot. Carried through the save so a boss fight
     * interrupted by the process dying is still a boss fight when it resumes -
     * and so the badge is awarded against the right id.
     */
    val bossId: String? = null
) {
    companion object {
        private const val KEY_PENDING = "ENCOUNTER_PENDING"
        private const val KEY_RAT_ID = "ENCOUNTER_RAT_ID"
        private const val KEY_BOT_NAME = "ENCOUNTER_BOT_NAME"
        private const val KEY_BOT_POWER = "ENCOUNTER_BOT_POWER"
        /**
         * The Rustbot's Toughness, as saves written before HP was stored
         * directly hold it. Read on the way in and never written again.
         */
        private const val KEY_BOT_TOUGH = "ENCOUNTER_BOT_TOUGH"

        private const val KEY_BOT_HP = "ENCOUNTER_BOT_HP"
        private const val KEY_REWARD = "ENCOUNTER_REWARD"

        /** What a stored Toughness was worth in HP, before HP was stored. */
        private const val LEGACY_HP_PER_TOUGHNESS = 10
        private const val KEY_BOSS_ID = "ENCOUNTER_BOSS_ID"

        fun isPending(prefs: SharedPreferences): Boolean =
            prefs.getBoolean(KEY_PENDING, false)

        /**
         * Reads the pending encounter, converting an older save on the way.
         *
         * A fight saved before HP was stored directly holds a Toughness, and
         * ten HP a point is exactly what that build would have given it - so an
         * encounter interrupted by an app update resolves against the same
         * Rustbot it was raised as, rather than silently becoming a different
         * fight. Keyed on which value is present rather than on a migration
         * flag, so a save imported from an older export converts too.
         */
        fun load(prefs: SharedPreferences): Encounter? {
            if (!isPending(prefs)) return null

            val hp = if (prefs.contains(KEY_BOT_HP)) {
                prefs.getInt(KEY_BOT_HP, 1)
            } else {
                prefs.getInt(KEY_BOT_TOUGH, 1) * LEGACY_HP_PER_TOUGHNESS
            }

            return Encounter(
                ratId = prefs.getLong(KEY_RAT_ID, -1L),
                botName = prefs.getString(KEY_BOT_NAME, "Rustbot") ?: "Rustbot",
                botPower = prefs.getInt(KEY_BOT_POWER, 1),
                botMaxHp = max(1, hp),
                reward = prefs.getInt(KEY_REWARD, 0),
                bossId = prefs.getString(KEY_BOSS_ID, null)
            ).takeIf { it.ratId >= 0 }
        }

        fun save(prefs: SharedPreferences, encounter: Encounter) {
            prefs.edit()
                .putBoolean(KEY_PENDING, true)
                .putLong(KEY_RAT_ID, encounter.ratId)
                .putString(KEY_BOT_NAME, encounter.botName)
                .putInt(KEY_BOT_POWER, encounter.botPower)
                .putInt(KEY_BOT_HP, encounter.botMaxHp)
                // Dropped rather than left behind, so the old key cannot sit in
                // the save looking like a value worth reading.
                .remove(KEY_BOT_TOUGH)
                .putInt(KEY_REWARD, encounter.reward)
                // Removed rather than written null, so a boss fight cannot leave
                // its id behind for the next ordinary Rustbot to inherit.
                .apply {
                    if (encounter.bossId != null) putString(KEY_BOSS_ID, encounter.bossId)
                    else remove(KEY_BOSS_ID)
                }
                .apply()
        }

        fun clear(prefs: SharedPreferences) {
            prefs.edit().putBoolean(KEY_PENDING, false).remove(KEY_BOSS_ID).apply()
        }

        /**
         * The pending encounter together with the rat that must fight it, or
         * null if there is nothing fightable.
         *
         * Self-healing, and that is the point. An encounter names a rat by id;
         * the Fusion Pot can consume that rat afterwards, leaving a fight nobody
         * can take. [GameEngine.maybeTriggerEncounter] refuses to raise a new
         * encounter while one is outstanding, so such a leftover does not merely
         * fail to open - it ends combat permanently, with no route back from
         * inside the game. Clearing it here is the only thing standing between a
         * splice and a dead game.
         *
         * Blocking, because it queries the collection.
         */
        fun loadFightable(
            prefs: SharedPreferences,
            dao: RatDao
        ): Pair<Encounter, RatEntity>? {
            val encounter = load(prefs) ?: return null
            val fighter = dao.byId(encounter.ratId)

            if (fighter == null) {
                clear(prefs)
                return null
            }
            return encounter to fighter
        }
    }

    val isBoss: Boolean get() = bossId != null

    /**
     * Builds the simulator for this encounter against [rat].
     *
     * [loadout] is whatever the Shop has armed for this one fight, passed in
     * rather than read here so the simulator stays a pure function of the
     * numbers handed to it - which is what keeps the manual and Auto-Resolve
     * paths impossible to drift apart.
     *
     * Fights on [RatEntity.effectivePower]/[RatEntity.effectiveMaxHp], not the
     * stored stats - deliberately the one place the rarity bonus is spent.
     * [RustbotFactory.forEncounter] sizes [botPower]/[botMaxHp] off the same
     * rat's *stored* stats back when the encounter was raised, exactly the way
     * it has always ignored whatever Loadout would later apply too - so a
     * Rare or Legendary rat is fighting an opponent sized for a plainer rat of
     * its same base stats, not one that grew to match it.
     */
    fun toBattle(rat: RatEntity, loadout: Loadout = Loadout.NONE, startingRatHp: Int? = null): Battle {
        val maxHp = loadout.maxHpFor(rat.effectiveMaxHp)
        return Battle(
            ratName = rat.name,
            ratPower = loadout.powerFor(rat.effectivePower),
            ratMaxHp = maxHp,
            botName = botName,
            botPower = botPower,
            botMaxHp = botMaxHp,
            bossId = bossId,
            ratFaction = rat.faction,
            // Clamped rather than trusted outright - a Loadout bought between
            // an Arena run's fights could otherwise carry in more HP than
            // this fight's max allows.
            startingRatHp = startingRatHp?.coerceIn(1, maxHp) ?: maxHp
        )
    }
}
