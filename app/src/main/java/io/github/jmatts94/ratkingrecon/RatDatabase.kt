package io.github.jmatts94.ratkingrecon

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The rat collection.
 *
 * Only rats live here. Scalars the step loop touches constantly - Scrap, level,
 * EXP, bounty and expedition state - stay in SharedPreferences, where a
 * synchronous read on the sensor path costs nothing.
 */
@Database(
    entities = [RatEntity::class],
    version = 2,
    exportSchema = true
)
abstract class RatDatabase : RoomDatabase() {

    abstract fun ratDao(): RatDao

    companion object {
        private const val NAME = "ratking.db"

        /**
         * Adds the combat columns.
         *
         * Both are defaulted, so existing rats need no backfill: a rat that has
         * never fought is simply ready with no bonus HP. Max HP stays derived
         * from Toughness rather than stored.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rats ADD COLUMN recoveringUntil INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rats ADD COLUMN bonusHp INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var instance: RatDatabase? = null

        fun get(context: Context): RatDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RatDatabase::class.java,
                    NAME
                ).addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
    }
}
