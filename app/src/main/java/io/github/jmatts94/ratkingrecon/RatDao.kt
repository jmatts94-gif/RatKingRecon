package io.github.jmatts94.ratkingrecon

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

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

    /**
     * The roster's best stats and whether it holds a shiny, ignoring one rat.
     *
     * [excludedId] is [BattleRat.NONE] (-1) to count everything, which no row's
     * id can equal - so the Ledger Tasks gate has one code path whether or not a
     * Battle Rat is standing out of it.
     */
    @Query("SELECT COALESCE(MAX(power), 0) FROM rats WHERE id != :excludedId")
    fun maxPowerExcluding(excludedId: Long): Int

    @Query("SELECT COALESCE(MAX(toughness), 0) FROM rats WHERE id != :excludedId")
    fun maxToughnessExcluding(excludedId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM rats WHERE shiny = 1 AND id != :excludedId)")
    fun ownsShinyExcluding(excludedId: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM rats WHERE shiny = 1)")
    fun ownsShiny(): Boolean

    /** The best rat that is not knocked out, used to pick a fighter. */
    @Query("""
        SELECT * FROM rats
        WHERE recoveringUntil <= :now
        ORDER BY (power + toughness) DESC, id ASC
        LIMIT 1
    """)
    fun strongestAvailable(now: Long): RatEntity?

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

    @Delete
    fun delete(rats: List<RatEntity>)

    @Query("DELETE FROM rats")
    fun deleteAll()

    /**
     * Swaps the whole collection for [rats], atomically.
     *
     * Used by save import. A transaction so a crash partway cannot leave the
     * player with the old collection deleted and the new one not yet written.
     */
    @Transaction
    fun replaceAll(rats: List<RatEntity>) {
        deleteAll()
        insertAll(rats)
    }

    /**
     * Fuses two rats into one, atomically.
     *
     * A transaction so a crash mid-splice cannot eat the parents without
     * producing the mutant. Returns the mutant's real row id - [mutant] itself
     * still carries the placeholder 0 [RatEntity.id] is given before an
     * insert assigns one, and the caller needs the real id to show the result
     * card for this exact rat.
     */
    @Transaction
    fun splice(parents: List<RatEntity>, mutant: RatEntity): Long {
        delete(parents)
        return insert(mutant)
    }
}
