package com.example.ratkingrecon

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The rat collection.
 *
 * Only rats live here. Scalars the step loop touches constantly - Scrap, level,
 * EXP, bounty and expedition state - stay in SharedPreferences, where a
 * synchronous read on the sensor path costs nothing.
 */
@Database(
    entities = [RatEntity::class],
    version = 1,
    exportSchema = true
)
abstract class RatDatabase : RoomDatabase() {

    abstract fun ratDao(): RatDao

    companion object {
        private const val NAME = "ratking.db"

        @Volatile
        private var instance: RatDatabase? = null

        fun get(context: Context): RatDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RatDatabase::class.java,
                    NAME
                ).build().also { instance = it }
            }
    }
}
