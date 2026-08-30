package io.github.jmatts94.ratkingrecon

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picks the one rat an Arena run is built around - no mid-run swapping, so
 * this is the only chance to choose.
 *
 * Reuses the same card grid [SplicingActivity]'s picker established -
 * [RatCardAdapter] with [isDisabled]/[isSelected] lambdas - single-select
 * instead of two, and a narrower exclusion than Splicing's: a knocked-out
 * rat is the only one turned away here, the same eligibility
 * [RatDao.strongestAvailable] already applies elsewhere. Being on Battle duty
 * or out on an Expedition does not disqualify a rat from the Arena.
 */
class ArenaSelectActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: RatCardAdapter

    private var roster: List<RatEntity> = emptyList()
    private var excludedIds: Set<Long> = emptySet()
    private var selectedId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = RatRepository.prefs(this)

        // A run already has its champion locked in - reaching this screen
        // mid-run (the back button, a recreated task) must never re-offer a
        // choice that would let it be swapped. Straight back into the fight
        // already in progress instead, the same recovery ArenaPrepActivity's
        // own busy case takes.
        if (ArenaRun.isActive(prefs)) {
            startActivity(Intent(this, BattleActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_arena_select)
        EdgeToEdge.apply(this)

        adapter = RatCardAdapter(
            prefs = prefs,
            equippedFrame = Frames.byId(ShopEffects.equippedCosmetic(prefs)),
            animator = FrameAnimator(),
            onCardClick = { onPetTapped(it) },
            isDisabled = { it.id in excludedIds },
            isSelected = { it.id == selectedId }
        )
        findViewById<RecyclerView>(R.id.arenaSelectGrid).adapter = adapter

        findViewById<MaterialButton>(R.id.arenaSelectCancelButton).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.arenaSelectConfirmButton).setOnClickListener { confirm() }

        load()
    }

    private fun onPetTapped(pet: RatEntity) {
        selectedId = if (selectedId == pet.id) null else pet.id
        refresh()
    }

    private fun load() {
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            // Strongest first, the same ordering the Ledger's own "Sort Power"
            // already gives - a run's champion is a stat choice, not a
            // collection to browse newest-first the way the plain roster is.
            //
            // byPowerDesc() orders by the stored power column, but the card
            // grid shows effectivePower (stored + rarity bonus) - see
            // RatCardAdapter.onBindViewHolder. A Rare/Legendary rat's stored
            // power can trail a Common's while its displayed number leads it,
            // so the DB order alone can put a bigger on-screen number behind
            // a smaller one. Re-sorting here by the same value the cards show
            // keeps the two in sync; sortedByDescending is stable, so ties
            // keep the query's own id-ascending order.
            val loadedRoster = withContext(Dispatchers.IO) {
                RatRepository.dao(this@ArenaSelectActivity).byPowerDesc()
            }.sortedByDescending { it.effectivePower }
            roster = loadedRoster
            excludedIds = loadedRoster.filter { it.isRecovering(now) }.map { it.id }.toSet()
            adapter.submitList(roster)
            refresh()
        }
    }

    private fun refresh() {
        adapter.notifyDataSetChanged()

        findViewById<View>(R.id.arenaSelectEmpty).visibility =
            if (roster.isEmpty()) View.VISIBLE else View.GONE

        val eligibleCount = roster.count { it.id !in excludedIds }
        val statusText = findViewById<TextView>(R.id.arenaSelectStatusText)
        statusText.text = when {
            eligibleCount == 0 -> getString(R.string.arena_select_none_eligible)
            selectedId == null -> getString(R.string.arena_select_pick_one)
            else -> ""
        }

        val canConfirm = selectedId != null
        val confirmButton = findViewById<MaterialButton>(R.id.arenaSelectConfirmButton)
        confirmButton.isEnabled = canConfirm
        confirmButton.backgroundTintList = ContextCompat.getColorStateList(
            this, if (canConfirm) R.color.amber else R.color.disabled_fill
        )
        confirmButton.setTextColor(
            ContextCompat.getColor(this, if (canConfirm) R.color.text_primary else R.color.text_muted)
        )
    }

    private fun confirm() {
        val ratId = selectedId ?: return
        startActivity(
            Intent(this, ArenaPrepActivity::class.java).putExtra(ArenaPrepActivity.EXTRA_RAT_ID, ratId)
        )
    }
}
