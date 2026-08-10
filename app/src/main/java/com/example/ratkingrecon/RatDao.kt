package com.example.ratkingrecon

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

/**
 * Queries over the rat collection.
 *
 * Deliberately blocking rather than suspending: the only callers are the Ledger
 * (which runs them on a background dispatcher) and the step-tracking service
 * (whose sensor callbacks are delivered on its own HandlerThread). Keeping them
 * blocking avoids threading coroutines through the sensor hot path.
 */
@Dao
interface RatDao {

    @Query("SELECT * FROM rats ORDER BY caughtAt ASC, id ASC")
    fun all(): List<RatEntity>

    @Query("SELECT * FROM rats ORDER BY power DESC, id ASC")
    fun byPowerDesc(): List<RatEntity>

    @Query("SELECT * FROM rats WHERE shiny = 1 ORDER BY caughtAt ASC, id ASC")
    fun shinyOnly(): List<RatEntity>

    @Query("SELECT * FROM rats WHERE id = :id")
    fun byId(id: Long): RatEntity?

    @Query("SELECT COUNT(*) FROM rats")
    fun count(): Int

    /**
     * How many distinct roster species have been found.
     *
     * Counted in SQL rather than by loading every row, and restricted to keys
     * the roster still knows about so a card recovered from an old save cannot
     * push the total past the roster size.
     */
    @Query("SELECT COUNT(DISTINCT artKey) FROM rats WHERE artKey IN (:rosterKeys)")
    fun distinctSpeciesFound(rosterKeys: List<String>): Int

    @Query("SELECT COALESCE(MAX(power), 0) FROM rats")
    fun maxPower(): Int

    @Query("SELECT COALESCE(MAX(toughness), 0) FROM rats")
    fun maxToughness(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM rats WHERE shiny = 1)")
    fun ownsShiny(): Boolean

    /** The weakest [limit] rats, which the Fusion Pot consumes. */
    @Query("SELECT * FROM rats ORDER BY (power + toughness) ASC, id ASC LIMIT :limit")
    fun weakest(limit: Int): List<RatEntity>

    /** The best rat that is not knocked out, used to pick a fighter. */
    @Query("""
        SELECT * FROM rats
        WHERE recoveringUntil <= :now
        ORDER BY (power + toughness) DESC, id ASC
        LIMIT 1
    """)
    fun strongestAvailable(now: Long): RatEntity?

    @Query("SELECT COUNT(*) FROM rats WHERE recoveringUntil <= :now")
    fun availableCount(now: Long): Int

    @Query("UPDATE rats SET wins = wins + 1 WHERE id = :id")
    fun recordWin(id: Long)

    /** Loses a fight: the rat is knocked out until [until]. */
    @Query("UPDATE rats SET losses = losses + 1, recoveringUntil = :until WHERE id = :id")
    fun recordLoss(id: Long, until: Long)

    @Query("UPDATE rats SET recoveringUntil = 0 WHERE id = :id")
    fun revive(id: Long)

    @Insert
    fun insert(rat: RatEntity): Long

    @Insert
    fun insertAll(rats: List<RatEntity>)

    @Update
    fun update(rat: RatEntity)

    @Delete
    fun delete(rats: List<RatEntity>)

    /**
     * Fuses two rats into one, atomically.
     *
     * A transaction so a crash mid-splice cannot eat the parents without
     * producing the mutant.
     */
    @Transaction
    fun splice(parents: List<RatEntity>, mutant: RatEntity) {
        delete(parents)
        insert(mutant)
    }
}
