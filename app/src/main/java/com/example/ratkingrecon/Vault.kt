package com.example.ratkingrecon

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Stable names for every piece of rat art.
 *
 * Drawable resource IDs are regenerated on every build, so they must NEVER be
 * written to disk - a save made yesterday would point at a different picture
 * today. Saves store the key; the ID is looked up at render time.
 */
object RatArt {

    /** Used when a saved key no longer matches any drawable. */
    const val FALLBACK_KEY = "newwegg_pic"

    val byKey: Map<String, Int> = mapOf(
        "anchor_pic" to R.drawable.anchor_pic,
        "beacon_pic" to R.drawable.beacon_pic,
        "blaze_pic" to R.drawable.blaze_pic,
        "boiler_pic" to R.drawable.boiler_pic,
        "bolt_pic" to R.drawable.bolt_pic,
        "boop_pic" to R.drawable.boop_pic,
        "brasscap_pic" to R.drawable.brasscap_pic,
        "canal_pic" to R.drawable.canal_pic,
        "chimney_pic" to R.drawable.chimney_pic,
        "clinker_pic" to R.drawable.clinker_pic,
        "cogtail_pic" to R.drawable.cogtail_pic,
        "coppernose_pic" to R.drawable.coppernose_pic,
        "dockrat_pic" to R.drawable.dockrat_pic,
        "flux_pic" to R.drawable.flux_pic,
        "forman_pic" to R.drawable.forman_pic,
        "foundry_pic" to R.drawable.foundry_pic,
        "gasket_pic" to R.drawable.gasket_pic,
        "gauge_pic" to R.drawable.gauge_pic,
        "glowtail_pic" to R.drawable.glowtail_pic,
        "greasepaw_pic" to R.drawable.greasepaw_pic,
        "hopper_pic" to R.drawable.hopper_pic,
        "latch_pic" to R.drawable.latch_pic,
        "magneto_pic" to R.drawable.magneto_pic,
        "miller_pic" to R.drawable.miller_pic,
        "moptail_pic" to R.drawable.moptail_pic,
        "newwegg_pic" to R.drawable.newwegg_pic,
        "nutkin_pic" to R.drawable.nutkin_pic,
        "pipesqueak_pic" to R.drawable.pipesqueak_pic,
        "piston_pic" to R.drawable.piston_pic,
        "rafter_pic" to R.drawable.rafter_pic,
        "relay_pic" to R.drawable.relay_pic,
        "rivet_pic" to R.drawable.rivet_pic,
        "rumbler_pic" to R.drawable.rumbler_pic,
        "rusty_pic" to R.drawable.rusty_pic,
        "scrapper_pic" to R.drawable.scrapper_pic,
        "smokestack_pic" to R.drawable.smokestack_pic,
        "sooty_pic" to R.drawable.sooty_pic,
        "sparkplug_pic" to R.drawable.sparkplug_pic,
        "spindle_pic" to R.drawable.spindle_pic,
        "sprocket_pic" to R.drawable.sprocket_pic,
        "steamy_pic" to R.drawable.steamy_pic,
        "switch_pic" to R.drawable.switch_pic,
        "tinwhisker_pic" to R.drawable.tinwhisker_pic,
        "turbine_pic" to R.drawable.turbine_pic,
        "valve_pic" to R.drawable.valve_pic,
        "welder_pic" to R.drawable.welder_pic,
        "winch_pic" to R.drawable.winch_pic,
        "workshop_pic" to R.drawable.workshop_pic,
        "wrencher_pic" to R.drawable.wrencher_pic
    )

    /** Reverse lookup, used only when upgrading pre-key saves. */
    private val byResId: Map<Int, String> = byKey.entries.associate { it.value to it.key }

    fun resId(key: String): Int = byKey[key] ?: byKey.getValue(FALLBACK_KEY)

    fun keyFor(resId: Int): String? = byResId[resId]
}

