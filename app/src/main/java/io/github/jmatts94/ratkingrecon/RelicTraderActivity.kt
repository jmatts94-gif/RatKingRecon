package io.github.jmatts94.ratkingrecon

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/**
 * The Relic Trader.
 *
 * A screen of its own rather than four rows in the Shop, because the Shop is
 * denominated in Scrap from the affordability check to the button label, and one
 * of these exchanges has to ask the player which reward they want.
 *
 * The rules live in [RelicTrader]; this draws them and collects the choices.
 */
class RelicTraderActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var totalText: TextView
    private lateinit var countRow: LinearLayout

    /** A drawn exchange, kept so [refresh] can restate its cost and button. */
    private class Row(
        val exchange: RelicExchange,
        val button: MaterialButton,
        val cost: TextView
    )

    private val rows = mutableListOf<Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_relic_trader)

        prefs = getSharedPreferences("SaveData", Context.MODE_PRIVATE)
        totalText = findViewById(R.id.traderTotalText)
        countRow = findViewById(R.id.relicCountRow)

        findViewById<MaterialButton>(R.id.traderBackButton).setOnClickListener { finish() }

        buildCounts()
        buildExchanges()
    }

    override fun onResume() {
        super.onResume()
        // A Ledger Task claimed in another screen can drop a relic while this
        // one is in the back stack.
        refresh()
    }

    /** One pill per relic kind, so the player can see what they are holding. */
    private fun buildCounts() {
        for (relic in Relics.ALL) {
            val pill = TextView(this).apply {
                background = ContextCompat.getDrawable(this@RelicTraderActivity, R.drawable.bg_pill_tan)
                setTextColor(ContextCompat.getColor(this@RelicTraderActivity, R.color.text_primary))
                gravity = Gravity.CENTER
                textSize = 13f
                setPadding(8, 12, 8, 12)
                tag = relic.id
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = 4; marginEnd = 4 }
            }
            countRow.addView(pill)
        }
    }

    private fun buildExchanges() {
        val container = findViewById<LinearLayout>(R.id.exchangeContainer)
        val inflater = LayoutInflater.from(this)

        for (exchange in RelicTrader.exchanges) {
            val view = inflater.inflate(R.layout.view_relic_exchange, container, false)

            view.findViewById<TextView>(R.id.exchangeTitle).setText(exchange.titleRes)
            view.findViewById<TextView>(R.id.exchangeBody).text =
                getString(exchange.bodyRes, *exchange.bodyArgs.toTypedArray())

            val button = view.findViewById<MaterialButton>(R.id.exchangeButton)
            button.setOnClickListener { attempt(exchange) }

            rows += Row(exchange, button, view.findViewById(R.id.exchangeCost))
            container.addView(view)
        }
    }

    // ---- trading -------------------------------------------------------------

    /**
     * Starts an exchange, asking first when the reward comes in more than one
     * kind.
     *
     * The refusal is checked here so the player is told why nothing happened,
     * and checked again inside [RelicTrader.trade] - a dialog can sit open long
     * enough for a fight to arm a buff underneath it.
     */
    private fun attempt(exchange: RelicExchange) {
        RelicTrader.refusalFor(prefs, exchange)?.let {
            toast(describe(it, exchange))
            return
        }

        when (exchange.reward) {
            is RelicReward.ItemVoucher -> chooseItemVoucher(exchange)
            else -> settle(exchange, choiceId = null)
        }
    }

    private fun chooseItemVoucher(exchange: RelicExchange) {
        val items = RelicTrader.availableItemChoices(prefs)
        val labels = items.map { getString(itemNameRes(it)) }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.trade_pick_item)
            .setItems(labels) { _, which -> settle(exchange, items[which].name) }
            .setNegativeButton(R.string.trade_cancel, null)
            .show()
    }

    private fun itemNameRes(item: BattleItem): Int = when (item) {
        BattleItem.HP_TONIC -> R.string.shop_name_hp_tonic
        BattleItem.CORROSIVE_CHARGE -> R.string.shop_name_corrosive_charge
        BattleItem.REINFORCED_PLATING -> R.string.shop_name_reinforced_plating
        BattleItem.CLEANSE -> R.string.shop_name_cleanse
    }

    private fun settle(exchange: RelicExchange, choiceId: String?) {
        val refusal = RelicTrader.trade(prefs, exchange, choiceId)
        if (refusal != null) {
            toast(describe(refusal, exchange))
            refresh()
            return
        }

        toast(getString(R.string.trade_done, getString(exchange.titleRes)))
        refresh()
    }

    private fun describe(refusal: RelicRefusal, exchange: RelicExchange): String = when (refusal) {
        is RelicRefusal.NotEnough -> getString(
            R.string.trade_not_enough,
            refusal.needed,
            getString(exchange.relic.nameRes),
            refusal.held
        )

        RelicRefusal.EveryItemFull -> getString(R.string.trade_items_all_full)
    }

    // ---- drawing -------------------------------------------------------------

    private fun refresh() {
        val total = Relics.total(prefs)
        totalText.text = getString(R.string.trader_total, total)

        // Says where relics come from, for a player who has arrived before
        // holding any. The exchanges stay on screen underneath, so the Trader
        // still shows what it would trade for.
        findViewById<View>(R.id.traderEmpty).visibility =
            if (total == 0) View.VISIBLE else View.GONE

        for (relic in Relics.ALL) {
            val pill = countRow.findViewWithTag<TextView>(relic.id) ?: continue
            pill.text = getString(
                R.string.trader_count_pill,
                getString(relic.nameRes),
                Relics.countOf(prefs, relic)
            )
        }

        for (row in rows) {
            val held = Relics.countOf(prefs, row.exchange.relic)
            row.cost.text = getString(
                R.string.trade_cost,
                row.exchange.cost,
                getString(row.exchange.relic.nameRes),
                held
            )

            val canTrade = RelicTrader.canTrade(prefs, row.exchange)
            row.button.text = getString(
                if (canTrade) R.string.trade_action else R.string.trade_unavailable
            )
            row.button.isEnabled = canTrade

            // Tinted by hand for the same reason the Shop does it: Material's
            // disabled grey lands badly on a cream screen.
            row.button.backgroundTintList = ContextCompat.getColorStateList(
                this,
                if (canTrade) R.color.amber else R.color.disabled_fill
            )
            row.button.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (canTrade) R.color.text_primary else R.color.text_muted
                )
            )
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
