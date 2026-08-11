package io.github.jmatts94.ratkingrecon

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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private companion object {
        /** Level at which the consumables on the home screen become available. */
        const val UNLOCK_LEVEL = 5
        const val KEY_UNLOCK_SEEN = "UNLOCK_POPUP_SEEN"

        /** How long a newly hatched rat stays on screen before the next egg appears. */
        const val REVEAL_MS = 5_000L

        /** Redraw interval for the active-contract dialog. */
        const val CONTRACT_TICK_MS = 1_000L
    }

    /** Drives the active-contract dialog; idle unless that dialog is open. */
    private val contractTicker = android.os.Handler(android.os.Looper.getMainLooper())

    private var bountyReward = 0
    private var isBountyActive = false
    private var bountyTargetSteps = 0f // We use a float because the sensor uses floats
    private var bountyEndTime: Long = 0L // Long is used for big time numbers
    private var playerLevel = 1
    private var maxExp = 50

    private var isExpeditionActive = false
    private var deployedRatId: Long = -1L // -1 means "no rat selected"
    private var expeditionEndTime: Long = 0L

    private fun startActiveBounty(offer: BountyOffer) {
        ActiveContract.accept(
            prefs = sharedPreferences,
            offer = offer,
            currentTotalSteps = GameEngine.totalStepsOf(sharedPreferences)
        )

        isBountyActive = true
        bountyReward = offer.reward
        bountyTargetSteps = GameEngine.totalStepsOf(sharedPreferences) + offer.steps
        bountyEndTime = System.currentTimeMillis() + offer.minutes * 60L * 1000L

        Toast.makeText(
            this,
            getString(R.string.toast_contract_accepted, offer.minutes),
            Toast.LENGTH_LONG
        ).show()
    }
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

    private fun checkExpedition() {
        if (!isExpeditionActive) {
            Toast.makeText(this, getString(R.string.toast_no_rats_out), Toast.LENGTH_SHORT).show()
            return
        }

        val currentTime = System.currentTimeMillis()

        if (currentTime >= expeditionEndTime) {
            // SCENARIO A: TIME IS UP! (Give Reward)
            isExpeditionActive = false
            sharedPreferences.edit().putBoolean(ShopEffects.KEY_EXPEDITION_ACTIVE, false).apply()

            // Give a massive payout for waiting (e.g., 200 to 500 Scrap)
            val reward = (200..500).random()
            val currentScrap = sharedPreferences.getInt(GameEngine.KEY_SCRAP, 0)
            sharedPreferences.edit().putInt(GameEngine.KEY_SCRAP, currentScrap + reward).apply()

            Toast.makeText(this, getString(R.string.toast_expedition_complete, reward), Toast.LENGTH_LONG).show()
            updateScreen()
        } else {
            // SCENARIO B: STILL WORKING (Tell them how long is left)
            val timeLeftMillis = expeditionEndTime - currentTime
            val minutesLeft = (timeLeftMillis / (1000 * 60)).toInt()

            Toast.makeText(this, getString(R.string.toast_expedition_running, minutesLeft), Toast.LENGTH_SHORT).show()
        }
    }
    private fun showBountyBoard() {
        // One contract at a time, but "you already have one" on its own left no
        // way to see what it was. Showing it is more use than refusing.
        ActiveContract.load(sharedPreferences)?.let {
            showActiveContract(it)
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
            startActiveBounty(offer)
            dialog.dismiss()
        }
    }

    /**
     * Shows the contract already running: what it was, how far along it is, how
     * long is left and what it pays.
     *
     * Redrawn on a ticker while it is open, because both the step count and the
     * countdown move on their own - the service banks steps whether or not this
     * dialog is up.
     */
    private fun showActiveContract(contract: ActiveContract) {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_active_contract)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val nameText = dialog.findViewById<TextView>(R.id.activeContractName)
        val stepsText = dialog.findViewById<TextView>(R.id.activeContractSteps)
        val progressBar = dialog.findViewById<ProgressBar>(R.id.activeContractProgress)
        val timeText = dialog.findViewById<TextView>(R.id.activeContractTime)
        val rewardText = dialog.findViewById<TextView>(R.id.activeContractReward)

        nameText.text = contract.name.ifBlank { getString(R.string.contract_active_unnamed) }
        rewardText.text = getString(R.string.contract_active_reward, contract.reward)

        val redraw = object : Runnable {
            override fun run() {
                // Re-read rather than trusting the copy this dialog opened with:
                // the contract may have been paid out or failed underneath it.
                val live = ActiveContract.load(sharedPreferences)
                if (live == null) {
                    dialog.dismiss()
                    return
                }

                val total = GameEngine.totalStepsOf(sharedPreferences)

                if (live.knowsRequirement) {
                    stepsText.text = getString(
                        R.string.contract_active_steps,
                        live.stepsWalked(total),
                        live.requiredSteps.toInt()
                    )
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = live.percentComplete(total)
                } else {
                    // Accepted before the requirement was recorded, so the only
                    // honest thing to show is the distance still owed.
                    stepsText.text = getString(
                        R.string.contract_active_steps_left,
                        live.stepsRemaining(total)
                    )
                    progressBar.visibility = View.GONE
                }

                timeText.text = if (live.hasExpired()) {
                    getString(R.string.contract_active_expired)
                } else {
                    getString(R.string.contract_active_time, live.minutesRemaining())
                }

                contractTicker.postDelayed(this, CONTRACT_TICK_MS)
            }
        }

        dialog.findViewById<Button>(R.id.btnCloseActiveContract).setOnClickListener {
            dialog.dismiss()
        }
        dialog.setOnDismissListener { contractTicker.removeCallbacks(redraw) }

        redraw.run()
        dialog.show()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 0. First launch: explain the game before the workshop appears. The
        //    flag lives in the save, so a reset brings the walkthrough back.
        if (!Onboarding.isComplete(RatRepository.prefs(this))) {
            startActivity(android.content.Intent(this, OnboardingActivity::class.java))
        }

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
        missionButton = findViewById(R.id.scavengeMissionsButton)
        petImage = findViewById(R.id.petImage)
        scrapText = findViewById(R.id.scrapText)
        playerLevelText = findViewById(R.id.playerLevelText)
        stepCountText = findViewById(R.id.stepCountText)

        // 3. WAKE UP ROUTINE (Fixed: Now using "SaveData" to match the rest of your app)
        sharedPreferences = getSharedPreferences("SaveData", Context.MODE_PRIVATE)

        playerLevel = sharedPreferences.getInt("PLAYER_LEVEL", 1)
        maxExp = playerLevel * 50

        isBountyActive = ActiveContract.isActive(sharedPreferences)
        ActiveContract.load(sharedPreferences)?.let {
            bountyTargetSteps = it.targetSteps
            bountyEndTime = it.endsAt
            bountyReward = it.reward
        }

        // 4. Hand step tracking to the service. Started from here because
        //    Android 12+ refuses foreground-service starts from the background.
        startTrackingIfAllowed()

        // 5. Button Listeners
        //
        // Both consumables are bought in the Shop now. Nothing on this screen
        // writes MUTAGEN_ACTIVE or POLISH_ACTIVE any more; GameEngine still
        // reads and clears them at the hatch exactly as before.
        findViewById<Button>(R.id.scavengeMissionsButton).setOnClickListener {
            showBountyBoard()
        }

        val ledgerTasksButton = findViewById<Button>(R.id.expeditionBoardButton)
        ledgerTasksButton.setOnClickListener {
            startActivity(android.content.Intent(this, LedgerTasksActivity::class.java))
        }
        Tooltip.attachTo(
            ledgerTasksButton,
            R.string.tooltip_tasks_title,
            R.string.tooltip_tasks_body
        )

        findViewById<Button>(R.id.openGalleryButton).setOnClickListener {
            startActivity(android.content.Intent(this, GalleryActivity::class.java))
        }

        findViewById<Button>(R.id.shopButton).setOnClickListener {
            startActivity(android.content.Intent(this, ShopActivity::class.java))
        }

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.achievementsButton).setOnClickListener {
            startActivity(android.content.Intent(this, AchievementsActivity::class.java))
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
            deployedRatId = -1L
            expeditionEndTime = 0L

            updateScreen()
            Toast.makeText(this, getString(R.string.toast_game_reset), Toast.LENGTH_SHORT).show()
        }

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
        Toast.makeText(this, getString(R.string.toast_hatch_joined, name), Toast.LENGTH_LONG).show()
    }

    private fun updateScreen() {
        // The header icons carry the meaning now, so these are bare values.
        scrapText.text = sharedPreferences.getInt(GameEngine.KEY_SCRAP, 0).toString()
        playerLevelText.text = "Lvl $playerLevel"
        updateStepDisplays()

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

        isExpeditionActive = sharedPreferences.getBoolean(ShopEffects.KEY_EXPEDITION_ACTIVE, false)
        if (isExpeditionActive) {
            expeditionEndTime = sharedPreferences.getLong(ShopEffects.KEY_EXPEDITION_END, 0L)
        }

        reloadProgress()
        updateScreen()
        maybeStartBankedBoss()
    }

    /**
     * Starts a boss banked while the app was away.
     *
     * The whole reason bosses are banked rather than raised is that this happens
     * here, with the player looking at the screen, instead of arriving as a
     * notification that could be auto-resolved from a pocket.
     *
     * [Bosses.startBanked] declines - leaving the boss banked for next time - if
     * an ordinary Rustbot is still waiting or every rat is knocked out, so both
     * of those cases simply do nothing here.
     */
    private fun maybeStartBankedBoss() {
        if (Bosses.bankedId(sharedPreferences) == null) return

        lifecycleScope.launch {
            val started = withContext(Dispatchers.IO) {
                Bosses.startBanked(
                    RatRepository.dao(this@MainActivity),
                    sharedPreferences
                ) { getString(it.nameRes) }
            } ?: return@launch

            Toast.makeText(
                this@MainActivity,
                getString(R.string.boss_arrived, getString(started.nameRes)),
                Toast.LENGTH_LONG
            ).show()
            startActivity(android.content.Intent(this@MainActivity, BattleActivity::class.java))
        }
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