/**
 * One rat in the player's binder.
 *
 * This is the saved form - [Rat] is the roster template it was rolled from.
 */
data class RatCard(
    val artKey: String,
    val power: Int,
    val toughness: Int,
    val name: String,
    val shiny: Boolean
) {
    val imageRes: Int get() = RatArt.resId(artKey)

    /** Combined stat line, used to pick splice fodder and rank the binder. */
    val score: Int get() = power + toughness
}

/**
 * Reads and writes the player's collection.
 *
 * Stored as a JSON array under [KEY_CARDS]. The old format was a Set of
 * comma-joined strings, which silently merged identical rats and broke on any
 * pet name containing a comma; both are fixed here.
 */
object Vault {

    private const val KEY_CARDS = "CATALOGUE_V2"
    private const val KEY_LEGACY = "CATALOGUE"

    private const val F_ART = "art"
    private const val F_POWER = "power"
    private const val F_TOUGH = "tough"
    private const val F_NAME = "name"
    private const val F_SHINY = "shiny"

    /** Every rat the player owns, in the order they were collected. */
    fun load(prefs: SharedPreferences): MutableList<RatCard> {
        val raw = prefs.getString(KEY_CARDS, null) ?: return migrateLegacy(prefs)
        return parse(raw)
    }

    fun save(prefs: SharedPreferences, cards: List<RatCard>) {
        val arr = JSONArray()
        for (card in cards) {
            arr.put(JSONObject().apply {
                put(F_ART, card.artKey)
                put(F_POWER, card.power)
                put(F_TOUGH, card.toughness)
                put(F_NAME, card.name)
                put(F_SHINY, card.shiny)
            })
        }
        prefs.edit().putString(KEY_CARDS, arr.toString()).apply()
    }

    /** Appends one rat. Duplicates are kept - two identical rats are two rats. */
    fun add(prefs: SharedPreferences, card: RatCard) {
        val cards = load(prefs)
        cards.add(card)
        save(prefs, cards)
    }

    private fun parse(raw: String): MutableList<RatCard> {
        val cards = mutableListOf<RatCard>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                cards.add(
                    RatCard(
                        artKey = obj.optString(F_ART, RatArt.FALLBACK_KEY),
                        power = obj.optInt(F_POWER, 1),
                        toughness = obj.optInt(F_TOUGH, 1),
                        name = obj.optString(F_NAME, "Unknown"),
                        shiny = obj.optBoolean(F_SHINY, false)
                    )
                )
            }
        } catch (e: JSONException) {
            // A corrupted blob should show an empty binder, not crash on every launch.
        }
        return cards
    }

    /**
     * Upgrades a save written in the old "resId,power,tough,name,shiny" format.
     *
     * Runs once - the rewritten JSON is what gets read from then on. Resource
     * IDs minted by an older build can no longer be resolved, so those cards
     * keep their stats and fall back to the egg art.
     */
    private fun migrateLegacy(prefs: SharedPreferences): MutableList<RatCard> {
        val legacy = prefs.getStringSet(KEY_LEGACY, emptySet()) ?: emptySet()
        val cards = mutableListOf<RatCard>()

        for (entry in legacy) {
            val parts = entry.split(",")
            if (parts.size < 5) continue
            val resId = parts[0].toIntOrNull() ?: continue

            // A pet name may itself have contained commas, so it is everything
            // between the two stat fields and the trailing shiny flag.
            val name = parts.subList(3, parts.size - 1).joinToString(",")

            cards.add(
                RatCard(
                    artKey = RatArt.keyFor(resId) ?: RatArt.FALLBACK_KEY,
                    power = parts[1].toIntOrNull() ?: 1,
                    toughness = parts[2].toIntOrNull() ?: 1,
                    name = name.ifEmpty { "Unknown" },
                    shiny = parts.last().toBoolean()
                )
            )
        }

        save(prefs, cards)
        return cards
    }
}
