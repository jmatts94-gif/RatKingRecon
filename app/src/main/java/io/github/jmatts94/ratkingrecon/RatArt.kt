package io.github.jmatts94.ratkingrecon

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
        "cogtail_pic" to R.drawable.cogtail_pic,
        "coppernose_pic" to R.drawable.coppernose_pic,
        "flux_pic" to R.drawable.flux_pic,
        "forman_pic" to R.drawable.forman_pic,
        "foundry_pic" to R.drawable.foundry_pic,
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
        "piston_pic" to R.drawable.piston_pic,
        "rafter_pic" to R.drawable.rafter_pic,
        "rivet_pic" to R.drawable.rivet_pic,
        "sooty_pic" to R.drawable.sooty_pic,
        "sparkplug_pic" to R.drawable.sparkplug_pic,
        "spindle_pic" to R.drawable.spindle_pic,
        "timetail_pic" to R.drawable.timetail_pic,
        "welder_pic" to R.drawable.welder_pic,
        "winch_pic" to R.drawable.winch_pic,
        "wrencher_pic" to R.drawable.wrencher_pic
    )

    /** Reverse lookup, used only when upgrading pre-key saves. */
    private val byResId: Map<Int, String> = byKey.entries.associate { it.value to it.key }

    fun resId(key: String): Int = byKey[key] ?: byKey.getValue(FALLBACK_KEY)

    fun keyFor(resId: Int): String? = byResId[resId]
}
