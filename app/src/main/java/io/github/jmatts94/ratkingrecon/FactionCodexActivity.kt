package io.github.jmatts94.ratkingrecon

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt

/**
 * Every faction's mechanical effects, gathered on one screen.
 *
 * Nothing here is a second copy of a number - every line is built from the
 * same constant that actually drives the effect ([TaskBonuses], [BossMoves],
 * [FactionSpecials]), so retuning one of those cannot silently leave this
 * screen advertising the old terms. [Roster]'s faction constants are read
 * straight through as the display name too - the rest of the app already
 * treats them as player-facing text.
 *
 * No splice-effect row yet, deliberately - the Fusion Pot's per-faction
 * splice bonuses live in a feature that has not shipped in this build. Add
 * one the same way the other three are built, straight off that feature's
 * own constants, once it has.
 */
class FactionCodexActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_faction_codex)

        findViewById<MaterialButton>(R.id.factionCodexBackButton).setOnClickListener { finish() }

        val container = findViewById<LinearLayout>(R.id.factionCodexContainer)
        val inflater = LayoutInflater.from(this)

        // The same fixed order the Ledger Task rat picker already presents
        // factions in - see TaskBonuses.FACTIONS.
        for (faction in TaskBonuses.FACTIONS) {
            val view = inflater.inflate(R.layout.item_faction_codex, container, false)

            view.findViewById<TextView>(R.id.codexFactionName).text = faction
            view.findViewById<TextView>(R.id.codexFactionFlavor).text = getString(flavorResFor(faction))
            view.findViewById<TextView>(R.id.codexTaskLine).text =
                TaskBonuses.descriptionFor(faction)?.let { getString(it) }.orEmpty()
            view.findViewById<TextView>(R.id.codexBossLine).text = bossLineFor(faction)
            view.findViewById<TextView>(R.id.codexSpecialLine).text = specialLineFor(faction)

            container.addView(view)
        }
    }

    private fun flavorResFor(faction: String): Int = when (faction) {
        Roster.SMUGGLERS -> R.string.codex_flavor_smugglers
        Roster.SCAVENGERS -> R.string.codex_flavor_scavengers
        Roster.TINKERERS -> R.string.codex_flavor_tinkerers
        Roster.BRAWLERS -> R.string.codex_flavor_brawlers
        else -> R.string.codex_flavor_foundry_born
    }

    /** The boss matchup line, straight off [BossMoves]'s own reverse lookups. */
    private fun bossLineFor(faction: String): String {
        if (faction == Roster.FOUNDRY_BORN) return getString(R.string.codex_boss_matchup_foundry_born)

        val strongAgainst = BossMoves.bossWeakTo(faction)?.let { bossNameFor(it) }
        val weakAgainst = BossMoves.bossThatTargets(faction)?.let { bossNameFor(it) }
        return getString(
            R.string.codex_boss_matchup,
            strongAgainst ?: return "",
            weakAgainst ?: return ""
        )
    }

    private fun bossNameFor(bossId: String): String? = Bosses.byId(bossId)?.nameRes?.let { getString(it) }

    /** The Special line, straight off [FactionSpecials]'s own numbers. */
    private fun specialLineFor(faction: String): String {
        val multiplier = FactionSpecials.multiplierFor(faction).let {
            if (it == it.toInt().toDouble()) it.toInt().toString() else it.toString()
        }
        return when (faction) {
            Roster.BRAWLERS -> getString(R.string.codex_special_brawler, multiplier)
            Roster.FOUNDRY_BORN -> getString(
                R.string.codex_special_foundry_born,
                multiplier,
                (FactionSpecials.FOUNDRY_BORN_DOT_FRACTION * 100).roundToInt(),
                FactionSpecials.FOUNDRY_BORN_DOT_ROUNDS
            )
            Roster.TINKERERS -> getString(
                R.string.codex_special_tinkerer,
                multiplier,
                (FactionSpecials.TINKERER_BLOCK_CHANCE * 100).roundToInt()
            )
            Roster.SCAVENGERS -> getString(
                R.string.codex_special_scavenger,
                multiplier,
                (FactionSpecials.SCAVENGER_REFUND_CHANCE * 100).roundToInt()
            )
            else -> getString(
                R.string.codex_special_smuggler,
                multiplier,
                (FactionSpecials.SMUGGLER_LIFESTEAL_FRACTION * 100).roundToInt()
            )
        }
    }
}
