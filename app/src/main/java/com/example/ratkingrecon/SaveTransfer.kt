package com.example.ratkingrecon

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Raised when a file is not a save this build knows how to restore. */
class SaveFormatException(message: String) : Exception(message)

/**
 * The whole game state, as one JSON document.
 *
 * Two stores back the game, so both are in the file. SharedPreferences holds the
 * scalars - Scrap, level, EXP, bounty and expedition timers - and Room holds the
 * rat collection. A file with only the prefs half would restore a player's
 * Scrap and level and quietly leave them with no rats, so the roster is exported
 * out of the database rather than out of the stale pre-Room blob that still sits
 * in SharedPreferences under CATALOGUE_V2.
 *
 * Preference values carry an explicit type because SharedPreferences is typed
 * and JSON is not. A 5 written back as the wrong type is not a rounding error:
 * getInt on a Long returns the *default*, so the value would vanish silently.
 */
object SaveTransfer {

    const val FORMAT = "ratkingrecon.save"
    const val VERSION = 1

    private const val F_FORMAT = "format"
    private const val F_VERSION = "version"
    private const val F_EXPORTED_AT = "exportedAt"
    private const val F_PREFS = "prefs"
    private const val F_RATS = "rats"

    private const val F_KEY = "key"
    private const val F_TYPE = "type"
    private const val F_VALUE = "value"

    private const val T_INT = "int"
    private const val T_LONG = "long"
    private const val T_FLOAT = "float"
    private const val T_BOOLEAN = "boolean"
    private const val T_STRING = "string"
    private const val T_STRING_SET = "stringSet"

    /** One preference, with enough type information to put it back as it was. */
    data class PrefEntry(val key: String, val type: String, val value: Any) {

        /** The casts are safe: [SaveTransfer.parse] is the only thing that builds these. */
        @Suppress("UNCHECKED_CAST")
        fun writeTo(editor: SharedPreferences.Editor) {
            when (type) {
                T_INT -> editor.putInt(key, value as Int)
                T_LONG -> editor.putLong(key, value as Long)
                T_FLOAT -> editor.putFloat(key, value as Float)
                T_BOOLEAN -> editor.putBoolean(key, value as Boolean)
                T_STRING -> editor.putString(key, value as String)
                T_STRING_SET -> editor.putStringSet(key, (value as Set<String>).toMutableSet())
            }
        }
    }

    /** A parsed, fully validated save. Holding one means it is safe to apply. */
    data class Snapshot(
        val exportedAt: Long,
        val prefs: List<PrefEntry>,
        val rats: List<RatEntity>
    )

    // ---- writing -------------------------------------------------------------

    fun export(prefs: SharedPreferences, dao: RatDao): String =
        JSONObject()
            .put(F_FORMAT, FORMAT)
            .put(F_VERSION, VERSION)
            .put(F_EXPORTED_AT, System.currentTimeMillis())
            .put(F_PREFS, prefsToJson(prefs))
            .put(F_RATS, ratsToJson(dao.all()))
            .toString(2)

    private fun prefsToJson(prefs: SharedPreferences): JSONArray {
        val out = JSONArray()

        // Sorted, so exporting the same save twice produces the same file.
        for ((key, value) in prefs.all.toSortedMap()) {
            val entry = JSONObject().put(F_KEY, key)
            when (value) {
                is Int -> entry.put(F_TYPE, T_INT).put(F_VALUE, value)
                is Long -> entry.put(F_TYPE, T_LONG).put(F_VALUE, value)
                is Float -> entry.put(F_TYPE, T_FLOAT).put(F_VALUE, value.toDouble())
                is Boolean -> entry.put(F_TYPE, T_BOOLEAN).put(F_VALUE, value)
                is String -> entry.put(F_TYPE, T_STRING).put(F_VALUE, value)
                is Set<*> -> entry.put(F_TYPE, T_STRING_SET)
                    .put(F_VALUE, JSONArray(value.map { it.toString() }))
                // Not a type SharedPreferences can hold, so there is nothing to
                // round-trip; skipping beats writing something unreadable.
                else -> continue
            }
            out.put(entry)
        }
        return out
    }

    /**
     * Rat rows, ids included.
     *
     * The ids matter: DEPLOYED_RAT_ID and ENCOUNTER_RAT_ID in the preferences
     * half point at them, so regenerating them on import would leave a running
     * expedition or a pending fight aimed at a rat that no longer exists.
     */
    private fun ratsToJson(rats: List<RatEntity>): JSONArray {
        val out = JSONArray()
        for (rat in rats) {
            out.put(
                JSONObject()
                    .put("id", rat.id)
                    .put("artKey", rat.artKey)
                    .put("name", rat.name)
                    .put("power", rat.power)
                    .put("toughness", rat.toughness)
                    .put("shiny", rat.shiny)
                    .put("caughtAt", rat.caughtAt)
                    .put("isSpliced", rat.isSpliced)
                    .put("wins", rat.wins)
                    .put("losses", rat.losses)
                    .put("battleExp", rat.battleExp)
                    .put("ratLevel", rat.ratLevel)
                    .put("recoveringUntil", rat.recoveringUntil)
                    .put("bonusHp", rat.bonusHp)
            )
        }
        return out
    }

