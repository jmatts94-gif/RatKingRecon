package io.github.jmatts94.ratkingrecon

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryActivity : AppCompatActivity() {

    private var isExpeditionActive = false
    private var deployedRatId: Long = -1L
    private var expeditionEndTime: Long = 0L

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * The star marker, sized to sit beside text.
     *
     * The vector is a 24dp icon, which swamps an 11sp card label, so the bounds
     * are set explicitly rather than taken from the drawable.
     */
    private fun starIcon(sizeDp: Int): Drawable? {
        val icon = ContextCompat.getDrawable(this, R.drawable.ic_star) ?: return null
        val size = dp(sizeDp)
        icon.setBounds(0, 0, size, size)
        return icon
    }

    override fun onResume() {
        super.onResume()
        // This runs every time you open the Binder!
        updateCollectionProgress()
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

        // --- SORTING LOGIC ---
        findViewById<Button>(R.id.btnAll).setOnClickListener { renderGrid("ALL") }
        findViewById<Button>(R.id.btnPower).setOnClickListener { renderGrid("POWER") }
        findViewById<Button>(R.id.btnShiny).setOnClickListener { renderGrid("SHINY") }

        // Initial draw
        renderGrid("ALL")
    }

    /**
     * Burns 5 Scrap and the two weakest rats to mint one stronger mutant.
     *
     * Works on list positions rather than values, so two identical rats still
     * count as two separate pieces of fodder.
     */
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

            // --- THE RARITY ENGINE (Booster Pack Logic) ---
            val diceRoll = (1..100).random()
            val mutantArt: String = when {
                diceRoll <= 5 -> listOf(
                    "forman_pic", "foundry_pic", "blaze_pic", "glowtail_pic", "beacon_pic"
                ).random()

                diceRoll <= 30 -> listOf(
                    "wrencher_pic", "welder_pic", "rivet_pic", "cogtail_pic", "anchor_pic", "flux_pic"
                ).random()

                else -> listOf("bolt_pic", "boop_pic", "sooty_pic").random()
            }

            val mutant = RatEntity(
                artKey = mutantArt,
                power = maxOf(parents[0].power, parents[1].power) + 1,
                toughness = maxOf(parents[0].toughness, parents[1].toughness) + 1,
                name = "Spliced Mutant",
                shiny = (1..5).random() == 1,
                isSpliced = true
            )

            withContext(Dispatchers.IO) { dao.splice(parents, mutant) }
            sharedPreferences.edit().putInt(GameEngine.KEY_SCRAP, scrap - 5).apply()

            Toast.makeText(this@GalleryActivity, getString(R.string.toast_fusion_complete), Toast.LENGTH_SHORT).show()
            recreate()
        }
    }

    private fun renderGrid(filter: String) {
        val petGrid = findViewById<GridLayout>(R.id.petGrid)
        petGrid.removeAllViews() // Wipe the grid clean

        // Sorting and filtering happen in SQL rather than in memory.
        lifecycleScope.launch {
            val filteredList = withContext(Dispatchers.IO) {
                val dao = RatRepository.dao(this@GalleryActivity)
                when (filter) {
                    "POWER" -> dao.byPowerDesc()
                    "SHINY" -> dao.shinyOnly()
                    else -> dao.all()
                }
            }
            populateGrid(petGrid, filteredList)
        }
    }

    /**
     * The Shop frame currently equipped, as a stroke colour and width.
     *
     * Null when none is on, in which case the card keeps the border set in
     * item_rat_card.xml. Purely a border swap - no card data is touched.
     */
    private fun equippedFrame(): Pair<Int, Int>? =
        when (ShopEffects.equippedCosmetic(RatRepository.prefs(this))) {
            Shop.FRAME_BRASS -> R.color.amber_dark to dp(3)
            Shop.FRAME_EMBER -> R.color.terracotta to dp(3)
            else -> null
        }

    private fun populateGrid(petGrid: GridLayout, pets: List<RatEntity>) {
        val layoutInflater = LayoutInflater.from(this)
        val frame = equippedFrame()

        for (pet in pets) {
            val cardView = layoutInflater.inflate(R.layout.item_rat_card, petGrid, false)

            frame?.let { (colorRes, width) ->
                (cardView as com.google.android.material.card.MaterialCardView).apply {
                    strokeColor = ContextCompat.getColor(this@GalleryActivity, colorRes)
                    strokeWidth = width
                }
            }
            val cardImage = cardView.findViewById<ImageView>(R.id.cardImage)
            val cardName = cardView.findViewById<TextView>(R.id.cardName)
            val cardPower = cardView.findViewById<TextView>(R.id.cardPower)
            val cardToughness = cardView.findViewById<TextView>(R.id.cardToughness)

            // The stat icons live in item_rat_card.xml, so these are bare numbers.
            cardImage.setImageResource(pet.imageRes)
            cardPower.text = pet.power.toString()
            cardToughness.text = pet.toughness.toString()

            cardName.text = pet.name
            if (pet.shiny) {
                cardName.setTextColor(ContextCompat.getColor(this, R.color.shiny_gold))
                cardName.setCompoundDrawablesRelative(starIcon(14), null, null, null)
                cardName.compoundDrawablePadding = dp(3)
            } else {
                cardName.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                cardName.setCompoundDrawablesRelative(null, null, null, null)
            }

            cardView.setOnClickListener { showEnlargedRat(pet) }

            petGrid.addView(cardView)
        }
    }

    private fun showEnlargedRat(pet: RatEntity) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_enlarged_rat)

        // 1. Hook up the UI Elements
        val enlargedImage = dialog.findViewById<ImageView>(R.id.enlargedRatImage)
        val nameText = dialog.findViewById<TextView>(R.id.enlargedRatName)
        val powerText = dialog.findViewById<TextView>(R.id.enlargedRatPower)
        val toughnessText = dialog.findViewById<TextView>(R.id.enlargedRatToughness)
        val deployButton = dialog.findViewById<Button>(R.id.deployScrapyardButton)
        val closeButton = dialog.findViewById<Button>(R.id.closeEnlargedButton)

        // 2. Set the Visuals and Stats
        enlargedImage.setImageResource(pet.imageRes)
        powerText.text = pet.power.toString()
        toughnessText.text = pet.toughness.toString()

        nameText.text = pet.name
        if (pet.shiny) {
            nameText.setTextColor(ContextCompat.getColor(this, R.color.shiny_gold))
            nameText.setCompoundDrawablesRelative(starIcon(22), null, null, null)
            nameText.compoundDrawablePadding = dp(6)
        } else {
            nameText.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            nameText.setCompoundDrawablesRelative(null, null, null, null)
        }

        // 3. --- THE DISPATCH LOGIC ---
        deployButton.setOnClickListener {
            val prefs = getSharedPreferences("SaveData", Context.MODE_PRIVATE)

            // Block them if a rat is already out there!
            if (prefs.getBoolean(ShopEffects.KEY_EXPEDITION_ACTIVE, false)) {
                Toast.makeText(this@GalleryActivity, getString(R.string.toast_rat_already_out), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Set the timer for 4 hours from right now
            val msToAdd = 4 * 60 * 60 * 1000L
            val endTime = System.currentTimeMillis() + msToAdd

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
