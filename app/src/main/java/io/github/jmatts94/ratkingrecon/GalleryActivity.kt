package io.github.jmatts94.ratkingrecon

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryActivity : AppCompatActivity() {

    private var isExpeditionActive = false
    private var deployedRatId: Long = -1L
    private var expeditionEndTime: Long = 0L

    /**
     * Drives the animated Binder frames, if one is equipped.
     *
     * One ticker for the whole screen rather than an animation per card, fed by
     * the adapter as cards attach and detach. See [FrameAnimator].
     */
    private val frameAnimator = FrameAnimator()

    private lateinit var adapter: RatCardAdapter

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        // This runs every time you open the Binder!
        updateCollectionProgress()
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

        spliceButton.setOnClickListener { spliceWeakestPair(sharedPreferences) }

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
        findViewById<Button>(R.id.btnShiny).setOnClickListener { renderGrid("SHINY") }
        findViewById<Button>(R.id.btnRarity).setOnClickListener { renderGrid("RARITY") }
        findViewById<Button>(R.id.btnFactionCodex).setOnClickListener {
            startActivity(android.content.Intent(this, FactionCodexActivity::class.java))
        }

        // Initial draw
        renderGrid("ALL")
    }

    /**
     * Burns 5 Scrap and the two weakest rats to mint one stronger mutant.
     *
     * The delete-and-insert runs inside a Room transaction, so a crash mid-splice
     * cannot consume the parents without producing the mutant. Fodder is chosen
     * by row id, so two identical rats are still two separate pieces of fodder.
     */
    private fun spliceWeakestPair(sharedPreferences: SharedPreferences) {
        lifecycleScope.launch {
            val scrap = sharedPreferences.getInt(GameEngine.KEY_SCRAP, 0)
            val dao = withContext(Dispatchers.IO) { RatRepository.dao(this@GalleryActivity) }
            val parents = withContext(Dispatchers.IO) { dao.weakest(2) }

            if (scrap < 5 || parents.size < 2) {
                Toast.makeText(this@GalleryActivity, getString(R.string.toast_splice_requirements), Toast.LENGTH_SHORT).show()
                return@launch
            }

            // Rolled once and kept: the species this mutant actually is, not
            // the generic "Spliced Mutant" label that used to hide it. isSpliced
            // is still what marks its origin - the name no longer has to.
            val species = Fusion.roll()
            val mutant = RatEntity(
                artKey = species.artKey,
                power = maxOf(parents[0].power, parents[1].power) + 1,
                toughness = maxOf(parents[0].toughness, parents[1].toughness) + 1,
                name = species.name,
                shiny = (1..5).random() == 1,
                isSpliced = true
            )

            withContext(Dispatchers.IO) {
                dao.splice(parents, mutant)
                // A splice consumes two rats and mints one, so the roster shrinks
                // - but the mutant can still be the species that completes the
                // collection, and milestones only ever latch on.
                Milestones.refresh(
                    sharedPreferences,
                    Milestones.readProgress(dao, sharedPreferences)
                )
            }
            sharedPreferences.edit().putInt(GameEngine.KEY_SCRAP, scrap - 5).apply()

            Toast.makeText(this@GalleryActivity, getString(R.string.toast_fusion_complete), Toast.LENGTH_SHORT).show()
            recreate()
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
        lifecycleScope.launch {
            val filteredList = withContext(Dispatchers.IO) {
                val dao = RatRepository.dao(this@GalleryActivity)
                when (filter) {
                    "POWER" -> dao.byPowerDesc()
                    "SHINY" -> dao.shinyOnly()
                    // Rarity is derived from artKey, not a column, so this
                    // can't be a @Query like the others - sorted in Kotlin
                    // instead, rarest first, ties broken by the same combined
                    // stat line the cards themselves rank by.
                    "RARITY" -> dao.all().sortedWith(
                        compareByDescending<RatEntity> { it.gearCount }
                            .thenByDescending { it.effectivePower + it.effectiveToughness }
                            .thenBy { it.id }
                    )
                    else -> dao.all()
                }
            }
            adapter.submitList(filteredList)
            showEmptyState(filteredList.isEmpty(), filter)
        }
    }

    /**
     * Says why the grid is bare, rather than leaving it bare.
     *
     * The two reasons read differently and should: a player who has walked
     * nothing yet needs to be told what fills the Binder, while one who has
     * simply filtered for shinies they do not own needs telling how rare those
     * are. Only SHINY actually filters - POWER is a sort - so an empty grid
     * under any other tab means the collection itself is empty.
     */
    private fun showEmptyState(empty: Boolean, filter: String) {
        val panel = findViewById<View>(R.id.petGridEmpty)
        panel.visibility = if (empty) View.VISIBLE else View.GONE
        if (!empty) return

        val shinyFilter = filter == "SHINY"
        findViewById<TextView>(R.id.petGridEmptyTitle).setText(
            if (shinyFilter) R.string.ledger_empty_shiny_title else R.string.ledger_empty_title
        )
        findViewById<TextView>(R.id.petGridEmptyBody).setText(
            if (shinyFilter) R.string.ledger_empty_shiny_body else R.string.ledger_empty_body
        )
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
        val prefs = RatRepository.prefs(this)
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_enlarged_rat)

        // 1. Hook up the UI Elements
        val enlargedImage = dialog.findViewById<ImageView>(R.id.enlargedRatImage)
        val nameText = dialog.findViewById<TextView>(R.id.enlargedRatName)
        val speciesText = dialog.findViewById<TextView>(R.id.enlargedRatSpecies)
        val factionText = dialog.findViewById<TextView>(R.id.enlargedRatFaction)
        val powerText = dialog.findViewById<TextView>(R.id.enlargedRatPower)
        val toughnessText = dialog.findViewById<TextView>(R.id.enlargedRatToughness)
        val gear1 = dialog.findViewById<ImageView>(R.id.enlargedGear1)
        val gear2 = dialog.findViewById<ImageView>(R.id.enlargedGear2)
        val gear3 = dialog.findViewById<ImageView>(R.id.enlargedGear3)
        val deployButton = dialog.findViewById<Button>(R.id.deployScrapyardButton)
        val battleRatButton = dialog.findViewById<Button>(R.id.battleRatButton)
        val closeButton = dialog.findViewById<Button>(R.id.closeEnlargedButton)

        // 2. Set the Visuals and Stats
        enlargedImage.setImageResource(pet.imageRes)
        powerText.text = pet.effectivePower.toString()
        toughnessText.text = pet.effectiveToughness.toString()
        gear1.visibility = if (pet.gearCount >= 1) View.VISIBLE else View.GONE
        gear2.visibility = if (pet.gearCount >= 2) View.VISIBLE else View.GONE
        gear3.visibility = if (pet.gearCount >= 3) View.VISIBLE else View.GONE

        // Read from artKey rather than trusted from pet.name - the one place
        // on this screen that answers "what species is this" regardless of
        // what the name field happens to say. Faction rides the same lookup,
        // since it is flavor drawn from the same roster entry.
        val species = Roster.all.firstOrNull { it.artKey == pet.artKey }
        speciesText.text = getString(R.string.enlarged_rat_species, species?.name ?: pet.name)
        val factionLine = getString(
            R.string.enlarged_rat_faction,
            species?.faction ?: getString(R.string.enlarged_rat_faction_unknown)
        )
        // What this rat is worth on the Scrap Run, appended so the bonus is a
        // visible reason to deploy this rat rather than a hidden incentive -
        // see TaskBonuses.
        factionText.text = TaskBonuses.descriptionFor(pet.faction)?.let {
            "$factionLine  ·  ${getString(it)}"
        } ?: factionLine

        // The star is part of the name text now, not a compound drawable -
        // Battle Rat stays a drawable at the far end, unaffected.
        val onDuty = BattleRat.isBattleRat(prefs, pet.id)
        nameText.text = if (pet.shiny) getString(R.string.card_name_shiny, pet.name) else pet.name
        nameText.setTextColor(
            ContextCompat.getColor(this, if (pet.shiny) R.color.shiny_gold else R.color.text_primary)
        )
        nameText.setCompoundDrawablesRelative(
            null, null, if (onDuty) CardIcons.battle(this, 22) else null, null
        )
        nameText.compoundDrawablePadding = dp(6)

        // The same button stands a rat down again, the way buying an equipped
        // Binder frame a second time takes it off.
        battleRatButton.text = getString(
            if (onDuty) R.string.btn_clear_battle_rat else R.string.btn_set_battle_rat
        )
        battleRatButton.setOnClickListener {
            val nowOnDuty = BattleRat.toggle(prefs, pet.id)
            Toast.makeText(
                this@GalleryActivity,
                if (nowOnDuty) {
                    getString(R.string.toast_battle_rat_set, pet.name)
                } else {
                    getString(R.string.toast_battle_rat_cleared, pet.name)
                },
                Toast.LENGTH_SHORT
            ).show()
            dialog.dismiss()
            recreate()
        }

        // 3. --- THE DISPATCH LOGIC ---
        deployButton.setOnClickListener {

            // A rat on combat duty is not available for the Scrap Run. That is
            // the cost of the designation, and the reason it is free to change.
            if (BattleRat.isBattleRat(prefs, pet.id)) {
                Toast.makeText(
                    this@GalleryActivity,
                    getString(R.string.toast_battle_rat_busy, pet.name),
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            // Block them if a rat is already out there!
            if (prefs.getBoolean(ShopEffects.KEY_EXPEDITION_ACTIVE, false)) {
                Toast.makeText(this@GalleryActivity, getString(R.string.toast_rat_already_out), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 4 hours from right now, shortened for a Scavenger or skipped
            // outright for a Brawler's instant-complete - see TaskBonuses.
            // Rolled once, here, rather than re-checked on every later look at
            // the tile: the outcome is decided the moment the rat is sent out.
            val msToAdd = 4 * 60 * 60 * 1000L
            val startTime = System.currentTimeMillis()
            val endTime = TaskBonuses.endTimeFor(startTime, msToAdd, pet.faction)

            // Save it to the exact same file the Home Screen checks
            prefs.edit()
                .putBoolean(ShopEffects.KEY_EXPEDITION_ACTIVE, true)
                .putLong("DEPLOYED_RAT_ID", pet.id) // Remembering exactly which rat we sent
                .putLong(ShopEffects.KEY_EXPEDITION_END, endTime)
                .apply()

            Toast.makeText(this@GalleryActivity, getString(R.string.toast_rat_deployed, pet.name), Toast.LENGTH_LONG).show()
            dialog.dismiss()
        }

        // 4. Closing the pop-up
        closeButton.setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.enlargedLayout).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }
}
