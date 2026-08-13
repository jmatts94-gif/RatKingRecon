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

        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS

        /** The Scrap Run icon with nothing out, dimmed rather than swapped. */
        const val IDLE_ICON_ALPHA = 0.35f
    }

    private var bountyReward = 0
    private var isBountyActive = false
    private var bountyTargetSteps = 0f // We use a float because the sensor uses floats
    private var bountyEndTime: Long = 0L // Long is used for big time numbers
    private var playerLevel = 1
    private var maxExp = 50

    private var isExpeditionActive = false
    private var deployedRatId: Long = -1L // -1 means "no rat selected"
    private var expeditionEndTime: Long = 0L

    private lateinit var sharedPreferences: SharedPreferences

    private lateinit var petImage: ImageView
    private lateinit var scrapText: TextView
    private lateinit var playerLevelText: TextView
    private lateinit var stepCountText: TextView

    /**
     * While this timestamp is in the future the freshly hatched rat stays on
     * screen instead of snapping back to the egg. Walking keeps earning EXP
     * throughout - this only affects what the image shows.
     */
    private var revealUntil = 0L

    private var currentExp = 0

    /**
     * Steps walked today, read straight from the save.
     *
     * This used to be a session count derived from a baseline captured when the
     * Activity was created, which meant backgrounding the app - or Android
     * quietly destroying it - restarted the number at zero while the service
     * carried on banking the walk correctly. Reading a persisted total instead
     * makes the display survive anything short of midnight.
     */
    private var stepsToday = 0

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

        // Rolls today's quest if the date has turned over, and books tonight's
        // streak reminder. Re-booking every launch is harmless - the alarm is a
        // single slot and setting it again just moves it.
        DailyQuest.ensureToday(sharedPreferences)
        DailyAlerts.scheduleStreakReminder(this)

        // 5. Button Listeners
        //
        // Both consumables are bought in the Shop now. Nothing on this screen
        // writes MUTAGEN_ACTIVE or POLISH_ACTIVE any more; GameEngine still
        // reads and clears them at the hatch exactly as before.
        // One button for both boards now. Contracts and Ledger Tasks are still
        // two systems with their own rewards and timers; they just share a
        // screen rather than a button each.
        val tasksButton = findViewById<Button>(R.id.tasksButton)
        tasksButton.setOnClickListener {
            startActivity(android.content.Intent(this, TasksActivity::class.java))
        }
        Tooltip.attachTo(
            tasksButton,
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

        findViewById<View>(R.id.streakTile).setOnClickListener { showDailyQuest() }

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

        // The notification carries the sound when the app is away; this is the
        // same moment heard while the player is watching it, where the alert is
        // deliberately suppressed.
        GameSounds.play(this, GameSounds.Cue.HATCH)
    }

    private fun updateScreen() {
        // The header icons carry the meaning now, so these are bare values.
        scrapText.text = sharedPreferences.getInt(GameEngine.KEY_SCRAP, 0).toString()
        playerLevelText.text = "Lvl $playerLevel"
        updateStepDisplays()
        updateExpeditionTile()
        updateStreakTile()

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
        dialog.window?.setWindowAnimations(R.style.Animation_RatKing_Dialog)
        dialog.findViewById<Button>(R.id.unlockConfirmButton).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    /**
     * Paints the streak tile: the count, and how lit the lantern is.
     *
     * Four discrete stages rather than a continuous fade, which is what the
     * lantern is for - it is read at a glance beside the egg, and a value that
     * creeps up by a percent is not readable at a glance. A quest that can only
     * be unfinished or finished simply uses the two ends.
     */
    private fun updateStreakTile() {
        DailyQuest.ensureToday(sharedPreferences)

        findViewById<TextView>(R.id.streakCount).text = Streak.count(sharedPreferences).toString()

        val percent = DailyQuest.percent(sharedPreferences)
        val stage = when {
            percent >= 100 -> 1.0f
            percent >= 66 -> 0.72f
            percent >= 33 -> 0.5f
            else -> 0.28f
        }
        findViewById<ImageView>(R.id.streakLantern).alpha = stage
    }

    /** Today's quest, its progress and what it pays. */
    private fun showDailyQuest() {
        DailyQuest.ensureToday(sharedPreferences)

        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_daily_quest)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.Animation_RatKing_Dialog)

        val type = DailyQuest.type(sharedPreferences)
        val target = DailyQuest.target(sharedPreferences)
        val progress = DailyQuest.progress(sharedPreferences)
        val percent = DailyQuest.percent(sharedPreferences)
        val streak = Streak.count(sharedPreferences)

        dialog.findViewById<TextView>(R.id.questTitle).text =
            if (type == QuestType.STEPS) {
                getString(DailyQuest.titleRes(type), target)
            } else {
                getString(DailyQuest.titleRes(type))
            }

        dialog.findViewById<ProgressBar>(R.id.questProgressBar).progress = percent
        dialog.findViewById<TextView>(R.id.questProgressText).text =
            if (DailyQuest.isComplete(sharedPreferences)) {
                getString(R.string.quest_done)
            } else {
                getString(R.string.quest_progress, progress, target)
            }

        dialog.findViewById<TextView>(R.id.questStreakText).text = when {
            Streak.onGraceDay(sharedPreferences) -> getString(R.string.quest_grace)
            streak == 0 -> getString(R.string.quest_streak_none)
            streak == 1 -> getString(R.string.quest_streak_one)
            else -> getString(R.string.quest_streak, streak)
        }

        dialog.findViewById<ImageView>(R.id.questLantern).alpha =
            if (percent >= 100) 1.0f else 0.28f + (percent / 100f) * 0.72f

        dialog.findViewById<Button>(R.id.questCloseButton).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** Pushes today's step total to the header and, if open, the Contract Board. */
    private fun updateStepDisplays() {
        val display = stepsToday.toString()
        stepCountText.text = display

        // The tile beside the egg shows the same total the header does, with
        // the distance it comes to underneath. The conversion is Milestones'
        // rather than one of its own, so the home screen and the Achievements
        // screen cannot disagree about how far a walk was.
        findViewById<TextView>(R.id.stepsTileCount).text =
            getString(R.string.tile_steps, stepsToday)
        findViewById<TextView>(R.id.stepsTileDistance).text =
            getString(R.string.tile_distance, Milestones.kilometresFor(stepsToday.toLong()))
    }

    /**
     * The Scrap Run tile: what is out, and how long is left of it.
     *
     * Reads the run ShopEffects records and writes nothing back - starting and
     * collecting both still happen on the Ledger. A run whose end time has
     * passed stays active until it is collected, so that window gets a label of
     * its own rather than counting down past zero.
     */
    private fun updateExpeditionTile() {
        val status = findViewById<TextView>(R.id.expeditionStatus)
        val icon = findViewById<ImageView>(R.id.expeditionIcon)

        if (!isExpeditionActive) {
            status.setText(R.string.tile_expedition_idle)
            icon.alpha = IDLE_ICON_ALPHA
            return
        }

        icon.alpha = 1f
        val remaining = expeditionEndTime - System.currentTimeMillis()
        status.text = when {
            remaining <= 0L -> getString(R.string.tile_expedition_ready)
            remaining >= HOUR_MS -> getString(
                R.string.tile_expedition_hm,
                remaining / HOUR_MS,
                (remaining % HOUR_MS) / MINUTE_MS
            )
            else -> getString(R.string.tile_expedition_m, remaining / MINUTE_MS)
        }
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

        // The walkthrough of this screen, once, after the splash has finished
        // with the player. It has to be here rather than in onCreate: the splash
        // is started and not waited for, so onCreate has no idea whether it is
        // done. See CoachMarks.shouldShow.
        CoachMarkOverlay.showIfDue(this)
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

        stepsToday = DailySteps.today(sharedPreferences)
    }

}
