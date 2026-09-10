package io.github.jmatts94.ratkingrecon

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * Crafts and equips gear - see [GearPieces]/[GearEffects].
 *
 * A screen of its own rather than folded into the Relic Trader, for the same
 * reason the Trader itself is a screen of its own rather than four Shop
 * rows: a gear row's own button carries three states (Craft/Equip/Equipped)
 * a plain exchange never needed, and a recipe can spend more than one relic
 * at once. Reached one tap from the Trader, since both spend the same
 * relics - see [RelicTraderActivity].
 */
class GearWorkshopActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var countRow: LinearLayout

    /** A drawn piece, kept so [refresh] can restate its cost and button without rebuilding the whole list. */
    private class Row(
        val piece: GearPiece,
        val cost: TextView,
        val button: MaterialButton
    )

    private val rows = mutableListOf<Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gear_workshop)
        EdgeToEdge.apply(this)

        prefs = getSharedPreferences("SaveData", Context.MODE_PRIVATE)
        countRow = findViewById(R.id.workshopRelicCountRow)

        findViewById<MaterialButton>(R.id.workshopBackButton).setOnClickListener { finish() }

        buildCounts()
        buildPieces()
    }

    override fun onResume() {
        super.onResume()
        // A Ledger Task or the Scrap Run claimed in another screen can drop
        // a relic while this one is in the back stack - same reason
        // RelicTraderActivity.onResume refreshes.
        refresh()
    }

    /** One pill per relic kind - the same icon-and-count row RelicTraderActivity.buildCounts already draws. */
    private fun buildCounts() {
        for (relic in Relics.ALL) {
            val pill = TextView(this).apply {
                background = ContextCompat.getDrawable(this@GearWorkshopActivity, R.drawable.bg_pill_tan)
                setTextColor(ContextCompat.getColor(this@GearWorkshopActivity, R.color.text_primary))
                gravity = Gravity.CENTER
                textSize = 13f
                setPadding(8, 12, 8, 12)
                tag = relic.id
                setCompoundDrawablesWithIntrinsicBounds(sizedIcon(relic.iconRes, 16), null, null, null)
                compoundDrawablePadding = 6
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = 4; marginEnd = 4 }
            }
            countRow.addView(pill)
        }
    }

    private fun sizedIcon(resId: Int, sizeDp: Int): Drawable? {
        val icon = ContextCompat.getDrawable(this, resId) ?: return null
        val size = (sizeDp * resources.displayMetrics.density).toInt()
        icon.setBounds(0, 0, size, size)
        return icon
    }

    private fun buildPieces() {
        val container = findViewById<LinearLayout>(R.id.workshopPieceContainer)
        val inflater = LayoutInflater.from(this)

        for (piece in GearPieces.all) {
            val view = inflater.inflate(R.layout.view_gear_piece, container, false)

            view.findViewById<ImageView>(R.id.gearPieceIcon).setImageResource(piece.iconRes)
            view.findViewById<TextView>(R.id.gearPieceTitle).setText(piece.nameRes)
            view.findViewById<TextView>(R.id.gearPieceBody).setText(piece.descRes)
            view.findViewById<TextView>(R.id.gearPieceSlot).setText(
                if (piece.slot == GearSlot.TOOL) R.string.gear_workshop_slot_tool else R.string.gear_workshop_slot_trinket
            )

            val cost = view.findViewById<TextView>(R.id.gearPieceCost)
            val button = view.findViewById<MaterialButton>(R.id.gearPieceButton)
            button.setOnClickListener { onPieceButtonTapped(piece) }

            rows += Row(piece, cost, button)
            container.addView(view)
        }
    }

    // ---- crafting and equipping -----------------------------------------------

    private fun onPieceButtonTapped(piece: GearPiece) {
        if (!GearEffects.owns(prefs, piece.id)) {
            craft(piece)
        } else {
            // Equipping the one already worn in this slot stands it down
            // again - the same toggle a Binder frame already does.
            GearEffects.toggleEquipped(prefs, piece)
            refresh()
        }
    }

    private fun craft(piece: GearPiece) {
        val refusal = GearEffects.craft(prefs, piece)
        if (refusal != null) {
            toast(describe(refusal))
            refresh()
            return
        }

        toast(getString(R.string.gear_workshop_craft_done, getString(piece.nameRes)))
        refresh()
    }

    private fun describe(refusal: GearCraftRefusal): String = when (refusal) {
        is GearCraftRefusal.NotEnough -> getString(
            R.string.gear_workshop_not_enough,
            refusal.needed,
            getString(refusal.relic.nameRes),
            refusal.held
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ---- drawing -----------------------------------------------------------------

    private fun refresh() {
        for (relic in Relics.ALL) {
            val pill = countRow.findViewWithTag<TextView>(relic.id) ?: continue
            val held = Relics.countOf(prefs, relic)
            pill.text = held.toString()
            pill.contentDescription = getString(R.string.trader_count_pill_description, getString(relic.nameRes), held)
        }

        for (row in rows) {
            val owned = GearEffects.owns(prefs, row.piece.id)
            val equipped = GearEffects.isEquipped(prefs, row.piece.id)

            row.cost.visibility = if (owned) View.GONE else View.VISIBLE
            if (!owned) {
                row.cost.text = row.piece.craftCost.entries.joinToString(getString(R.string.gear_workshop_cost_join)) { (relicId, needed) ->
                    val relic = Relics.byId(relicId)
                    getString(R.string.gear_workshop_cost_line, needed, relic?.let { getString(it.nameRes) } ?: relicId)
                }
            }

            row.button.text = getString(
                when {
                    !owned -> R.string.gear_workshop_craft
                    equipped -> R.string.gear_workshop_equipped
                    else -> R.string.gear_workshop_equip
                }
            )

            // Craft is disabled once the recipe cannot be afforded; Equip/
            // Equipped are always tappable, the same way a Binder frame's
            // own Equip button always is once owned.
            val canCraft = owned || GearEffects.craftRefusalFor(prefs, row.piece) == null
            row.button.isEnabled = canCraft

            row.button.backgroundTintList = ContextCompat.getColorStateList(
                this,
                when {
                    equipped -> R.color.amber_dark
                    canCraft -> R.color.amber
                    else -> R.color.disabled_fill
                }
            )
            row.button.setTextColor(
                ContextCompat.getColor(this, if (canCraft) R.color.text_primary else R.color.text_muted)
            )
        }
    }
}
