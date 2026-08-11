package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException

/**
 * Reads rats out of the two SharedPreferences formats that predate Room.
 *
 * Parsing only - nothing here writes. [RatRepository] owns when this runs and
 * where the results go.
 *
 * Two historical formats exist:
 *  - [KEY_JSON]   a JSON array, the format immediately before Room
 *  - [KEY_CSV]    a Set of "resId,power,tough,name,shiny" strings, the original
 *
 * The CSV form is read only when no JSON exists, because the JSON import
 * already superseded it in place.
 */
object LegacyImport {

    const val KEY_JSON = "CATALOGUE_V2"
    const val KEY_CSV = "CATALOGUE"

    private const val F_ART = "art"
    private const val F_POWER = "power"
    private const val F_TOUGH = "tough"
    private const val F_NAME = "name"
    private const val F_SHINY = "shiny"

    /** Every rat found in the old formats, ready to insert. Empty if there are none. */
    fun read(prefs: SharedPreferences): List<RatEntity> {
        val json = prefs.getString(KEY_JSON, null)
        return if (json != null) fromJson(json) else fromCsv(prefs)
    }

    /** True if either legacy format holds anything, used for reporting. */
    fun hasLegacyData(prefs: SharedPreferences): Boolean =
        prefs.getString(KEY_JSON, null) != null ||
            (prefs.getStringSet(KEY_CSV, emptySet())?.isNotEmpty() == true)

    private fun fromJson(raw: String): List<RatEntity> {
        val rats = mutableListOf<RatEntity>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                rats += RatEntity(
                    artKey = obj.optString(F_ART, RatArt.FALLBACK_KEY),
                    name = obj.optString(F_NAME, "Unknown"),
                    power = obj.optInt(F_POWER, 1),
                    toughness = obj.optInt(F_TOUGH, 1),
                    shiny = obj.optBoolean(F_SHINY, false),
                    // Order in the array is the only ordering the old format had;
                    // spacing the timestamps keeps that order stable after import.
                    caughtAt = i.toLong()
                )
            }
        } catch (e: JSONException) {
            // A corrupt blob imports as nothing rather than failing the launch.
        }
        return rats
    }

    /**
     * The original format: resource IDs and comma-joined fields.
     *
     * Resource IDs from an older build cannot be resolved, so those rats keep
     * their stats and fall back to the egg art. Names could themselves contain
     * commas, so the name is everything between the stats and the trailing flag.
     */
    private fun fromCsv(prefs: SharedPreferences): List<RatEntity> {
        val legacy = prefs.getStringSet(KEY_CSV, emptySet()) ?: emptySet()
        val rats = mutableListOf<RatEntity>()

        for ((i, entry) in legacy.withIndex()) {
            val parts = entry.split(",")
            if (parts.size < 5) continue
            val resId = parts[0].toIntOrNull() ?: continue
            val name = parts.subList(3, parts.size - 1).joinToString(",")

            rats += RatEntity(
                artKey = RatArt.keyFor(resId) ?: RatArt.FALLBACK_KEY,
                name = name.ifEmpty { "Unknown" },
                power = parts[1].toIntOrNull() ?: 1,
                toughness = parts[2].toIntOrNull() ?: 1,
                shiny = parts.last().toBoolean(),
                caughtAt = i.toLong()
            )
        }
        return rats
    }
}
