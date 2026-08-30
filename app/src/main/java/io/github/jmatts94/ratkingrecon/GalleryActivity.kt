package io.github.jmatts94.ratkingrecon

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryActivity : AppCompatActivity() {

    private var isExpeditionActive = false
    private var deployedRatId: Long = -1L
    private var expeditionEndTime: Long = 0L

    /** Which sort/filter tab is showing, so onResume can redraw the same one. */
    private var currentFilter = "ALL"

    /**
     * Drives the animated Binder frames, if one is equipped.
     *
     * One ticker for the whole screen rather than an animation per card, fed by
     * the adapter as cards attach and detach. See [FrameAnimator].
     */
    private val frameAnimator = FrameAnimator()

    private lateinit var adapter: RatCardAdapter

    override fun onResume() {
        super.onResume()
        // This runs every time you open the Binder - including coming back
        // from the Splicing screen, which is the only other screen that can
        // change the roster out from under this one.
        updateCollectionProgress()
        renderGrid(currentFilter)
        frameAnimator.start()
    }

    /** Nothing should be turning behind a dialog, another screen or a dark display. */
    override fun onPause() {
        super.onPause()
        frameAnimator.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        frameAnimator.stop()
        frameAnimator.clear()
    }

    /**
     * Shows how much of the roster has been discovered.
     *
     * Counts distinct species rather than cards held: duplicates and spliced
     * mutants are real rats but not new discoveries, and counting them could
     * put the total above the roster size. Anything whose art is not in the
     * roster - a card recovered from a pre-key save, say - is skipped for the
     * same reason. The denominator comes from [Roster] so it tracks the roster
     * growing.
     */
    private fun updateCollectionProgress() {
        val rosterKeys = Roster.all.map { it.artKey }

        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                RatRepository.dao(this@GalleryActivity).distinctSpeciesFound(rosterKeys)
            }

            findViewById<TextView>(R.id.collectionText).text =
                getString(R.string.collection_progress, found, rosterKeys.size)

            findViewById<ProgressBar>(R.id.collectionProgress).apply {
                max = rosterKeys.size
                progress = found
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        EdgeToEdge.apply(this)

        val sharedPreferences = getSharedPreferences("SaveData", Context.MODE_PRIVATE)

        // Make sure the ID matches what you named the button in your XML!
        findViewById<Button>(R.id.returnToBaseButton).setOnClickListener {
            finish() // This instantly closes the Binder and returns you to the Home screen
        }

        // Load the Idle Expedition (So this one doesn't get amnesia either!)
        isExpeditionActive = sharedPreferences.getBoolean(ShopEffects.KEY_EXPEDITION_ACTIVE, false)
        if (isExpeditionActive) {
            deployedRatId = sharedPreferences.getLong("DEPLOYED_RAT_ID", -1L)
            expeditionEndTime = sharedPreferences.getLong(ShopEffects.KEY_EXPEDITION_END, 0L)
        }

        // --- THE FUSION POT LOGIC ---
        // Colours now come from the layout so they stay in step with the palette.
        val spliceButton = findViewById<Button>(R.id.spliceButton)

        spliceButton.setOnClickListener {
            startActivity(Intent(this, SplicingActivity::class.java))
        }

        // The frame is fixed for the life of this screen: equipping one happens
        // in the Shop, and coming back here builds the adapter again.
        adapter = RatCardAdapter(
            prefs = RatRepository.prefs(this),
            equippedFrame = equippedFrame(),
            animator = frameAnimator,
            onCardClick = { showEnlargedRat(it) }
        )
        findViewById<RecyclerView>(R.id.petGrid).adapter = adapter

        // --- SORTING LOGIC ---
        findViewById<Button>(R.id.btnAll).setOnClickListener { renderGrid("ALL") }
        findViewById<Button>(R.id.btnPower).setOnClickListener { renderGrid("POWER") }
        findViewById<Button>(R.id.btnFaction).setOnClickListener { renderGrid("FACTION") }
        findViewById<Button>(R.id.btnRarity).setOnClickListener { renderGrid("RARITY") }
        findViewById<Button>(R.id.btnFactionCodex).setOnClickListener {
            startActivity(android.content.Intent(this, FactionCodexActivity::class.java))
        }
    }

    /**
     * Swaps the list behind the grid.
     *
     * Sorting and filtering happen in SQL rather than in memory, and the result
     * is handed to the adapter, which diffs it against what is already shown -
     * so changing filter moves the cards that moved rather than rebuilding all
     * of them.
     */
    private fun renderGrid(filter: String) {
        currentFilter = filter
        lifecycleScope.launch {
            val filteredList = withContext(Dispatchers.IO) {
                val dao = RatRepository.dao(this@GalleryActivity)
                when (filter) {
                    "POWER" -> dao.byPowerDesc()
                    // Rarity and faction are both derived from artKey, not a
                    // column, so neither can be a @Query like the others -
                    // sorted in Kotlin instead, ties broken by the same
                    // combined stat line the cards themselves rank by.
                    "RARITY" -> dao.all().sortedWith(
                        compareByDescending<RatEntity> { it.gearCount }
                            .thenByDescending { it.effectivePower + it.effectiveToughness }
                            .thenBy { it.id }
                    )
                    // Grouped in the same fixed order the Ledger Task picker
                    // presents the five factions in, rather than
                    // alphabetically - one order for "all five factions" is
                    // enough. A rat whose artKey the roster no longer
                    // recognises sorts last rather than crashing the compare.
                    "FACTION" -> dao.all().sortedWith(
                        compareBy<RatEntity> { TaskBonuses.FACTIONS.indexOf(it.faction).takeIf { i -> i >= 0 } ?: TaskBonuses.FACTIONS.size }
                            .thenByDescending { it.effectivePower + it.effectiveToughness }
                            .thenBy { it.id }
                    )
                    else -> dao.all()
                }
            }
            adapter.submitList(filteredList)
            showEmptyState(filteredList.isEmpty())
        }
    }

    /**
     * Says why the grid is bare, rather than leaving it bare.
     *
     * Every filter here is a sort or a re-grouping, never an exclusion, so an
     * empty grid under any tab means the collection itself is empty - one
     * message covers all of them.
     */
    private fun showEmptyState(empty: Boolean) {
        val panel = findViewById<View>(R.id.petGridEmpty)
        panel.visibility = if (empty) View.VISIBLE else View.GONE
        if (!empty) return

        findViewById<TextView>(R.id.petGridEmptyTitle).setText(R.string.ledger_empty_title)
        findViewById<TextView>(R.id.petGridEmptyBody).setText(R.string.ledger_empty_body)
    }

    /**
     * The frame currently equipped, or null when none is.
     *
     * Looked up in [Frames] rather than matched here, so a frame added to the
     * catalogue turns up on the cards without this needing to hear about it.
     */
    private fun equippedFrame(): CardFrame? =
        Frames.byId(ShopEffects.equippedCosmetic(RatRepository.prefs(this)))

    private fun showEnlargedRat(pet: RatEntity) {
        EnlargedRatDialog.show(
            activity = this,
            pet = pet,
            prefs = RatRepository.prefs(this),
            onBattleRatToggled = { recreate() }
        )
    }
}
