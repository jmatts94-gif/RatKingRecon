package com.example.ratkingrecon

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
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
 * The Shop.
 *
 * Nothing here knows what a particular item is: the screen walks
 * [Shop.categories], draws a section per category and a row per item, and a
 * purchase just writes that item's flag. Growing the shop therefore means
 * editing Shop.kt, not this file.
 */
class ShopActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var scrapText: TextView

    /** Every buy button, kept so [refresh] can restate prices and armed state. */
    private val buyButtons = mutableListOf<Pair<ShopItem, MaterialButton>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shop)

        // Same store the rest of the app writes to; the boost flags are useless
        // to GameEngine if they land anywhere else.
        prefs = getSharedPreferences("SaveData", Context.MODE_PRIVATE)
        scrapText = findViewById(R.id.shopScrapText)

        findViewById<MaterialButton>(R.id.shopBackButton).setOnClickListener { finish() }

        buildCatalogue()
    }

    override fun onResume() {
        super.onResume()
        // Steps banked while this screen was open can hatch a rat, which spends
        // a boost and pays out Scrap, so re-read rather than trust what we drew.
        refresh()
    }

    /** Inflates a section per category, and a row per item inside it. */
    private fun buildCatalogue() {
        val container = findViewById<LinearLayout>(R.id.shopCategoryContainer)
        val inflater = LayoutInflater.from(this)

        for (category in Shop.categories) {
            val section = inflater.inflate(R.layout.view_shop_category, container, false)
            section.findViewById<TextView>(R.id.categoryTitle).setText(category.titleRes)
            section.findViewById<TextView>(R.id.categorySubtitle).setText(category.subtitleRes)

            val rows = section.findViewById<LinearLayout>(R.id.categoryItems)
            if (category.items.isEmpty()) {
                val empty = inflater.inflate(R.layout.view_shop_empty, rows, false)
                empty.findViewById<TextView>(R.id.emptyBody).setText(category.emptyBodyRes)
                rows.addView(empty)
            } else {
                category.items.forEach { rows.addView(buildRow(inflater, rows, it)) }
            }

            container.addView(section)
        }
    }

    private fun buildRow(inflater: LayoutInflater, parent: ViewGroup, item: ShopItem): View {
        val row = inflater.inflate(R.layout.view_shop_item, parent, false)

        row.findViewById<ImageView>(R.id.itemIcon).setImageResource(item.iconRes)
        row.findViewById<TextView>(R.id.itemName).setText(item.nameRes)
        row.findViewById<TextView>(R.id.itemBody).setText(item.bodyRes)

        val buy = row.findViewById<MaterialButton>(R.id.itemBuyButton)
        buy.setOnClickListener { purchase(item) }
        buyButtons += item to buy

        return row
    }

    /**
     * Buys [item], keeping the rule the home screen enforced: pay the price, set
     * the flag, and refuse a second purchase while the first is still unspent.
     *
     * Setting the flag is the whole transaction. The effect is applied - and the
     * flag cleared - by GameEngine on the next hatch, so buying a boost can
     * never change a rat that has already been collected.
     */
    private fun purchase(item: ShopItem) {
        // Already armed. The button is disabled in this state, so this only
        // guards against a tap that raced a hatch.
        if (prefs.getBoolean(item.prefKey, false)) return

        val scrap = prefs.getInt(GameEngine.KEY_SCRAP, 0)
        if (scrap < item.price) {
            Toast.makeText(this, R.string.shop_too_poor, Toast.LENGTH_SHORT).show()
            return
        }

        prefs.edit()
            .putInt(GameEngine.KEY_SCRAP, scrap - item.price)
            .putBoolean(item.prefKey, true)
            .apply()

        Toast.makeText(
            this,
            getString(R.string.shop_activated, getString(item.nameRes)),
            Toast.LENGTH_SHORT
        ).show()

        refresh()
    }

    /** Repaints the purse and every buy button from the save. */
    private fun refresh() {
        scrapText.text = prefs.getInt(GameEngine.KEY_SCRAP, 0).toString()

        for ((item, button) in buyButtons) {
            val armed = prefs.getBoolean(item.prefKey, false)

            button.isEnabled = !armed
            button.text =
                if (armed) getString(R.string.shop_active)
                else getString(R.string.shop_price, item.price)

            // Tint by hand: the palette is hardcoded warm, so Material's own
            // disabled colours would drop a grey button into a cream screen.
            button.backgroundTintList = ContextCompat.getColorStateList(
                this,
                if (armed) R.color.disabled_fill else R.color.amber
            )
            button.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (armed) R.color.text_muted else R.color.text_primary
                )
            )
        }
    }
}
