package com.example.ratkingrecon

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

class GalleryActivity : AppCompatActivity() {

    private var isExpeditionActive = false
    private var deployedRatKey: String? = null
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
        val sharedPreferences = getSharedPreferences("SaveData", Context.MODE_PRIVATE)

        val rosterKeys = Roster.all.map { it.artKey }.toSet()
        val found = Vault.load(sharedPreferences)
            .map { it.artKey }
            .filter { it in rosterKeys }
            .distinct()
            .size

        findViewById<TextView>(R.id.collectionText).text =
            getString(R.string.collection_progress, found, Roster.all.size)

        findViewById<ProgressBar>(R.id.collectionProgress).apply {
            max = Roster.all.size
            progress = found
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
        isExpeditionActive = sharedPreferences.getBoolean("EXPEDITION_ACTIVE", false)
        if (isExpeditionActive) {
            deployedRatKey = sharedPreferences.getString("DEPLOYED_RAT_KEY", null)
            expeditionEndTime = sharedPreferences.getLong("EXPEDITION_END_TIME", 0L)
        }

        // --- THE SPLICING VAT LOGIC ---
        // Colours now come from the layout so they stay in step with the palette.
        val spliceButton = findViewById<Button>(R.id.spliceButton)

        // Restore the Mutagen tooltip
        findViewById<Button>(R.id.mutagenButton).setOnLongClickListener {
            Toast.makeText(this, "Tinkerer's Serum: Guarantees high Power & Toughness (6-10)!", Toast.LENGTH_SHORT).show()
            true // The 'true' tells Android we successfully handled the long press
        }

        // Restore the Shiny Polish tooltip
        findViewById<Button>(R.id.shinyButton).setOnLongClickListener {
            Toast.makeText(this, "Gleam-in-a-Bottle: Guarantees a Shiny variant!", Toast.LENGTH_SHORT).show()
            true
        }

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
    private fun spliceWeakestPair(sharedPreferences: SharedPreferences) {
        val scrap = sharedPreferences.getInt("SCRAP", 0)
        val cards = Vault.load(sharedPreferences)

        if (scrap < 5 || cards.size < 2) {
            Toast.makeText(this, "Need 5 Scrap and at least 2 rats!", Toast.LENGTH_SHORT).show()
            return
        }

        val weakestFirst = cards.indices.sortedBy { cards[it].score }
        val parent1 = cards[weakestFirst[0]]
        val parent2 = cards[weakestFirst[1]]

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

        val mutant = RatCard(
            artKey = mutantArt,
            power = maxOf(parent1.power, parent2.power) + 1,
            toughness = maxOf(parent1.toughness, parent2.toughness) + 1,
            name = "Spliced Mutant",
            shiny = (1..5).random() == 1
        )

        // Drop the higher index first so the lower one does not shift under us.
        listOf(weakestFirst[0], weakestFirst[1]).sortedDescending().forEach { cards.removeAt(it) }
        cards.add(mutant)

        Vault.save(sharedPreferences, cards)
        sharedPreferences.edit().putInt("SCRAP", scrap - 5).apply()

        Toast.makeText(this, "Fusion Complete! Mutant Created.", Toast.LENGTH_SHORT).show()
        recreate()
    }

    private fun renderGrid(filter: String) {
        val petGrid = findViewById<GridLayout>(R.id.petGrid)
        petGrid.removeAllViews() // Wipe the grid clean

        val sharedPreferences = getSharedPreferences("SaveData", Context.MODE_PRIVATE)
        val allPets = Vault.load(sharedPreferences)

        // Apply filters
        val filteredList = when (filter) {
            "POWER" -> allPets.sortedByDescending { it.power }
            "SHINY" -> allPets.filter { it.shiny }
            else -> allPets
        }

        val layoutInflater = LayoutInflater.from(this)
        for (pet in filteredList) {
            val cardView = layoutInflater.inflate(R.layout.item_rat_card, petGrid, false)
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

    private fun showEnlargedRat(pet: RatCard) {
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
            if (prefs.getBoolean("EXPEDITION_ACTIVE", false)) {
                Toast.makeText(this@GalleryActivity, "You already have a rat in the Scrapyard!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Set the timer for 4 hours from right now
            val msToAdd = 4 * 60 * 60 * 1000L
            val endTime = System.currentTimeMillis() + msToAdd

            // Save it to the exact same file the Home Screen checks
            prefs.edit()
                .putBoolean("EXPEDITION_ACTIVE", true)
                .putString("DEPLOYED_RAT_KEY", pet.artKey) // Remembering who we sent
                .putLong("EXPEDITION_END_TIME", endTime)
                .apply()

            Toast.makeText(this@GalleryActivity, "${pet.name} deployed! Check the Home Screen later.", Toast.LENGTH_LONG).show()
            dialog.dismiss()
        }

        // 4. Closing the pop-up
        closeButton.setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.enlargedLayout).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }
}
