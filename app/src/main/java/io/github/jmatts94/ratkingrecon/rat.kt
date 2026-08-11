package io.github.jmatts94.ratkingrecon

/**
 * A species in the master roster - the template a hatched [RatCard] is rolled from.
 *
 * [artKey] is a stable name from [RatArt], not a resource ID, so the same value
 * can be written straight into a save.
 */
data class Rat(
    val artKey: String,
    val name: String,
    val rarity: String
)

/**
 * Every species that can hatch.
 *
 * Lives here rather than in an Activity so the step-tracking service can roll a
 * rat while the app is closed.
 */
object Roster {
    val all: List<Rat> = listOf(
        Rat("flux_pic", "Flux", "Rare"),
        Rat("glowtail_pic", "Glowtail", "Legendary"),
        Rat("anchor_pic", "Anchor", "Rare"),
        Rat("beacon_pic", "Beacon", "Legendary"),
        Rat("bolt_pic", "Bolt", "Common"),
        Rat("sooty_pic", "Sooty", "Common"),
        Rat("boop_pic", "Boop", "Common"),
        Rat("foundry_pic", "Foundry", "Legendary"),
        Rat("wrencher_pic", "Wrencher", "Rare"),
        Rat("welder_pic", "Welder", "Rare"),
        Rat("rivet_pic", "Rivet", "Rare"),
        Rat("cogtail_pic", "Cogtail", "Rare"),
        Rat("forman_pic", "Forman", "Legendary"),
        Rat("blaze_pic", "Blaze", "Legendary"),
        Rat("brasscap_pic", "Brasscap", "common"),
        Rat("gauge_pic", "Gauge", "Common"),
        Rat("boiler_pic", "Boiler", "common"),
        Rat("canal_pic", "Canal", "common"),
        Rat("chimney_pic", "Chimney", "Legendary"),
        Rat("coppernose_pic", "Coppernose", "common"),
        Rat("greasepaw_pic", "Greasepaw", "Common"),
        Rat("hopper_pic", "Hopper", "Rare"),
        Rat("latch_pic", "Latch", "Rare"),
        Rat("magneto_pic", "Magneto", "Common"),
        Rat("moptail_pic", "moptail", "Common"),
        Rat("miller_pic", "Miller", "Rare"),
        Rat("piston_pic", "Piston", "Common"),
        Rat("rafter_pic", "Rafter", "Rare"),
        Rat("winch_pic", "Winch", "Common"),
        Rat("spindle_pic", "Spindle", "Common"),
        Rat("sparkplug_pic", "Sparkplug", "Legendary"),
        Rat("nutkin_pic", "Nutkin", "common")
    )
}
