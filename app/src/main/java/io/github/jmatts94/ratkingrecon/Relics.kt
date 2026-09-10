package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * One kind of relic.
 *
 * [id] reaches the save and must never change. [legacyName] is what this relic
 * was stored as before relics became countable - the display string itself,
 * emoji and all - and is kept only so [Relics.migrateIfNeeded] can recognise it.
 *
 * Moving the display text into a resource is the point of the split: the old
 * scheme stored the name, so the name could never be corrected or translated
 * without orphaning what players already held.
 *
 * [iconRes] is a real vector icon rather than the emoji [legacyName] carries -
 * the Relic Trader's own icon tiles used to fall back on that emoji baked into
 * [nameRes]'s text, which read as flat next to every other icon in the game.
 */
data class Relic(
    val id: String,
    @param:StringRes val nameRes: Int,
    val legacyName: String,
    @param:DrawableRes val iconRes: Int
)

/**
 * The relics a player holds, counted per type.
 *
 * Relics used to live in a single Set<String>, which silently discarded every
 * duplicate: four were the most anyone could ever hold, and a fifth drop of one
 * already owned added nothing while still announcing itself. They are counts
 * now, so they accumulate and can be spent - see [RelicTrader].
 */
object Relics {

    val ALL: List<Relic> = listOf(
        // ic_settings rather than ic_gear_double/ic_gear_triple: those two are
        // Arena win-streak achievement badges, composed as several gears
        // scattered outside center on purpose for a bigger badge context -
        // shrunk into a pill they read as clipped. A single centered gear
        // fits both the pill and the name.
        Relic("rusted_gear", R.string.relic_rusted_gear, "⚙️ Rusted Gear", R.drawable.ic_settings),
        Relic("glowing_vial", R.string.relic_glowing_vial, "🧪 Glowing Vial", R.drawable.ic_flask),
        Relic("tattered_blueprint", R.string.relic_tattered_blueprint, "📜 Tattered Blueprint", R.drawable.ic_contract),
        // Shares Compass Charm's own icon - Compass Charm is the one gear
        // piece this relic actually crafts.
        Relic("heavy_wrench", R.string.relic_heavy_wrench, "🔧 Heavy Wrench", R.drawable.ic_expedition),
        // Earned by walking, not by fighting or a task board - see
        // GameEngine.checkWornCogDrop. Kept out of every combat/task relic
        // roll (this file's own rollFor, Ledger Tasks, the Scrap Run), so
        // this stays the one relic a player only ever sees from steps. No
        // legacy name: this relic postdates the Set<String> scheme
        // migrateIfNeeded exists to convert, so there is nothing old to
        // recognise. Shares Worn Pedometer's own icon - both are the same
        // "earned by walking" idea.
        Relic("worn_cog", R.string.relic_worn_cog, "", R.drawable.ic_footprint)
    )

    private const val KEY_PREFIX = "RELIC_"

    /** The old Set<String>. Its presence is what triggers the migration. */
    const val KEY_LEGACY = "RELICS"

    fun byId(id: String): Relic? = ALL.firstOrNull { it.id == id }

    fun countKey(relic: Relic): String = KEY_PREFIX + relic.id

    // ---- migration -----------------------------------------------------------

    /**
     * Turns a legacy set of relic names into counts, once.
     *
     * Keyed on the old key still being there rather than on a "migrated" flag,
     * and that is deliberate. [SaveTransfer] round-trips raw preferences, so
     * importing a save exported by an older build brings the old set back with
     * it. A one-shot flag would already be set and would skip it, stranding
     * those relics; presence-as-signal simply migrates again.
     *
     * Each held name becomes one of that relic, which is the honest maximum -
     * the old set never recorded more than one, so there is no larger number to
     * restore. Anything unrecognised is dropped rather than guessed at.
     */
    fun migrateIfNeeded(prefs: SharedPreferences) {
        if (!prefs.contains(KEY_LEGACY)) return

        val held = prefs.getStringSet(KEY_LEGACY, emptySet()).orEmpty()
        val editor = prefs.edit()

        for (name in held) {
            val relic = ALL.firstOrNull { it.legacyName == name } ?: continue
            val key = countKey(relic)
            editor.putInt(key, prefs.getInt(key, 0) + 1)
        }

        // Removed in the same edit that banks the counts, so the migration
        // cannot half-happen and run again on top of itself.
        editor.remove(KEY_LEGACY).apply()
    }

    // ---- counts --------------------------------------------------------------

    fun countOf(prefs: SharedPreferences, relic: Relic): Int {
        migrateIfNeeded(prefs)
        return prefs.getInt(countKey(relic), 0)
    }

    /** Every relic held, of every type. */
    fun total(prefs: SharedPreferences): Int {
        migrateIfNeeded(prefs)
        return ALL.sumOf { prefs.getInt(countKey(it), 0) }
    }

    /** How many different kinds are held, which is all the old counter could show. */
    fun typesHeld(prefs: SharedPreferences): Int {
        migrateIfNeeded(prefs)
        return ALL.count { prefs.getInt(countKey(it), 0) > 0 }
    }

    /**
     * Stages [amount] more of [relic] into [editor].
     *
     * Takes the caller's editor so a drop lands in the same commit as the rest
     * of the claim it came from.
     */
    fun grant(
        prefs: SharedPreferences,
        editor: SharedPreferences.Editor,
        relic: Relic,
        amount: Int = 1
    ) {
        migrateIfNeeded(prefs)
        editor.putInt(countKey(relic), prefs.getInt(countKey(relic), 0) + amount)
    }

    /**
     * Takes [amount] of [relic] away, or nothing at all.
     *
     * Returns false and changes nothing when the player is short, so a caller
     * can check and spend in one step without risking a partial deduction.
     */
    fun spend(prefs: SharedPreferences, relic: Relic, amount: Int): Boolean {
        val held = countOf(prefs, relic)
        if (held < amount) return false

        prefs.edit().putInt(countKey(relic), held - amount).apply()
        return true
    }

    // ---- drops ---------------------------------------------------------------

    /**
     * The pool [rollFor] and [DailyQuest.eligibleRelics] both draw from -
     * every relic but Worn Cog, which only ever drops from
     * [GameEngine.checkWornCogDrop]. Without this exclusion, appending Worn
     * Cog to [ALL] (needed so [RelicTrader]'s own index lookups and
     * [GearPieces]' craft costs can name it the same way every other relic
     * is named) would silently leak it into every combat/task relic roll
     * too, which is exactly the "not just combat" split this relic exists
     * to make.
     */
    private val COMBAT_TASK_POOL: List<Relic> = ALL.filterNot { it.id == "worn_cog" }

    /**
     * Rolls a relic drop at [chance], or null.
     *
     * Takes the chance directly rather than a [LedgerTaskTier] - a Ledger Task
     * still calls this with that tier's own [LedgerTaskTier.relicChance] (which
     * used to be a flat 25% everywhere, and made the two-hour task roughly
     * twelve times the best relic-per-hour rate in the game with no reason to
     * run the long ones), but the Scrap Run has no tier to read a chance off
     * and a Tinkerer bonus needs to adjust either one before the roll happens,
     * not after.
     *
     * Which relic drops is still uniform - the caller decides how often, not
     * what - but only ever among [COMBAT_TASK_POOL], not [ALL].
     */
    fun rollFor(chance: Double): Relic? =
        if (Math.random() < chance) COMBAT_TASK_POOL.random() else null
}
