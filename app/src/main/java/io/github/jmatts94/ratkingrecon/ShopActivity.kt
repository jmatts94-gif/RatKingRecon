package io.github.jmatts94.ratkingrecon

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
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Shop.
 *
 * Nothing here knows what a particular item is: the screen walks
 * [Shop.categories] and switches on each item's [ShopEffect], so every kind of
 * purchase has one implementation and growing the shop is an edit to Shop.kt.
 */
class ShopActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var scrapText: TextView

    /** A drawn row, kept so [refresh] can restate its price, state and count. */
    private class Row(
        val item: ShopItem,
        val category: ShopCategory,
        val button: MaterialButton,
        val body: TextView
    )

    private val rows = mutableListOf<Row>()

    /** Guards the one purchase that is not instantaneous, against a double tap. */
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shop)
        EdgeToEdge.apply(this)

        // Same store the rest of the app writes to; a purchase is useless if the
        // flag or charge it sets lands anywhere else.
        prefs = getSharedPreferences("SaveData", Context.MODE_PRIVATE)
        scrapText = findViewById(R.id.shopScrapText)

        findViewById<MaterialButton>(R.id.shopBackButton).setOnClickListener { finish() }

        buildCatalogue()
    }

    override fun onResume() {
        super.onResume()
        // Steps banked while this screen was open can hatch a rat or settle a
        // fight, both of which spend things bought here.
        refresh()
    }

    private fun buildCatalogue() {
        val container = findViewById<LinearLayout>(R.id.shopCategoryContainer)
        val inflater = LayoutInflater.from(this)

        for (category in Shop.categories) {
            val section = inflater.inflate(R.layout.view_shop_category, container, false)
            section.findViewById<TextView>(R.id.categoryTitle).setText(category.titleRes)
            section.findViewById<TextView>(R.id.categorySubtitle).setText(category.subtitleRes)

            val list = section.findViewById<LinearLayout>(R.id.categoryItems)
            when {
                category.items.isNotEmpty() ->
                    category.items.forEach { list.addView(buildRow(inflater, list, it, category)) }

                // A section with nothing in it and nothing to say draws its
                // heading alone, which is what "built but empty" looks like.
                category.emptyBodyRes != 0 -> {
                    val empty = inflater.inflate(R.layout.view_shop_empty, list, false)
                    empty.findViewById<TextView>(R.id.emptyBody).setText(category.emptyBodyRes)
                    list.addView(empty)
                }
            }

            container.addView(section)
        }
    }

    private fun buildRow(
        inflater: LayoutInflater,
        parent: ViewGroup,
        item: ShopItem,
        category: ShopCategory
    ): View {
        val row = inflater.inflate(R.layout.view_shop_item, parent, false)
        row.background = ContextCompat.getDrawable(this, category.cardBackgroundRes)

        val icon = row.findViewById<ImageView>(R.id.itemIcon)
        icon.setImageResource(item.iconRes)
        // Power Surge and the Golden Wrench share their icons (ic_power, ic_sparkle)
        // with unrelated uses elsewhere - the rat card's Power stat, Shiny Polish,
        // the PULSE frame - so the teal has to be a tint on this one ImageView
        // rather than baked into the drawable, or it would bleed into all of them.
        icon.imageTintList = if (isCombatBuffIcon(item)) {
            ContextCompat.getColorStateList(this, R.color.teal_fill)
        } else {
            null
        }

        row.findViewById<TextView>(R.id.itemName).setText(item.nameRes)

        val body = row.findViewById<TextView>(R.id.itemBody)
        // The long-press explainer the Hatchery has always had, kept now that
        // the button lives here rather than on the home screen.
        if (item.tooltipBodyRes != 0) {
            Tooltip.attachTo(row, item.tooltipTitleRes, item.tooltipBodyRes)
        }

        val buy = row.findViewById<MaterialButton>(R.id.itemBuyButton)
        buy.setOnClickListener { purchase(item) }

        rows += Row(item, category, buy, body)
        return row
    }

    /** True for the two Combat items whose icon means something else everywhere else it's drawn. */
    private fun isCombatBuffIcon(item: ShopItem): Boolean {
        val effect = item.effect
        return effect is ShopEffect.Flag &&
            (effect.key == ShopEffects.KEY_POWER_SURGE || effect.key == ShopEffects.KEY_GOLDEN_WRENCH)
    }

    // ---- buying --------------------------------------------------------------

    /**
     * Runs [item]'s effect and charges for it.
     *
     * Scrap only ever leaves after the effect has been accepted, so a purchase
     * that cannot land - a Quick Return with no expedition out, a boost already
     * armed - costs nothing.
     */
    private fun purchase(item: ShopItem) {
        if (busy) return

        val effect = item.effect

        if (item.unlockLevel > 0 && GameEngine.levelOf(prefs) < item.unlockLevel) {
            toast(getString(R.string.shop_locked_toast, item.unlockLevel))
            return
        }

        if (effect is ShopEffect.ComingSoon) {
            toast(getString(R.string.shop_not_yet, getString(item.nameRes)))
            return
        }

        // Settled before the Scrap check: this row costs nothing, it opens a
        // door. onResume redraws when the player comes back, so a voucher
        // traded for in there is priced in the moment they return.
        if (effect is ShopEffect.Screen) {
            when (effect.id) {
                Shop.SCREEN_RELIC_TRADER ->
                    startActivity(android.content.Intent(this, RelicTraderActivity::class.java))
            }
            return
        }

        // Equipping something already owned is free, so it settles before the
        // affordability check rather than after it.
        if (effect is ShopEffect.Cosmetic && ShopEffects.ownsCosmetic(prefs, effect.id)) {
            ShopEffects.toggleEquipped(prefs, effect.id)
            refresh()
            return
        }

        // Belt-and-braces alongside the disabled "Arena Reward Only" label
        // above: that keeps an ordinary tap from ever reaching here, but this
        // is the one place Scrap actually leaves, and it must refuse on its
        // own rather than trust a button state it does not control.
        if (effect is ShopEffect.Cosmetic && Frames.byId(effect.id)?.sellable == false) return

        if (effect is ShopEffect.Flag && prefs.getBoolean(effect.key, false)) return

        // Power Surge and the Golden Wrench are mutually exclusive: only one can
        // ride a fight, so selling the second would be charging for nothing.
        if (effect is ShopEffect.Flag && ShopEffects.conflictsWithArmedBuff(prefs, effect.key)) {
            toast(getString(R.string.shop_buff_conflict))
            return
        }

        // A combat item's cap, if it has one - Revive Tokens and the
        // Masterwork voucher pass this with cap == null and are unaffected.
        if (effect is ShopEffect.Charge && effect.cap != null &&
            ShopEffects.charges(prefs, effect.key) >= effect.cap
        ) {
            toast(getString(R.string.shop_item_at_cap))
            return
        }

        val price = effectivePrice(item)
        val scrap = prefs.getInt(GameEngine.KEY_SCRAP, 0)
        if (scrap < price) {
            toast(getString(R.string.shop_too_poor))
            return
        }

        when (effect) {
            is ShopEffect.Flag -> prefs.edit().putBoolean(effect.key, true).apply()
            is ShopEffect.Charge -> ShopEffects.addCharge(prefs, effect.key)
            is ShopEffect.Cosmetic -> ShopEffects.grantCosmetic(prefs, effect.id)

            is ShopEffect.Action -> when (effect.id) {
                // Hatching writes to Room, so it finishes on a background
                // thread and pays for itself there.
                Shop.ACTION_MASTERWORK -> {
                    masterworkHatch(item)
                    return
                }

                Shop.ACTION_QUICK_RETURN ->
                    if (!ShopEffects.quickReturn(prefs)) {
                        toast(getString(R.string.shop_no_expedition))
                        return
                    }

                else -> return
            }

            ShopEffect.ComingSoon -> return
            is ShopEffect.Screen -> return
        }

        prefs.edit().putInt(GameEngine.KEY_SCRAP, scrap - price).apply()
        toast(getString(R.string.shop_activated, getString(item.nameRes)))
        refresh()
    }

    /**
     * Buys a hatch outright.
     *
     * The rat is minted and stored before any Scrap is taken, so a failure
     * leaves the player with their money. Scrap is re-read at the moment of the
     * write rather than reused from before the insert, so a bounty landing
     * mid-hatch is not overwritten.
     */
    private fun masterworkHatch(item: ShopItem) {
        busy = true
        refresh()

        lifecycleScope.launch {
            val hatched = withContext(Dispatchers.IO) {
                val dao = RatRepository.dao(this@ShopActivity)
                val minted = Masterwork.roll()
                val stored = minted.copy(id = dao.insert(minted))

                // The only place a Masterwork pull is observable: the rat it
                // mints carries no column marking it as one.
                Milestones.recordMasterworkPull(prefs)
                Milestones.refresh(prefs, Milestones.readProgress(dao, prefs))

                // "Any hatch method" includes the bought one, and so does the
                // payout it can finish.
                DailyQuest.record(prefs, QuestType.HATCH)?.let {
                    DailyAlerts.postQuestPaid(applicationContext, it)
                }
                GameEngine.recordHatch(prefs)
                stored
            }

            // Priced here rather than before the insert, for the same reason the
            // Scrap is re-read: this runs across a suspension point, and the
            // voucher could not be spent for a hatch that never happened.
            val price = effectivePrice(item)
            val discounted = price < item.price

            val scrap = prefs.getInt(GameEngine.KEY_SCRAP, 0)
            prefs.edit()
                .putInt(GameEngine.KEY_SCRAP, (scrap - price).coerceAtLeast(0))
                .apply()

            // Only now, with the rat minted and the Scrap taken.
            if (discounted) ShopEffects.spendCharge(prefs, ShopEffects.KEY_MASTERWORK_VOUCHER)

            busy = false
            toast(getString(R.string.shop_masterwork_done, hatched.name))
            refresh()
        }
    }

    /**
     * What [item] costs right now.
     *
     * Only the Hatchery ever differs from its catalogue price, and only while a
     * Relic Trader voucher is held. Routed through one function because the
     * price is read in three places - the button label, the affordability check
     * and the deduction - and two of them disagreeing is how a player gets
     * charged a price the button never showed.
     */
    private fun effectivePrice(item: ShopItem): Int {
        val effect = item.effect
        val isMasterwork = effect is ShopEffect.Action && effect.id == Shop.ACTION_MASTERWORK
        return if (isMasterwork) ShopEffects.masterworkPrice(prefs) else item.price
    }

    // ---- drawing -------------------------------------------------------------

    /** Repaints the purse and every row from the save. */
    private fun refresh() {
        scrapText.text = prefs.getInt(GameEngine.KEY_SCRAP, 0).toString()

        for (row in rows) {
            val label = labelFor(row.item, row.category)
            row.button.text = label.text
            row.button.isEnabled = label.enabled

            row.body.text = bodyFor(row.item)

            // Tinted by hand: the palette is hardcoded warm, so Material's own
            // disabled colours would drop a grey button into a cream screen.
            row.button.backgroundTintList = ContextCompat.getColorStateList(
                this,
                if (label.enabled) label.fill else R.color.disabled_fill
            )
            row.button.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (label.enabled) label.textColor else R.color.text_muted
                )
            )
        }
    }

    private class Label(
        val text: String,
        val enabled: Boolean,
        val fill: Int = R.color.amber,
        val textColor: Int = R.color.text_primary
    )

    private fun labelFor(item: ShopItem, category: ShopCategory): Label {
        if (item.unlockLevel > 0 && GameEngine.levelOf(prefs) < item.unlockLevel) {
            return Label(getString(R.string.shop_locked, item.unlockLevel), enabled = false)
        }
        return labelForUnlocked(item, category)
    }

    private fun labelForUnlocked(item: ShopItem, category: ShopCategory): Label = when (val effect = item.effect) {
        is ShopEffect.ComingSoon ->
            Label(getString(R.string.shop_coming_soon_btn), enabled = false)

        is ShopEffect.Screen ->
            Label(getString(R.string.shop_open), enabled = true, fill = R.color.amber)

        is ShopEffect.Flag ->
            if (prefs.getBoolean(effect.key, false)) {
                Label(getString(R.string.shop_active), enabled = false)
            } else {
                Label(price(item), enabled = true, fill = category.accentFillRes, textColor = category.accentTextRes)
            }

        is ShopEffect.Cosmetic -> when {
            // Checked before ownership: a frame taken off sale after the
            // player already owns it - none does yet, but the rule should
            // not depend on that - still has to fall through to Equip/Equipped
            // below rather than freeze on a label that no longer applies.
            !ShopEffects.ownsCosmetic(prefs, effect.id) && Frames.byId(effect.id)?.sellable == false ->
                Label(getString(R.string.shop_arena_reward_only), enabled = false)
            !ShopEffects.ownsCosmetic(prefs, effect.id) -> Label(price(item), enabled = true)
            ShopEffects.equippedCosmetic(prefs) == effect.id ->
                Label(getString(R.string.shop_equipped), enabled = true, fill = R.color.amber_dark)
            else -> Label(getString(R.string.shop_equip), enabled = true, fill = R.color.card_white)
        }

        is ShopEffect.Charge ->
            if (effect.cap != null && ShopEffects.charges(prefs, effect.key) >= effect.cap) {
                Label(getString(R.string.shop_item_max_held), enabled = false)
            } else {
                Label(price(item), enabled = true, fill = category.accentFillRes, textColor = category.accentTextRes)
            }

        // Actions are repeatable, so they always show their price.
        else -> Label(price(item), enabled = true, fill = category.accentFillRes, textColor = category.accentTextRes)
    }

    private fun price(item: ShopItem): String =
        getString(R.string.shop_price, effectivePrice(item))

    /** The item's description, plus how many are held when that is the point of it. */
    private fun bodyFor(item: ShopItem): String {
        val text = getString(item.bodyRes, *item.bodyArgs.toTypedArray())
        val effect = item.effect

        // Says why the Hatchery is cheaper than its advertised price, rather
        // than leaving a discount to be noticed or not.
        if (effect is ShopEffect.Action && effect.id == Shop.ACTION_MASTERWORK) {
            val vouchers = ShopEffects.charges(prefs, ShopEffects.KEY_MASTERWORK_VOUCHER)
            return if (vouchers > 0) {
                getString(R.string.shop_voucher_applied, text, ShopEffects.MASTERWORK_VOUCHER_VALUE)
            } else {
                text
            }
        }

        if (effect !is ShopEffect.Charge) return text

        val held = ShopEffects.charges(prefs, effect.key)
        return if (held > 0) getString(R.string.shop_held, text, held) else text
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
