package io.github.jmatts94.ratkingrecon

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.roundToInt

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
     * What a Power Surge multiplies the rat's Power by.
     *
     * A proportion rather than the flat +3 this used to be. Rustbots - and
     * bosses especially - are scaled off the rat that meets them, so a flat
     * bonus was worth twice as much to a Power 3 rat as to a Power 6 one, and
     * the stronger the roster got the less a Surge did. Simulated against the
     * boss tiers, the flat version actually inverted: a 3/3 rat beat four of
     * the five, an 8/8 rat only three.
     *
     * Half again, and not less, because Power is a small integer. Anything under
     * 17% rounds away entirely on a Power 3 rat, and under 50% on a Power 1 one -
     * an item that visibly does nothing is worse than no item.
     */
    const val SURGE_MULTIPLIER = 1.5

    fun powerSurgeArmed(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_POWER_SURGE, false)

    /** Set by the Golden Wrench, read and cleared by the next fight that resolves. */
    const val KEY_GOLDEN_WRENCH = "GOLDEN_WRENCH_ACTIVE"

    /**
     * What a Golden Wrench multiplies, on attack and on staying power.
     *
     * The same proportion on Power as a Surge; what the extra 125 Scrap buys is
     * the second half, on maximum HP. That is what carries a rat through The
     * Rustbringer, which no Surge beats at any rat size.
     */
    const val WRENCH_MULTIPLIER = 1.5

    fun wrenchArmed(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_GOLDEN_WRENCH, false)

    /**
     * Set by the Relic Trader's Trail Rations, read and cleared by the next
     * batch of steps that banks EXP - see GameEngine.onSteps. The same
     * one-shot shape as a Power Surge or Golden Wrench, just armed by a relic
     * trade instead of a Shop purchase, and spent by a walk instead of a fight.
     */
    const val KEY_TRAIL_RATIONS = "TRAIL_RATIONS_ACTIVE"

    /** What Trail Rations multiplies the next steps batch's banked EXP by. */
    const val TRAIL_RATIONS_MULTIPLIER = 2.0

    fun trailRationsArmed(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_TRAIL_RATIONS, false)

    /**
     * A multiplier as the percentage its description quotes: 1.5 reads as 50.
     *
     * The Shop sells both of these on a "+50%" that used to be typed into the
     * string beside them. Deriving it means retuning a multiplier cannot leave
     * the shelf advertising the old one.
     */
    fun percentBonus(multiplier: Double): Int = ((multiplier - 1.0) * 100).roundToInt()

    /**
     * What the Shop has armed, gathered once for a fight about to start.
     *
     * The two are mutually exclusive, and the Shop refuses to sell one while the
     * other is armed. This resolves it a second time anyway, in favour of the
     * dearer item, so a save that somehow holds both - an import from an older
     * build, where they stacked - cannot end up worse off than either alone.
     *
     * Whichever applied is spent by the fight it was carried into; see
     * [clearOneShotBuffs], which the resolver calls win or lose.
     */
    fun loadoutFor(prefs: SharedPreferences): Loadout = when {
        wrenchArmed(prefs) -> Loadout(
            powerMultiplier = WRENCH_MULTIPLIER,
            hpMultiplier = WRENCH_MULTIPLIER
        )

        powerSurgeArmed(prefs) -> Loadout(powerMultiplier = SURGE_MULTIPLIER)

        else -> Loadout.NONE
    }

    /**
     * True when [key] is a combat buff the player already has one of.
     *
     * The Shop asks before charging, so buying a Surge with a Wrench in hand
     * cannot quietly take the Scrap for something that would never apply.
     */
    fun conflictsWithArmedBuff(prefs: SharedPreferences, key: String): Boolean = when (key) {
        KEY_POWER_SURGE -> wrenchArmed(prefs)
        KEY_GOLDEN_WRENCH -> powerSurgeArmed(prefs)
        else -> false
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

    /**
     * Masterwork Hatchery discount vouchers held, traded for at the Relic Trader.
     *
     * A charge rather than a flag so they stack, but only one is ever applied to
     * a purchase - banking three does not make the next hatch free, it makes the
     * next three cheaper.
     */
    const val KEY_MASTERWORK_VOUCHER = "MASTERWORK_VOUCHER"

    /** Scrap off one Masterwork pull. */
    const val MASTERWORK_VOUCHER_VALUE = 100

    /**
     * Arena free-entry vouchers held, traded for at the Relic Trader.
     *
     * The same shape as [KEY_MASTERWORK_VOUCHER]: a charge rather than a flag
     * so they stack, but only one is ever spent on an entry - see
     * [arenaEntryCost].
     */
    const val KEY_ARENA_ENTRY_VOUCHER = "ARENA_ENTRY_VOUCHER"

    /**
     * The four combat items - see [Battle.applyItem] for what each does and
     * [BattleActivity]'s Items panel for where they are spent. Usable in any
     * fight, unlike Power Surge/Golden Wrench, which is why they are held as
     * counts rather than armed one at a time.
     */
    const val KEY_HP_TONIC = "ITEM_HP_TONIC"
    const val KEY_CORROSIVE_CHARGE = "ITEM_CORROSIVE_CHARGE"
    const val KEY_REINFORCED_PLATING = "ITEM_REINFORCED_PLATING"
    const val KEY_CLEANSE = "ITEM_CLEANSE"

    /**
     * How many of one combat item can be held at once.
     *
     * Applied only to the four keys above - Revive Tokens and the Masterwork
     * voucher have never had a cap and this does not give them one. The Shop
     * refuses a purchase that would cross it; see [ShopEffect.Charge.cap].
     */
    const val ITEM_CHARGE_CAP = 5

    /** What the Hatchery costs right now, with a voucher applied if one is held. */
    fun masterworkPrice(prefs: SharedPreferences): Int =
        if (charges(prefs, KEY_MASTERWORK_VOUCHER) > 0) {
            (Masterwork.PRICE - MASTERWORK_VOUCHER_VALUE).coerceAtLeast(0)
        } else {
            Masterwork.PRICE
        }

    /** What the Arena's entry costs right now - waived entirely if a voucher is held. */
    fun arenaEntryCost(prefs: SharedPreferences): Int =
        if (charges(prefs, KEY_ARENA_ENTRY_VOUCHER) > 0) 0 else Arena.ENTRY_COST

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

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS

    /**
     * "3h 12m left" / "40m left" / "Ready" - the same three bands and the
     * same strings the home screen's own expedition tile already reads, so
     * a caller with its own "how long is left" question (the enlarged rat
     * dialog's deploy button, for the specific rat that is the one out)
     * gets the identical wording rather than a second phrasing of it.
     */
    fun expeditionTimeLeftText(context: Context, remainingMs: Long): String = when {
        remainingMs <= 0L -> context.getString(R.string.tile_expedition_collect)
        remainingMs >= HOUR_MS -> context.getString(
            R.string.tile_expedition_hm,
            remainingMs / HOUR_MS,
            (remainingMs % HOUR_MS) / MINUTE_MS
        )
        else -> context.getString(R.string.tile_expedition_m, remainingMs / MINUTE_MS)
    }
}
