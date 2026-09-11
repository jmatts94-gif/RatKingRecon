package io.github.jmatts94.ratkingrecon

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One rat in the player's collection.
 *
 * Every row is a distinct instance: two rats rolled with identical stats are two
 * rows with two ids. That is the main thing the old JSON blob could not express,
 * and it is what upcoming combat state hangs off.
 *
 * [artKey] is a stable name from [RatArt], never a drawable resource ID -
 * resource IDs are regenerated on every build and must not reach storage.
 *
 * The combat columns all carry defaults so they can be introduced without
 * backfilling existing rows.
 */
@Entity(
    tableName = "rats",
    indices = [Index("artKey"), Index("shiny")]
)
data class RatEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "artKey")
    val artKey: String,

    val name: String,
    val power: Int,
    val toughness: Int,
    val shiny: Boolean,

    /** When this rat hatched, for "newest first" ordering. */
    val caughtAt: Long = System.currentTimeMillis(),

    /** Spliced mutants are minted by the Fusion Pot rather than hatched. */
    val isSpliced: Boolean = false,

    // --- combat ---
    val wins: Int = 0,
    val losses: Int = 0,
    val battleExp: Int = 0,
    val ratLevel: Int = 1,

    /** Epoch millis until which this rat is knocked out. 0 means ready. */
    val recoveringUntil: Long = 0,

    /**
     * Flat HP on top of the Toughness-derived base.
     *
     * Max HP is deliberately *not* stored: it is a function of Toughness, and a
     * stored copy would drift the moment the Fusion Pot changes that. This
     * column exists so future upgrades can add HP without duplicating the base.
     */
    val bonusHp: Int = 0
) {
    /** Battle HP. Derived, so existing rats are correct with no backfill. */
    val maxHp: Int get() = toughness * 10 + bonusHp

    fun isRecovering(now: Long = System.currentTimeMillis()): Boolean = recoveringUntil > now

    /** Resolved at render time; see [artKey]. */
    val imageRes: Int get() = RatArt.resId(artKey)

    /** Combined stat line, used to pick splice fodder and to rank the binder. */
    val score: Int get() = power + toughness

    /**
     * The species [artKey] belongs to, looked up fresh rather than stored.
     *
     * Same reasoning as [imageRes]: rarity lives on [Roster], not on this row,
     * so a rat's tier is always read from the roster of today rather than
     * frozen at mint time. Null for an artKey the current roster no longer
     * recognises - an old save's fallback art, say - in which case no rarity
     * bonus applies rather than guessing one.
     */
    val rarity: String? get() = Roster.all.firstOrNull { it.artKey == artKey }?.rarity

    /**
     * The species [artKey] belongs to's faction, looked up fresh; see [rarity].
     *
     * Used to check a boss's named Special against - see [BossMoves] - which is
     * the first thing [Rat.faction] actually decides rather than merely labels.
     */
    val faction: String? get() = Roster.all.firstOrNull { it.artKey == artKey }?.faction

    /** Power with the rarity bonus folded in. What combat and every display use. */
    val effectivePower: Int get() = power + Roster.statBonusFor(rarity)

    /** Toughness with the rarity bonus folded in. */
    val effectiveToughness: Int get() = toughness + Roster.statBonusFor(rarity)

    /** Battle HP off the boosted Toughness rather than the stored one. */
    val effectiveMaxHp: Int get() = effectiveToughness * 10 + bonusHp

    /** Gears the rarity badge draws: one for Common, two for Rare, three for Legendary. */
    val gearCount: Int get() = Roster.gearCountFor(rarity)

    /**
     * Whether this card draws [ShinyFoilDrawable] - a real shiny roll, or
     * TimeTail (see [Roster.SECRET]), who gets the same treatment on his own
     * merits rather than the RNG's. Deliberately not read off [shiny] itself:
     * that column feeds [RatDao.ownsShiny] and the Ledger Task shiny
     * requirement (see [TaskRatPickerActivity.REQUIRES_SHINY]), neither of
     * which TimeTail should silently satisfy just to borrow the card art.
     *
     * Scoped to the art alone - his name stays plain and his gold comes from
     * [gearCount]/the Achievements screen already, not from this.
     */
    val showsFoil: Boolean get() = shiny || rarity.equals(Roster.SECRET, ignoreCase = true)
}
