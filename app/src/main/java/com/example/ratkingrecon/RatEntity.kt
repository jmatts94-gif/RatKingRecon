package com.example.ratkingrecon

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

    // --- combat, not yet used ---
    val wins: Int = 0,
    val losses: Int = 0,
    val battleExp: Int = 0,
    val ratLevel: Int = 1
) {
    /** Resolved at render time; see [artKey]. */
    val imageRes: Int get() = RatArt.resId(artKey)

    /** Combined stat line, used to pick splice fodder and to rank the binder. */
    val score: Int get() = power + toughness
}