    // ---- reading -------------------------------------------------------------

    /**
     * Parses and checks a save without touching any stored state.
     *
     * Everything is validated up front precisely so that [apply] cannot fail
     * halfway: a file that is going to be rejected is rejected while the
     * player's existing progress is still completely intact.
     */
    fun parse(raw: String): Snapshot {
        val root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            throw SaveFormatException("that file is not valid JSON")
        }

        if (root.optString(F_FORMAT) != FORMAT) {
            throw SaveFormatException("that file is not a Rat King Recon save")
        }

        val version = root.optInt(F_VERSION, 0)
        if (version < 1) throw SaveFormatException("that save has no version")
        if (version > VERSION) {
            throw SaveFormatException("that save was written by a newer version of the app")
        }

        return Snapshot(
            exportedAt = root.optLong(F_EXPORTED_AT, 0L),
            prefs = parsePrefs(root.optJSONArray(F_PREFS)),
            rats = parseRats(root.optJSONArray(F_RATS))
        )
    }

    private fun parsePrefs(array: JSONArray?): List<PrefEntry> {
        val json = array ?: throw SaveFormatException("that save has no \"$F_PREFS\" section")
        val entries = mutableListOf<PrefEntry>()

        for (i in 0 until json.length()) {
            val obj = json.optJSONObject(i)
                ?: throw SaveFormatException("preference ${i + 1} is not an object")

            val key = obj.optString(F_KEY)
            if (key.isEmpty()) throw SaveFormatException("preference ${i + 1} has no key")

            val type = obj.optString(F_TYPE)
            if (!obj.has(F_VALUE)) throw SaveFormatException("\"$key\" has no value")

            val value: Any = when (type) {
                T_INT -> obj.optInt(F_VALUE)
                T_LONG -> obj.optLong(F_VALUE)
                T_FLOAT -> obj.optDouble(F_VALUE).toFloat()
                T_BOOLEAN -> obj.optBoolean(F_VALUE)
                T_STRING -> obj.optString(F_VALUE)
                T_STRING_SET -> {
                    val set = obj.optJSONArray(F_VALUE)
                        ?: throw SaveFormatException("\"$key\" should be a list of strings")
                    (0 until set.length()).map { set.optString(it) }.toSet()
                }
                else -> throw SaveFormatException("\"$key\" has an unknown type \"$type\"")
            }

            entries += PrefEntry(key, type, value)
        }
        return entries
    }

    private fun parseRats(array: JSONArray?): List<RatEntity> {
        // A save with no rats is legitimate - a brand new player has none - but
        // the section itself has to be there, or this is not one of our files.
        val json = array ?: throw SaveFormatException("that save has no \"$F_RATS\" section")
        val rats = mutableListOf<RatEntity>()

        for (i in 0 until json.length()) {
            val obj = json.optJSONObject(i)
                ?: throw SaveFormatException("rat ${i + 1} is not an object")

            val artKey = obj.optString("artKey")
            if (artKey.isEmpty()) throw SaveFormatException("rat ${i + 1} has no artwork key")

            rats += RatEntity(
                id = obj.optLong("id", 0L),
                artKey = artKey,
                name = obj.optString("name", "Unknown"),
                power = obj.optInt("power", 1),
                toughness = obj.optInt("toughness", 1),
                shiny = obj.optBoolean("shiny", false),
                caughtAt = obj.optLong("caughtAt", 0L),
                isSpliced = obj.optBoolean("isSpliced", false),
                wins = obj.optInt("wins", 0),
                losses = obj.optInt("losses", 0),
                battleExp = obj.optInt("battleExp", 0),
                ratLevel = obj.optInt("ratLevel", 1),
                recoveringUntil = obj.optLong("recoveringUntil", 0L),
                bonusHp = obj.optInt("bonusHp", 0)
            )
        }
        return rats
    }

    // ---- restoring -----------------------------------------------------------

    /**
     * Replaces every piece of saved state with [snapshot].
     *
     * Blocking and destructive: callers confirm with the player first and run it
     * off the main thread. Preferences are committed rather than applied, so the
     * write is known to have landed before step tracking is allowed to resume.
     */
    fun apply(snapshot: Snapshot, prefs: SharedPreferences, dao: RatDao) {
        val editor = prefs.edit().clear()
        snapshot.prefs.forEach { it.writeTo(editor) }

        // The pre-Room CATALOGUE blob is still inside the file we just restored.
        // Without this flag the next database open would import it all over
        // again, on top of the rats written below, duplicating the collection.
        editor.putBoolean(RatRepository.KEY_IMPORTED, true)

        // Dropped rather than restored - see GameEngine.KEY_BASELINE. The next
        // sensor reading re-baselines instead of banking the difference as EXP.
        editor.remove(GameEngine.KEY_BASELINE)

        editor.commit()

        dao.replaceAll(snapshot.rats)
    }
}
