package com.example.ratkingrecon

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * The single way into rat storage.
 *
 * Also owns the one-time import from SharedPreferences. That import runs
 * lazily on first access rather than being kicked off at startup, which
 * removes any chance of a screen querying the database before the old data has
 * landed in it.
 *
 * Every method here blocks, so callers must already be off the main thread:
 * activities go through a background dispatcher, and the step-tracking service
 * receives its sensor events on its own HandlerThread.
 */
object RatRepository {

    private const val TAG = "RatRepository"
    private const val PREFS = "SaveData"

    /** Set once the legacy import has run, so it never runs twice. */
    const val KEY_IMPORTED = "RATS_IMPORTED_TO_ROOM"

    @Volatile
    private var importChecked = false

    /** The DAO, with the legacy import guaranteed to have happened first. */
    fun dao(context: Context): RatDao {
        val app = context.applicationContext
        importLegacyIfNeeded(app)
        return RatDatabase.get(app).ratDao()
    }

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Moves any pre-Room collection into the database, exactly once.
     *
     * The old SharedPreferences keys are deliberately left in place. They cost
     * nothing, and if an import ever goes wrong the fix is to clear
     * [KEY_IMPORTED] and let it run again against untouched source data.
     */
    private fun importLegacyIfNeeded(app: Context) {
        if (importChecked) return

        synchronized(this) {
            if (importChecked) return

            val prefs = prefs(app)
            if (!prefs.getBoolean(KEY_IMPORTED, false)) {
                val rats = LegacyImport.read(prefs)
                if (rats.isNotEmpty()) {
                    RatDatabase.get(app).ratDao().insertAll(rats)
                }
                prefs.edit().putBoolean(KEY_IMPORTED, true).apply()
                Log.i(TAG, "Imported ${rats.size} rat(s) from SharedPreferences into Room")
            }

            importChecked = true
        }
    }
}
