package io.github.jmatts94.ratkingrecon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.PathMeasure
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
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

        /** Matches the Tasks screen, and the tile only ever shows whole minutes. */
        const val TICK_MS = 30_000L

        /**
         * How long the lantern takes to change stage. Long enough to read as a
         * light coming up rather than a value being assigned, short enough that
         * returning to the screen does not feel like waiting for an animation.
         */
        const val GLOW_FADE_MS = 280L

        /** How long each tip sits before the next one, inside the 6-8s the note asked for. */
        const val TIP_INTERVAL_MS = 7_000L

        /** Half of the crossfade - out then back in, so the swap is never a cut. */
        const val TIP_FADE_MS = 400L
    }

    private val ticker = Handler(Looper.getMainLooper())

    /**
     * Keeps the Scrap Run countdown honest while the screen is open.
     *
     * Without it the tile only redrew on resume and whenever the step service
     * reported, so standing still left the remaining time frozen at whatever it
     * read when the screen opened. Only this one tile moves on its own - the
     * rest of the screen changes when the save changes, and that already has a
     * receiver - so the tick redraws it alone rather than the whole screen.
     */
    private val tick = object : Runnable {
        override fun run() {
            updateExpeditionTile()
            ticker.postDelayed(this, TICK_MS)
        }
    }

    /** The flavor lines at the foot of the screen, read once from strings.xml. */
    private val gameplayTips: Array<String> by lazy { resources.getStringArray(R.array.gameplay_tips) }
    private var tipIndex = 0

    /** Crossfades the tip tile to its next line, rather than cutting between them. */
    private val tipTick = object : Runnable {
        override fun run() {
            showNextTip()
            ticker.postDelayed(this, TIP_INTERVAL_MS)
        }
    }

    private var bountyReward = 0
    private var isBountyActive = false
    private var bountyTargetSteps = 0f // We use a float because the sensor uses floats
    private var bountyEndTime: Long = 0L // Long is used for big time numbers
    private var playerLevel = 1
    private var maxExp = GameEngine.maxExpFor(1)

    private var isExpeditionActive = false
    private var deployedRatId: Long = -1L // -1 means "no rat selected"
    private var expeditionEndTime: Long = 0L

    private lateinit var sharedPreferences: SharedPreferences

    private lateinit var petImage: ImageView
    private lateinit var eggPanel: FrameLayout
    private lateinit var tunnelImage: ImageView
    private lateinit var tunnelRatIcon: ImageView
    private lateinit var scrapText: TextView
    private lateinit var playerLevelText: TextView
    private lateinit var stepCountText: TextView
    private lateinit var tipText: TextView

    /**
     * The tunnel's centreline, in the same 0..100 viewport hatch_tunnel.xml's
     * own pathData uses - duplicated from that file rather than parsed out of
     * it, since a VectorDrawable's pathData isn't available to read back at
     * runtime. The two must be changed together; see the note in the drawable.
     *
     * A snake of five straight runs joined by short drops - top right to
     * bottom left - rather than a curve. [PathMeasure] does not care which:
     * it walks arc length along whatever [Path] it is given, so switching
     * this from cubicTo curves to plain lineTo segments needed no change to
     * [positionTunnelRat] at all.
     */
    private val tunnelPath = Path().apply {
        moveTo(85f, 12f)
        lineTo(15f, 12f)
        lineTo(15f, 30f)
        lineTo(85f, 30f)
        lineTo(85f, 48f)
        lineTo(15f, 48f)
        lineTo(15f, 66f)
        lineTo(85f, 66f)
        lineTo(85f, 84f)
        lineTo(15f, 84f)
    }
    private val tunnelPathMeasure = PathMeasure(tunnelPath, false)

    /**
     * While this timestamp is in the future the freshly hatched rat stays on
     * screen instead of snapping back to the tunnel. Walking keeps earning EXP
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

            // The deployed rat's faction, if any - read off the DAO, so this
            // has to leave the main thread. Gone entirely (spliced away since
            // deploying) reads the same as never having named one: no bonus,
            // not a crash.
            val ratId = sharedPreferences.getLong("DEPLOYED_RAT_ID", -1L)

            lifecycleScope.launch {
                val dao = RatRepository.dao(this@MainActivity)
                val faction = withContext(Dispatchers.IO) {
                    if (ratId < 0) null else dao.byId(ratId)?.faction
                }

                // Give a massive payout for waiting (e.g., 200 to 500 Scrap),
                // boosted for a Smuggler.
                val reward = TaskBonuses.scrapFor((200..500).random(), faction)
                val currentScrap = sharedPreferences.getInt(GameEngine.KEY_SCRAP, 0)
                sharedPreferences.edit().putInt(GameEngine.KEY_SCRAP, currentScrap + reward).apply()

                // The Scrap Run never rolled for a relic before this - see
                // TaskBonuses.EXPEDITION_BASE_RELIC_CHANCE.
                val relicChance = TaskBonuses.relicChanceFor(
                    TaskBonuses.EXPEDITION_BASE_RELIC_CHANCE, faction
                )
                val relic = withContext(Dispatchers.IO) { Relics.rollFor(relicChance) }
                relic?.let {
                    val editor = sharedPreferences.edit()
                    Relics.grant(sharedPreferences, editor, it)
                    editor.apply()
                }

                // Foundry-born's bonus EXP, banked through the same path a
                // walked step would use - see GameEngine.bankBonusExp.
                val expBonus = TaskBonuses.expFor(TaskBonuses.Activity.EXPEDITION, faction)
                val hatched = if (expBonus > 0) {
                    withContext(Dispatchers.IO) {
                        GameEngine.bankBonusExp(dao, sharedPreferences, expBonus)
                    }
                } else {
                    null
                }

                Toast.makeText(
                    this@MainActivity,
                    if (relic != null) {
                        getString(R.string.toast_expedition_complete_relic, reward, getString(relic.nameRes))
                    } else {
                        getString(R.string.toast_expedition_complete, reward)
                    },
                    Toast.LENGTH_LONG
                ).show()

                reloadProgress()
                if (hatched != null) celebrateHatch(hatched.artKey, hatched.name)
                updateScreen()
            }
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
        eggPanel = findViewById(R.id.eggPanel)
        tunnelImage = findViewById(R.id.tunnelImage)
        tunnelRatIcon = findViewById(R.id.tunnelRatIcon)
        scrapText = findViewById(R.id.scrapText)
        playerLevelText = findViewById(R.id.playerLevelText)
        stepCountText = findViewById(R.id.stepCountText)
        tipText = findViewById(R.id.tipText)
        tipText.text = gameplayTips.getOrNull(tipIndex)

        // 3. WAKE UP ROUTINE (Fixed: Now using "SaveData" to match the rest of your app)
        sharedPreferences = getSharedPreferences("SaveData", Context.MODE_PRIVATE)

        playerLevel = sharedPreferences.getInt("PLAYER_LEVEL", 1)
        maxExp = GameEngine.maxExpFor(playerLevel)

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

        // The tile is the only surviving way to collect a finished Scrap Run
        // now that the old status bar under Shop is gone - checkExpedition()
        // already handles "nothing out" and "still running" gracefully, so
        // wiring the tap straight to it needs no extra state here.
        findViewById<View>(R.id.expeditionTile).setOnClickListener { checkExpedition() }
        Tooltip.attachTo(
            findViewById(R.id.expeditionTile),
            R.string.tooltip_expedition_title,
            R.string.tooltip_expedition_body
        )
        // The header pill and the tile below it both count steps and count
        // different ones, which is exactly the sort of thing an explainer is
        // for. It says which is which from either side.
        Tooltip.attachTo(
            stepCountText,
            R.string.tooltip_lifetime_title,
            R.string.tooltip_lifetime_body
        )
        // Steps are the one tile with no tap of its own - the explainer is all
        // a press on it does, and setOnLongClickListener makes it long-clickable
        // by itself, which is why it carries no android:clickable in the layout
        // the way the other two tiles now do.
        Tooltip.attachTo(
            findViewById(R.id.stepsTile),
            R.string.tooltip_steps_title,
            R.string.tooltip_steps_body
        )

        findViewById<Button>(R.id.achievementsButton).setOnClickListener {
            startActivity(android.content.Intent(this, AchievementsActivity::class.java))
        }

        findViewById<Button>(R.id.battleArenaButton).setOnClickListener {
            startActivity(android.content.Intent(this, ArenaLandingActivity::class.java))
        }

        findViewById<Button>(R.id.devResetButton).setOnClickListener {
            sharedPreferences.edit().clear().apply()
            playerLevel = 1
            currentExp = 0
            maxExp = GameEngine.maxExpFor(1)

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
        dialog.window?.setWindowAnimations(R.style.Animation_RatKing_Dialog)
        dialog.findViewById<Button>(R.id.unlockConfirmButton).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    /**
     * Paints the streak tile: the count, and how brightly the lantern burns.
     *
     * Four discrete stages rather than a continuous fade, which is what the
     * lantern is for - it is read at a glance beside the egg, and a value that
     * creeps up by a percent is not readable at a glance. A quest that can only
     * be unfinished or finished simply uses the two ends.
     *
     * The stages used to be the icon's own alpha, which dimmed the metalwork
     * along with the light and made an unstarted quest look like a drawing
     * failing to load rather than a lantern turned low. The lantern now holds
     * full opacity always and the light behind it carries the progress, which
     * is the thing actually being reported.
     *
     * Brightness moves further than size does across the four. The tile is
     * 40dp tall at its smallest and the lantern already takes 36dp of that, so
     * there is no room for a halo that grows dramatically - and a glow reaching
     * past the pill would read as a drawing error, not as more light.
     */
    private fun updateStreakTile() {
        DailyQuest.ensureToday(sharedPreferences)

        findViewById<TextView>(R.id.streakCount).text = Streak.count(sharedPreferences).toString()

        applyGlow(findViewById(R.id.streakGlow), DailyQuest.percent(sharedPreferences))
    }

    /**
     * Lights a lantern's glow to match [percent].
     *
     * Shared by the tile and the quest dialog rather than written out twice.
     * They are two views of one number, and a lantern that burns brighter on
     * the home screen than in the dialog it opens would be reporting the same
     * progress two different ways.
     *
     * The glow always animates in. A dialog's glow starts from the invisible it
     * was inflated at, which is the fade the tile only gets on a cold start.
     */
    private fun applyGlow(glow: View, percent: Int) {
        val (alpha, scale) = when {
            percent >= 100 -> 1.00f to 1.45f
            percent >= 66 -> 0.75f to 1.25f
            percent >= 33 -> 0.50f to 1.05f
            // Lit, faintly, rather than dark. A lantern showing nothing at all
            // reads as broken; showing almost nothing reads as turned down.
            else -> 0.20f to 0.85f
        }

        glow.animate()
            .alpha(alpha)
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(GLOW_FADE_MS)
            .start()
    }

    /** Today's quest, its progress and what it pays. */
    private fun showDailyQuest() {
        DailyQuest.ensureToday(sharedPreferences)

        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_daily_quest)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.Animation_RatKing_Dialog)

        // Without this the window is WRAP_CONTENT, and the card collapses to the
        // width of the one child that asks for a width of its own - the small
        // "Today's Round" label. Every other row is match_parent and gets
        // squeezed to that, which silently cut "Win a Rustbot fight" down to
        // "Win a" and the reward line to "Reward: 20-40". The layout's own 24dp
        // margin is what insets the card, so the window itself takes the width.
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

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

        // The range is the one rollReward actually pays, not a second copy of it
        // typed into the string.
        dialog.findViewById<TextView>(R.id.questRewardText).text = getString(
            R.string.quest_reward_line,
            DailyQuest.SCRAP_REWARD.first,
            DailyQuest.SCRAP_REWARD.last
        )

        applyGlow(dialog.findViewById(R.id.questGlow), percent)

        dialog.findViewById<Button>(R.id.questCloseButton).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /**
     * Pushes the two step totals to the two places that show them.
     *
     * The header counts every step ever taken and the tile beside the egg counts
     * today's. They were both today's, which made the header a second copy of a
     * number already on the screen; the lifetime figure was meanwhile only
     * visible on the Achievements screen, despite being the one that never goes
     * backwards and the one the milestones are measured against.
     */
    private fun updateStepDisplays() {
        stepCountText.text = GameEngine.lifetimeStepsOf(sharedPreferences).toString()

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
     * Fades the tip tile to its next line rather than swapping it under the
     * player's eye. Out, swap the text while invisible, back in - a cut would
     * read as a glitch on something that is meant to sit quietly.
     */
    private fun showNextTip() {
        if (gameplayTips.isEmpty()) return
        tipIndex = (tipIndex + 1) % gameplayTips.size

        tipText.animate()
            .alpha(0f)
            .setDuration(TIP_FADE_MS)
            .withEndAction {
                tipText.text = gameplayTips[tipIndex]
                tipText.animate().alpha(1f).setDuration(TIP_FADE_MS).start()
            }
            .start()
    }

    /**
     * The Scrap Run tile: what is out, and how long is left of it.
     *
     * Reads the run ShopEffects records; a tap on the tile is what writes back
     * and collects (see checkExpedition()) - starting still happens on the
     * Ledger. A run whose end time has passed stays active until it is
     * collected, so that window gets a label of its own rather than counting
     * down past zero.
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
     * Slides the rat icon down the tunnel as EXP fills, at the top when a
     * level just started and at the tunnel's mouth right as the next hatch
     * fires. Skipped once hatched - [petImage] is showing the new rat by then,
     * covering the tunnel entirely, the same swap the egg used to make.
     */
    private fun updateEggStage() {
        val revealing = System.currentTimeMillis() < revealUntil
        petImage.visibility = if (revealing) View.VISIBLE else View.GONE
        tunnelImage.visibility = if (revealing) View.GONE else View.VISIBLE
        tunnelRatIcon.visibility = if (revealing) View.GONE else View.VISIBLE
        if (revealing) return

        val progress = if (maxExp <= 0) 0f else currentExp.toFloat() / maxExp
        positionTunnelRat(progress.coerceIn(0f, 1f))
    }

    /**
     * Places [tunnelRatIcon] at [progress] of the way along [tunnelPath].
     *
     * Posted rather than applied immediately: [eggPanel] is sized by
     * ConstraintLayout against the width available (see the layout's own
     * comment on why it is not a fixed dp), so its measured size is not known
     * the instant this is first called from onCreate. `post` runs after the
     * pending layout pass instead of guessing whether one has already
     * happened.
     */
    private fun positionTunnelRat(progress: Float) {
        eggPanel.post {
            val panelWidth = eggPanel.width.toFloat()
            val panelHeight = eggPanel.height.toFloat()
            if (panelWidth <= 0f || panelHeight <= 0f) return@post

            val point = FloatArray(2)
            tunnelPathMeasure.getPosTan(progress * tunnelPathMeasure.length, point, null)

            // point[] is in the drawable's 0..100 viewport; hatch_tunnel.xml
            // fills eggPanel exactly (fitCenter over a square drawable in a
            // square parent), so scaling by the panel's own pixel size lines
            // the icon up with the pipe drawn under it.
            tunnelRatIcon.translationX = point[0] / 100f * panelWidth - tunnelRatIcon.width / 2f
            tunnelRatIcon.translationY = point[1] / 100f * panelHeight - tunnelRatIcon.height / 2f
        }
    }

    private fun loadGame() {
        playerLevel = sharedPreferences.getInt("PLAYER_LEVEL", 1)
        currentExp = sharedPreferences.getInt("CURRENT_EXP", 0)
        maxExp = GameEngine.maxExpFor(playerLevel)

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
        ticker.postDelayed(tick, TICK_MS)
        ticker.postDelayed(tipTick, TIP_INTERVAL_MS)
        maybeStartBankedBoss()

        // The walkthrough of this screen, once, after the splash has finished
        // with the player. It has to be here rather than in onCreate: the splash
        // is started and not waited for, so onCreate has no idea whether it is
        // done. See CoachMarks.shouldShow.
        CoachMarkOverlay.showIfDue(this)

        maybeShowWhatsNew()
    }

    /**
     * The release notes, once per update.
     *
     * Behind the walkthrough rather than beside it. Both want the first resume
     * of a home screen the player can see, and a player still being shown where
     * things are is not the audience for a list of what moved. Returning without
     * consuming is deliberate: the version stays unstamped, so the notes are
     * still owed on the next resume once the walkthrough is done with.
     */
    private fun maybeShowWhatsNew() {
        if (isFinishing || isDestroyed) return
        if (!Onboarding.isComplete(sharedPreferences)) return
        if (CoachMarks.shouldShow(sharedPreferences)) return

        if (!WhatsNew.consume(sharedPreferences)) return

        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_whats_new)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.Animation_RatKing_Dialog)

        // The card is match_parent inside a window that would otherwise be
        // WRAP_CONTENT, which collapses it to its narrowest child and cuts the
        // text. The layout's own 24dp margin is what insets it.
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialog.findViewById<Button>(R.id.whatsNewCloseButton).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
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
     *
     * Also recovers a boss fight that already made it into a pending [Encounter]
     * but never reached [BattleActivity] - the trace of a previous call to this
     * same method losing its Activity mid-flight (see [AppScope]). There is
     * nothing else in the app that would ever offer that fight again, so a
     * pending encounter with a [Encounter.bossId] is routed straight back in
     * rather than left to sit.
     *
     * Runs on [AppScope] rather than `lifecycleScope`: [Bosses.startBanked]
     * mutates the save (clears the bank, writes the pending encounter) partway
     * through, and a scope tied to this Activity would drop the rest of the
     * work - including the `startActivity` call below - if this screen is torn
     * down between that write and its own resumption. A scope that survives the
     * Activity guarantees the hand-off either completes or never started.
     */
    private fun maybeStartBankedBoss() {
        val orphaned = Encounter.load(sharedPreferences)?.takeIf { it.isBoss }
        if (orphaned == null && Bosses.bankedId(sharedPreferences) == null) return

        val appContext = applicationContext

        AppScope.launch {
            val spec = withContext(Dispatchers.IO) {
                if (orphaned != null) {
                    Bosses.byId(orphaned.bossId)
                } else {
                    Bosses.startBanked(
                        RatRepository.dao(appContext),
                        sharedPreferences
                    ) { appContext.getString(it.nameRes) }
                }
            } ?: return@launch

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.boss_arrived, appContext.getString(spec.nameRes)),
                    Toast.LENGTH_LONG
                ).show()
                appContext.startActivity(
                    android.content.Intent(appContext, BattleActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
        ticker.removeCallbacks(tipTick)
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
