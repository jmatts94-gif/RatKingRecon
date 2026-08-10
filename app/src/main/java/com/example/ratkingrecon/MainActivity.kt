package com.example.ratkingrecon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private companion object {
        /** Level at which the consumables on the home screen become available. */
        const val UNLOCK_LEVEL = 5
        const val KEY_UNLOCK_SEEN = "UNLOCK_POPUP_SEEN"

        /** How long a newly hatched rat stays on screen before the next egg appears. */
        const val REVEAL_MS = 5_000L
    }

    private var bountyReward = 0
    private var isBountyActive = false
    private var bountyTargetSteps = 0f // We use a float because the sensor uses floats
    private var bountyEndTime: Long = 0L // Long is used for big time numbers
    private var playerLevel = 1
    private var maxExp = 50

    private var isExpeditionActive = false
    private var deployedRatKey: String? = null // null means "no rat selected"
    private var expeditionEndTime: Long = 0L

    private fun startActiveBounty(stepsRequired: Float, minutesAllowed: Int, rewardAmount: Int) {
        isBountyActive = true
        bountyReward = rewardAmount // Save the specific reward!

        bountyTargetSteps = GameEngine.totalStepsOf(sharedPreferences) + stepsRequired
        bountyEndTime = System.currentTimeMillis() + (minutesAllowed * 60 * 1000)

        with(sharedPreferences.edit()) {
            putBoolean("BOUNTY_ACTIVE", isBountyActive)
            putFloat("BOUNTY_TARGET", bountyTargetSteps)
            putLong("BOUNTY_END_TIME", bountyEndTime)
            putInt("BOUNTY_REWARD", bountyReward) // Save it to the phone!
            apply()
        }

        Toast.makeText(this, "Contract Accepted! You have $minutesAllowed mins.", Toast.LENGTH_LONG).show()
    }
    private lateinit var buyPolishButton: Button
    private lateinit var buyPremiumButton: Button
    private lateinit var shopLayout: View
    private lateinit var missionButton: Button
    private lateinit var sharedPreferences: SharedPreferences

    private lateinit var petImage: ImageView
    private lateinit var scrapText: TextView
    private lateinit var playerLevelText: TextView
    private lateinit var stepCountText: TextView

    // Live counter shown inside the Contract Board dialog while it is open.
    private var bountyStepText: TextView? = null

    /**
     * While this timestamp is in the future the freshly hatched rat stays on
     * screen instead of snapping back to the egg. Walking keeps earning EXP
     * throughout - this only affects what the image shows.
     */
    private var revealUntil = 0L

    private var currentExp = 0

    // Cumulative reading when this launch began, so the header can show a
    // session total rather than the device's since-boot count.
    private var launchBaselineSteps = 0f
    private var stepsSinceLaunch = 0

    private fun deployRat(artKey: String, hoursToScavenge: Int) {
        isExpeditionActive = true
        deployedRatKey = artKey

        // Math: hours * 60 mins * 60 secs * 1000 milliseconds
        val msToAdd = hoursToScavenge * 60 * 60 * 1000L
        expeditionEndTime = System.currentTimeMillis() + msToAdd

        // Save to SharedPreferences so it survives the app closing!
        with(sharedPreferences.edit()) {
            putBoolean("EXPEDITION_ACTIVE", isExpeditionActive)
            putString("DEPLOYED_RAT_KEY", deployedRatKey)
            putLong("EXPEDITION_END_TIME", expeditionEndTime)
            apply()
        }

        Toast.makeText(this, "Rat deployed to the Scrapyard for $hoursToScavenge hours!", Toast.LENGTH_LONG).show()
    }
    private fun checkExpedition() {
        if (!isExpeditionActive) {
            Toast.makeText(this, "No rats currently in the Scrapyard.", Toast.LENGTH_SHORT).show()
            return
        }

        val currentTime = System.currentTimeMillis()

        if (currentTime >= expeditionEndTime) {
            // SCENARIO A: TIME IS UP! (Give Reward)
            isExpeditionActive = false
            sharedPreferences.edit().putBoolean("EXPEDITION_ACTIVE", false).apply()

            // Give a massive payout for waiting (e.g., 200 to 500 Scrap)
            val reward = (200..500).random()
            val currentScrap = sharedPreferences.getInt("SCRAP", 0)
            sharedPreferences.edit().putInt("SCRAP", currentScrap + reward).apply()

            Toast.makeText(this, "Expedition Complete! Your rat brought back $reward Scrap!", Toast.LENGTH_LONG).show()
            updateScreen()
        } else {
            // SCENARIO B: STILL WORKING (Tell them how long is left)
            val timeLeftMillis = expeditionEndTime - currentTime
            val minutesLeft = (timeLeftMillis / (1000 * 60)).toInt()

            Toast.makeText(this, "Rat is still scavenging... $minutesLeft minutes left.", Toast.LENGTH_SHORT).show()
        }
    }
    private fun showBountyBoard() {
        // Block if a mission is already running
        if (isBountyActive) {
            Toast.makeText(this, "You already have an active contract!", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. Create the Custom Dialog
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_bounty_board)

        // Optional: Make the background behind the popup slightly transparent dark
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Hold on to the dialog's step readout so the service's updates can keep
        // it live, and let go of it again once the dialog is gone.
        bountyStepText = dialog.findViewById<TextView>(R.id.bountyStepCountText)
        bountyStepText?.text = stepsSinceLaunch.toString()
        dialog.setOnDismissListener { bountyStepText = null }

        // 2. Roll a fresh offer per tier. Opening the board again re-rolls, so
        //    the names and payouts change each visit.
        bindBounty(dialog, R.id.btnBountyShort, Bounties.SHORT.roll())
        bindBounty(dialog, R.id.btnBountyMedium, Bounties.MEDIUM.roll())
        bindBounty(dialog, R.id.btnBountyLong, Bounties.LONG.roll())

        dialog.findViewById<Button>(R.id.btnCancelBounty).setOnClickListener {
            dialog.dismiss() // Just closes the pop-up without doing anything
        }

        // 3. Show it on screen!
        dialog.show()
    }

    /**
     * Puts a rolled offer on a button, label and payout in step.
     *
     * The reward shown here is the same value handed to [startActiveBounty], so
     * the board can no longer advertise a figure it does not pay.
     */
    private fun bindBounty(dialog: android.app.Dialog, buttonId: Int, offer: BountyOffer) {
        val button = dialog.findViewById<Button>(buttonId)
        button.text = getString(
            R.string.bounty_label,
            offer.name,
            offer.steps.toInt(),
            offer.minutes,
            offer.reward
        )
        button.setOnClickListener {
            startActiveBounty(offer.steps, offer.minutes, offer.reward)
            dialog.dismiss()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Permission Check
        val wanted = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            wanted += android.Manifest.permission.ACTIVITY_RECOGNITION
        }
        // Without this the hatch alert is silently dropped on Android 13+.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            wanted += android.Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }

        // 2. Initialize UI (We do this FIRST so the buttons exist before we click them)
        buyPolishButton = findViewById(R.id.buyPolishButton)
        buyPremiumButton = findViewById(R.id.buyPremiumButton)
        shopLayout = findViewById(R.id.shopLayout)
        missionButton = findViewById(R.id.scavengeMissionsButton)
        petImage = findViewById(R.id.petImage)
        scrapText = findViewById(R.id.scrapText)
        playerLevelText = findViewById(R.id.playerLevelText)
        stepCountText = findViewById(R.id.stepCountText)

        // 3. WAKE UP ROUTINE (Fixed: Now using "SaveData" to match the rest of your app)
        sharedPreferences = getSharedPreferences("SaveData", Context.MODE_PRIVATE)

        playerLevel = sharedPreferences.getInt("PLAYER_LEVEL", 1)
        maxExp = playerLevel * 50

        isBountyActive = sharedPreferences.getBoolean("BOUNTY_ACTIVE", false)
        if (isBountyActive) {
            bountyTargetSteps = sharedPreferences.getFloat("BOUNTY_TARGET", 0f)
            bountyEndTime = sharedPreferences.getLong("BOUNTY_END_TIME", 0L)
            bountyReward = sharedPreferences.getInt("BOUNTY_REWARD", 0)
        }

        // 4. Hand step tracking to the service. Started from here because
        //    Android 12+ refuses foreground-service starts from the background.
        startTrackingIfAllowed()

        // 5. Button Listeners
        //
        // Tinkerer's Serum moved to the future Shop screen, so nothing sets
        // MUTAGEN_ACTIVE any more. GameEngine still honours the flag, so a Shop
        // purchase writing it is all that is needed to re-enable 6-10 rolls.
        buyPolishButton.setOnClickListener {
            val scrap = sharedPreferences.getInt("SCRAP", 0)
            if (scrap >= 5 && !sharedPreferences.getBoolean(GameEngine.KEY_POLISH, false)) {
                sharedPreferences.edit()
                    .putInt("SCRAP", scrap - 5)
                    .putBoolean(GameEngine.KEY_POLISH, true)
                    .apply()
                Toast.makeText(this, "Gleam-in-a-Bottle Activated!", Toast.LENGTH_SHORT).show()
                updateScreen()
            }
        }

        findViewById<Button>(R.id.scavengeMissionsButton).setOnClickListener {
            showBountyBoard()
        }

        findViewById<Button>(R.id.expeditionBoardButton).setOnClickListener {
            startActivity(android.content.Intent(this, MissionActivity::class.java))
        }

        findViewById<Button>(R.id.openGalleryButton).setOnClickListener {
            startActivity(android.content.Intent(this, GalleryActivity::class.java))
        }

        // No ShopActivity yet - this is where Tinkerer's Serum will live.
        findViewById<Button>(R.id.shopButton).setOnClickListener {
            Toast.makeText(this, R.string.shop_coming_soon, Toast.LENGTH_SHORT).show()
        }

        // Placeholder until instant hatching is built. Charges nothing, and the
        // label and tooltip quote no price, so the two cannot contradict.
        buyPremiumButton.setOnClickListener {
            Toast.makeText(this, R.string.hatchery_coming_soon, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.activeExpeditionButton).setOnClickListener {
            checkExpedition()
        }

        findViewById<Button>(R.id.devResetButton).setOnClickListener {
            sharedPreferences.edit().clear().apply()
            playerLevel = 1
            currentExp = 0
            maxExp = 50

            // In-memory state has to go too, or a cleared bounty still blocks
            // the Contract Board until the app is restarted.
            isBountyActive = false
            bountyReward = 0
            bountyTargetSteps = 0f
            bountyEndTime = 0L
            isExpeditionActive = false
            deployedRatKey = null
            expeditionEndTime = 0L

            updateScreen()
            Toast.makeText(this, "Game Reset", Toast.LENGTH_SHORT).show()
        }

        // Long-press explainers. Tooltip is generic, so adding one to any other
        // button later is a single call like these.
        Tooltip.attachTo(buyPremiumButton, R.string.tooltip_hatchery_title, R.string.tooltip_hatchery_body)
        Tooltip.attachTo(buyPolishButton, R.string.tooltip_polish_title, R.string.tooltip_polish_body)

        // Load the rest of the game data
        loadGame()

        // Covers a save that was already past the unlock level before this shipped.
        maybeShowUnlockPopup()
    }

    /**
     * Reveals a rat the service just hatched, while the app happens to be open.
     *
     * The hatch itself already happened in GameEngine and the rat is in the
     * Ledger; this is presentation only. Sound and vibration come from the
     * notification channel, so they fire whether or not this screen is up.
     */
    private fun celebrateHatch(artKey: String, name: String) {
        petImage.setImageResource(RatArt.resId(artKey))
        revealUntil = System.currentTimeMillis() + REVEAL_MS
        Toast.makeText(this, "$name hatched and joined the Ledger!", Toast.LENGTH_LONG).show()
    }

    private fun updateScreen() {
        // The header icons carry the meaning now, so these are bare values.
        scrapText.text = sharedPreferences.getInt("SCRAP", 0).toString()
        playerLevelText.text = "Lvl $playerLevel"
        updateStepDisplays()
        updateShopVisibility()

        // 1. Update the Visual Bar
        val expBar = findViewById<ProgressBar>(R.id.expProgressBar)
        expBar.max = maxExp
        expBar.progress = currentExp.toInt()

        // 2. Update the Text
        val expText = findViewById<TextView>(R.id.expText)
        expText.text = "${currentExp.toInt()} / $maxExp EXP"

        updateEggStage()

        // --- THE NEW DYNAMIC STATUS BAR LOGIC ---
        val activeExpeditionButton = findViewById<Button>(R.id.activeExpeditionButton)

        if (isExpeditionActive) {
            // Rat is out! Show the green status bar.
            activeExpeditionButton.visibility = View.VISIBLE
        } else {
            // No rat out! Hide the green status bar.
            activeExpeditionButton.visibility = View.GONE
        }
    } // <--- THIS BRACKET WAS MISSING!

    /**
     * The workbench stays hidden until [UNLOCK_LEVEL] so new players are not shown
     * consumables they have no context for yet.
     */
    private fun updateShopVisibility() {
        shopLayout.visibility = if (playerLevel >= UNLOCK_LEVEL) View.VISIBLE else View.GONE
    }

    /**
     * Explains the consumables the first time they unlock, then never again.
     *
     * Safe to call repeatedly - the persisted flag makes it a no-op after the
     * first showing, including across reinstall-free relaunches.
     */
    private fun maybeShowUnlockPopup() {
        if (playerLevel < UNLOCK_LEVEL) return
        if (sharedPreferences.getBoolean(KEY_UNLOCK_SEEN, false)) return
        if (isFinishing || isDestroyed) return

        sharedPreferences.edit().putBoolean(KEY_UNLOCK_SEEN, true).apply()

        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_unlock)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.findViewById<Button>(R.id.unlockConfirmButton).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    /** Pushes the running step total to the header and, if open, the Contract Board. */
    private fun updateStepDisplays() {
        val display = stepsSinceLaunch.toString()
        stepCountText.text = display
        bountyStepText?.text = display
    }

    /**
     * Swaps the egg artwork as EXP fills: intact below 25%, a heavier fracture
     * at each quarter, fully split at 100%. Skipped once hatched, because the
     * image is showing a rat by then.
     *
     * Each stage is a self-contained vector, so swapping in commissioned art
     * later means pointing these five branches at the new drawables.
     */
    private fun updateEggStage() {
        // Leave the just-hatched rat on screen until its moment is up.
        if (System.currentTimeMillis() < revealUntil) return

        val progress = if (maxExp <= 0) 0f else currentExp.toFloat() / maxExp
        petImage.setImageResource(
            when {
                progress >= 1.00f -> R.drawable.egg_stage_4
                progress >= 0.75f -> R.drawable.egg_stage_3
                progress >= 0.50f -> R.drawable.egg_stage_2
                progress >= 0.25f -> R.drawable.egg_stage_1
                else -> R.drawable.egg_stage_0
            }
        )
    }

    // Progress only - the collection is owned by Vault and written when it changes,
    // not on every step the pedometer reports.
    private fun saveGame() {
        sharedPreferences.edit()
            .putInt("PLAYER_LEVEL", playerLevel)
            .putInt("CURRENT_EXP", currentExp)
            .apply()
    }

    private fun loadGame() {
        playerLevel = sharedPreferences.getInt("PLAYER_LEVEL", 1)
        currentExp = sharedPreferences.getInt("CURRENT_EXP", 0)

        // Fixed Math to match the rest of your app!
        maxExp = playerLevel * 50

        updateScreen()
    }

    override fun onResume() {
        super.onResume()

        // The service owns the sensor now, so all we do is listen for its updates
        // and re-read whatever it has already written.
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(StepTrackerService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        isExpeditionActive = sharedPreferences.getBoolean("EXPEDITION_ACTIVE", false)
        if (isExpeditionActive) {
            expeditionEndTime = sharedPreferences.getLong("EXPEDITION_END_TIME", 0L)
        }

        reloadProgress()
        updateScreen()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(stateReceiver)
    }

    /** Fired by [StepTrackerService] whenever steps changed the save. */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            reloadProgress()

            val art = intent?.getStringExtra(StepTrackerService.EXTRA_HATCHED_ART)
            val name = intent?.getStringExtra(StepTrackerService.EXTRA_HATCHED_NAME)
            if (art != null && name != null) celebrateHatch(art, name)

            val reward = intent?.getIntExtra(StepTrackerService.EXTRA_BOUNTY_REWARD, 0) ?: 0
            if (reward > 0) {
                Toast.makeText(
                    this@MainActivity,
                    "Bounty Complete! +$reward Scrap!",
                    Toast.LENGTH_LONG
                ).show()
            }
            if (intent?.getBooleanExtra(StepTrackerService.EXTRA_BOUNTY_FAILED, false) == true) {
                Toast.makeText(
                    this@MainActivity,
                    "Bounty Failed! You ran out of time.",
                    Toast.LENGTH_SHORT
                ).show()
            }

            updateScreen()
            maybeShowUnlockPopup()
        }
    }

    /**
     * Starts step tracking, but only once we are allowed to.
     *
     * A "health" foreground service needs ACTIVITY_RECOGNITION actually granted,
     * not merely declared - starting it while the permission dialog is still
     * pending throws SecurityException and takes the app down.
     */
    private fun startTrackingIfAllowed() {
        val granted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACTIVITY_RECOGNITION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (granted) StepTrackerService.start(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // The user has just answered, so this is the earliest we may legally start.
        startTrackingIfAllowed()
    }

    /** Pulls level, EXP and the step total back out of the save. */
    private fun reloadProgress() {
        playerLevel = GameEngine.levelOf(sharedPreferences)
        currentExp = GameEngine.expOf(sharedPreferences)
        maxExp = GameEngine.maxExpFor(playerLevel)

        val total = GameEngine.totalStepsOf(sharedPreferences)
        if (launchBaselineSteps == 0f && total > 0f) launchBaselineSteps = total
        stepsSinceLaunch = (total - launchBaselineSteps).toInt().coerceAtLeast(0)
    }

}
