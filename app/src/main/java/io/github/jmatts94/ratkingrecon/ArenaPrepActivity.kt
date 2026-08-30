package io.github.jmatts94.ratkingrecon

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The last stop before an Arena run actually starts: shows the chosen
 * champion, what combat items are held for it to lean on mid-fight, and the
 * flat Scrap toll - paid here, once, rather than per fight. "Enter the
 * Arena" is the only action that commits anything; everything else here is
 * just looking at what is about to be spent.
 */
class ArenaPrepActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RAT_ID = "arena_rat_id"
    }

    private lateinit var prefs: SharedPreferences
    private var ratId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_arena_prep)
        EdgeToEdge.apply(this)

        prefs = RatRepository.prefs(this)
        ratId = intent.getLongExtra(EXTRA_RAT_ID, -1L)

        findViewById<MaterialButton>(R.id.arenaPrepShopButton).setOnClickListener {
            startActivity(Intent(this, ShopActivity::class.java))
        }
        findViewById<View>(R.id.arenaPrepLeaveButton).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.arenaPrepEnterButton).setOnClickListener { enter() }

        loadRat()
    }

    override fun onResume() {
        super.onResume()
        // Held items and the Scrap balance can both have changed on a trip to
        // the Shop and back, so both are re-read every time this screen is
        // the one in front rather than only once in onCreate.
        bindItemRows()
        bindCost()
    }

    private fun loadRat() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                RatRepository.dao(this@ArenaPrepActivity).byId(ratId)
            }
            if (loaded == null) {
                Toast.makeText(this@ArenaPrepActivity, R.string.arena_prep_rat_gone, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            bindRat(loaded)
        }
    }

    private fun bindRat(pet: RatEntity) {
        findViewById<ImageView>(R.id.arenaPrepRatImage).setImageResource(pet.imageRes)
        findViewById<TextView>(R.id.arenaPrepRatName).text = pet.name

        val species = Roster.all.firstOrNull { it.artKey == pet.artKey }
        findViewById<TextView>(R.id.arenaPrepRatFaction).text = getString(
            R.string.enlarged_rat_faction,
            species?.faction ?: getString(R.string.enlarged_rat_faction_unknown)
        )
        findViewById<TextView>(R.id.arenaPrepRatPower).text = pet.effectivePower.toString()
        findViewById<TextView>(R.id.arenaPrepRatToughness).text = pet.effectiveToughness.toString()
    }

    /** The same four combat items, and the same "Name (N held)" wording, the in-battle Items panel uses. */
    private fun bindItemRows() {
        val container = findViewById<LinearLayout>(R.id.arenaPrepItemsContainer)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        val entries = listOf(
            Triple(R.drawable.ic_flask, R.string.shop_name_hp_tonic, ShopEffects.KEY_HP_TONIC),
            Triple(R.drawable.ic_toughness, R.string.shop_name_reinforced_plating, ShopEffects.KEY_REINFORCED_PLATING),
            Triple(R.drawable.ic_settings, R.string.shop_name_corrosive_charge, ShopEffects.KEY_CORROSIVE_CHARGE),
            Triple(R.drawable.ic_sparkle, R.string.shop_name_cleanse, ShopEffects.KEY_CLEANSE)
        )

        entries.forEach { (iconRes, nameRes, key) ->
            val row = inflater.inflate(R.layout.item_arena_held_item, container, false)
            row.findViewById<ImageView>(R.id.arenaHeldItemIcon).setImageResource(iconRes)
            row.findViewById<TextView>(R.id.arenaHeldItemLabel).text = getString(
                R.string.battle_item_row, getString(nameRes), ShopEffects.charges(prefs, key)
            )
            container.addView(row)
        }
    }

    private fun bindCost() {
        findViewById<TextView>(R.id.arenaPrepCostText).text =
            getString(R.string.arena_prep_cost, ShopEffects.arenaEntryCost(prefs))
        findViewById<TextView>(R.id.arenaPrepBalanceText).text =
            getString(R.string.arena_prep_balance, GameEngine.scrapOf(prefs))
    }

    private fun enter() {
        val cost = ShopEffects.arenaEntryCost(prefs)
        val scrap = GameEngine.scrapOf(prefs)
        if (scrap < cost) {
            Toast.makeText(this, R.string.shop_too_poor, Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            // A fight already pending is never overwritten - Arena.raiseFirstFight
            // takes the same caution Bosses.startBanked does - but a plain toast
            // and nothing else stranded the player with no way back to it: unlike
            // an ordinary walking encounter, an Arena fight never raises a
            // notification to reopen BattleActivity from, and unlike a boss,
            // MainActivity never resumes it either. Send them straight back into
            // the fight already raised instead - the same one this same button
            // would otherwise refuse to replace - at no extra Scrap cost.
            if (Encounter.isPending(prefs)) {
                Toast.makeText(this@ArenaPrepActivity, R.string.arena_prep_busy, Toast.LENGTH_SHORT).show()
                startActivity(Intent(this@ArenaPrepActivity, BattleActivity::class.java))
                finish()
                return@launch
            }

            val dao = RatRepository.dao(this@ArenaPrepActivity)
            val encounter = withContext(Dispatchers.IO) {
                Arena.raiseFirstFight(dao, prefs, GameEngine.levelOf(prefs), ratId)
            }

            if (encounter == null) {
                Toast.makeText(this@ArenaPrepActivity, R.string.arena_prep_rat_gone, Toast.LENGTH_SHORT).show()
                return@launch
            }

            // The voucher is spent here, not merely priced in bindCost - a
            // fight that never got raised (the rat vanished between opening
            // this screen and tapping Enter, above) must not burn it for
            // nothing. Guarded on the cost it already produced, the same way
            // the Shop's own Masterwork discount spends its voucher.
            if (cost == 0) ShopEffects.spendCharge(prefs, ShopEffects.KEY_ARENA_ENTRY_VOUCHER)
            prefs.edit().putInt(GameEngine.KEY_SCRAP, scrap - cost).apply()
            ArenaRun.begin(prefs, ratId)
            startActivity(Intent(this@ArenaPrepActivity, BattleActivity::class.java))
            finish()
        }
    }
}
