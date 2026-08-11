package com.example.ratkingrecon

import android.content.SharedPreferences

/**
 * The state behind the Shop's items, and the only place that reads or writes it.
 *
 * The catalogue in [Shop] says what is for sale; this says what owning it means.
 * Keeping the two apart is what lets the systems that honour an effect - combat,
 * expeditions, the Ledger grid - depend on a single small object rather than on
 * the Shop screen.
 *
 * Everything lives in the same "SaveData" preferences as the rest of the game,
 * so purchases travel with an exported save and are cleared by Reset Save.
 */
object ShopEffects {

    // ---- one-shot flags ------------------------------------------------------

    /** Set by Power Surge, read and cleared by the next fight that resolves. */
    const val KEY_POWER_SURGE = "POWER_SURGE_ACTIVE"

    /**
     * Extra Power a surged rat carries into one fight.
     *
     * Rat Power rolls 1-5 (6-10 with the Serum), so +3 is a real swing without
     * making a fight a foregone conclusion. Nothing in the brief fixed a number.
     */
    const val POWER_SURGE_BONUS = 3

    fun powerSurgeArmed(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_POWER_SURGE, false)

    /** Power to add for a fight starting now. Zero unless a surge is armed. */
    fun surgeBonusFor(prefs: SharedPreferences): Int =
        if (powerSurgeArmed(prefs)) POWER_SURGE_BONUS else 0

    /** Set by the Golden Wrench, read and cleared by the next fight that resolves. */
    const val KEY_GOLDEN_WRENCH = "GOLDEN_WRENCH_ACTIVE"

    /**
     * What a Golden Wrench multiplies, on attack and on staying power.
     *
     * Half again on both is what makes the boss tiers tractable: their Power
     * multipliers sit between 1.15 and 1.4, and their HP between 1.6 and 2.5,
     * against a rat whose own stats never grow with level.
     */
    const val WRENCH_MULTIPLIER = 1.5

    fun wrenchArmed(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_GOLDEN_WRENCH, false)

    /**
     * Everything the Shop has armed, gathered once for a fight about to start.
     *
     * Both buffs stack, and both are spent by the fight they are carried into -
     * see [clearOneShotBuffs], which the resolver calls win or lose.
     */
    fun loadoutFor(prefs: SharedPreferences): Loadout {
        val wrench = if (wrenchArmed(prefs)) WRENCH_MULTIPLIER else 1.0
        return Loadout(
            bonusPower = surgeBonusFor(prefs),
            powerMultiplier = wrench,
            hpMultiplier = wrench
        )
    }

    /** Burns whatever a finished fight was carrying. */
    fun clearOneShotBuffs(prefs: SharedPreferences) {
        prefs.edit()
            .putBoolean(KEY_POWER_SURGE, false)
            .putBoolean(KEY_GOLDEN_WRENCH, false)
            .apply()
    }

    // ---- stackable charges ---------------------------------------------------

    /** Revive tokens held. Bought ahead of a loss rather than paid for after one. */
    const val KEY_REVIVE_TOKENS = "REVIVE_TOKENS"

    fun charges(prefs: SharedPreferences, key: String): Int = prefs.getInt(key, 0)

    fun addCharge(prefs: SharedPreferences, key: String) {
        prefs.edit().putInt(key, charges(prefs, key) + 1).apply()
    }

    /**
     * Spends one charge if there is one.
     *
     * Returns false and changes nothing when the player holds none, so callers
     * can treat "no token" as the ordinary path rather than an error.
     */
    fun spendCharge(prefs: SharedPreferences, key: String): Boolean {
        val held = charges(prefs, key)
        if (held <= 0) return false
        prefs.edit().putInt(key, held - 1).apply()
        return true
    }

    // ---- cosmetics -----------------------------------------------------------

    private const val KEY_FRAME_OWNED = "FRAME_OWNED_"
    private const val KEY_FRAME_EQUIPPED = "FRAME_EQUIPPED"

    fun ownsCosmetic(prefs: SharedPreferences, id: String): Boolean =
        prefs.getBoolean(KEY_FRAME_OWNED + id, false)

    fun grantCosmetic(prefs: SharedPreferences, id: String) {
        // Equipped on purchase: buying a frame and seeing nothing change would
        // read as a broken transaction.
        prefs.edit()
            .putBoolean(KEY_FRAME_OWNED + id, true)
            .putString(KEY_FRAME_EQUIPPED, id)
            .apply()
    }

    fun equippedCosmetic(prefs: SharedPreferences): String? =
        prefs.getString(KEY_FRAME_EQUIPPED, null)

    /** Equipping the frame already on is how it comes back off again. */
    fun toggleEquipped(prefs: SharedPreferences, id: String) {
        val next = if (equippedCosmetic(prefs) == id) null else id
        prefs.edit().putString(KEY_FRAME_EQUIPPED, next).apply()
    }

    // ---- immediate actions ---------------------------------------------------

    // The one place these two keys are spelled out. MainActivity and
    // GalleryActivity read and write the same expedition, so they refer to
    // these rather than repeating the literals and risking a silent typo.
    const val KEY_EXPEDITION_ACTIVE = "EXPEDITION_ACTIVE"
    const val KEY_EXPEDITION_END = "EXPEDITION_END_TIME"

    /**
     * Quick Return: takes a quarter off whatever is left of the expedition.
     *
     * A quarter of the *remaining* time, not of the original run, so buying it
     * twice keeps helping without ever finishing the trip outright. Returns
     * false when there is no expedition out, which is what stops the Shop
     * charging for nothing.
     */
    fun quickReturn(prefs: SharedPreferences): Boolean {
        if (!prefs.getBoolean(KEY_EXPEDITION_ACTIVE, false)) return false

        val endsAt = prefs.getLong(KEY_EXPEDITION_END, 0L)
        val remaining = endsAt - System.currentTimeMillis()
        if (remaining <= 0L) return false

        prefs.edit().putLong(KEY_EXPEDITION_END, endsAt - remaining / 4).apply()
        return true
    }
}
