package io.github.jmatts94.ratkingrecon

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The manual battle screen.
 *
 * Owns no combat rules of its own: it drives the same [Battle] that
 * Auto-Resolve does and only renders the result, so the two paths cannot drift
 * apart.
 */
class BattleActivity : AppCompatActivity() {

    private companion object {
        /** Milliseconds per character for the log's own typewriter reveal - see [typeInLatestLine]. */
        const val TYPEWRITER_MS = 16L

        /** How long after the Rustbot's HEAVY the parry's counter-hit plays. */
        const val PARRY_COUNTER_DELAY_MS = 420L

        /** The pause between a guided round and the next lesson. */
        const val PRACTICE_STAGE_DELAY_MS = 1_400L

        /** The bot portrait frame's plain size, matching activity_battle.xml's own default. */
        const val PLAIN_PORTRAIT_DP = 64
        /** How much bigger a ceremonial boss's own portrait frame stands next to that - see [bindBossPortrait]. */
        const val BOSS_PORTRAIT_DP = 88

        /** The same breathing-glow cadence an earned Arena medallion already pulses at - see [ArenaBadgeMedallion]. */
        const val BOSS_GLOW_CYCLE_MS = 1_600L
        const val BOSS_GLOW_MIN_ALPHA = 30
    }

    private lateinit var battle: Battle
    private lateinit var encounter: Encounter
    private lateinit var rat: RatEntity

    private lateinit var botName: TextView
    private lateinit var botStats: TextView
    private lateinit var botHpBar: ProgressBar
    private lateinit var botDotIcon: ImageView
    private lateinit var botChargingIcon: ImageView
    private lateinit var botIntentText: TextView
    private lateinit var ratName: TextView
    private lateinit var ratStats: TextView
    private lateinit var ratHpBar: ProgressBar
    private lateinit var ratDotIcon: ImageView
    private lateinit var ratImage: ImageView
    private lateinit var specialGlyph: ImageView
    private lateinit var botCard: View
    private lateinit var ratCard: View
    private lateinit var botPortraitFrame: View
    private lateinit var ratPortraitFrame: View
    private lateinit var botImage: ImageView
    private lateinit var bossGlow: View
    private lateinit var bossBadge: ImageView
    private lateinit var botHitFlash: View
    private lateinit var ratHitFlash: View
    private lateinit var botDamagePopup: TextView
    private lateinit var ratDamagePopup: TextView
    private lateinit var activeBuffBadge: View
    private lateinit var activeBuffIcon: ImageView
    private lateinit var activeBuffLabel: TextView
    private lateinit var logView: TextView
    private lateinit var btnAttack: MaterialButton
    private lateinit var btnDefend: MaterialButton
    private lateinit var btnSpecial: MaterialButton
    private lateinit var btnItems: MaterialButton
    private lateinit var btnLeave: MaterialButton

    private val lines = mutableListOf<String>()

    /** Icon and name of whichever combat buff rode into this fight, if either did. */
    private var armedBuff: Pair<Int, Int>? = null

    /**
     * What the HP bars last actually showed, so [render] knows whether to
     * tween or snap - see [animateHpBar]. -1 means "nothing shown yet",
     * which is also what [bindStaticViews] resets both back to for every
     * fight this Activity loads, including an Arena run's own reload-in-place
     * onward: the first render of a new fight should never tween in from
     * whatever the last fight's bar happened to be sitting at.
     */
    private var lastBotHpShown = -1
    private var lastRatHpShown = -1

    /** The portraits' own idle breathing loop - see [startIdleAnimations]. */
    private val idleAnimators = mutableListOf<ObjectAnimator>()

    /**
     * A ceremonial boss's own glow, breathing behind its portrait - see
     * [bindStaticViews]. Fight-scoped rather than started once like
     * [idleAnimators]: most fights are an ordinary Rustbot with no glow to
     * animate at all, so this only exists for the fights that actually earn
     * one.
     */
    private var bossGlowAnimator: ObjectAnimator? = null

    /** Whether this is Boot Camp's practice fight, still being walked through. */
    private var practiceGuide = false

    /** Which lesson of the guided fight is next - see [showPracticeStage]. */
    private var practiceStage = 0

    /** The big centred word that pops over the fight - see [showCallout]. */
    private lateinit var callout: TextView

    /** The HEAVY warning's own throb - see [renderIntent]. */
    private var intentPulse: ObjectAnimator? = null

    /** The rat's health bar beating once it is low - see [renderHeartbeat]. */
    private var heartbeat: ObjectAnimator? = null

    /** The battle log's newest line typing itself out - see [render] and [revealLogInstantly]. */
    private var typewriterJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_battle)
        EdgeToEdge.apply(this)

        botName = findViewById(R.id.botName)
        botStats = findViewById(R.id.botStats)
        botHpBar = findViewById(R.id.botHpBar)
        botDotIcon = findViewById(R.id.botDotIcon)
        botChargingIcon = findViewById(R.id.botChargingIcon)
        botIntentText = findViewById(R.id.botIntentText)
        ratName = findViewById(R.id.ratName)
        ratStats = findViewById(R.id.ratStats)
        ratHpBar = findViewById(R.id.ratHpBar)
        ratDotIcon = findViewById(R.id.ratDotIcon)
        ratImage = findViewById(R.id.ratImage)
        specialGlyph = findViewById(R.id.specialGlyph)
        botCard = findViewById(R.id.botCard)
        ratCard = findViewById(R.id.ratCard)
        botPortraitFrame = findViewById(R.id.botPortraitFrame)
        ratPortraitFrame = findViewById(R.id.ratPortraitFrame)
        botImage = findViewById(R.id.botImage)
        bossGlow = findViewById(R.id.bossGlow)
        bossBadge = findViewById(R.id.bossBadge)
        botHitFlash = findViewById(R.id.botHitFlash)
        ratHitFlash = findViewById(R.id.ratHitFlash)
        botDamagePopup = findViewById(R.id.botDamagePopup)
        ratDamagePopup = findViewById(R.id.ratDamagePopup)
        activeBuffBadge = findViewById(R.id.activeBuffBadge)
        activeBuffIcon = findViewById(R.id.activeBuffIcon)
        activeBuffLabel = findViewById(R.id.activeBuffLabel)
        logView = findViewById(R.id.battleLog)
        btnAttack = findViewById(R.id.btnAttack)
        btnDefend = findViewById(R.id.btnDefend)
        btnSpecial = findViewById(R.id.btnSpecial)
        btnItems = findViewById(R.id.btnItems)
        btnLeave = findViewById(R.id.btnLeave)

        btnLeave.setOnClickListener { finish() }
        // A tap skips the typewriter straight to the full line - see
        // revealLogInstantly. Harmless to tap when nothing is animating: it
        // just re-sets the same text that is already fully shown.
        logView.setOnClickListener { revealLogInstantly() }
        wireActions()
        startIdleAnimations()
        buildCallout()

        loadFight()
    }

    override fun onDestroy() {
        idleAnimators.forEach { it.cancel() }
        idleAnimators.clear()
        bossGlowAnimator?.cancel()
        intentPulse?.cancel()
        heartbeat?.cancel()
        super.onDestroy()
    }

    /**
     * The callout sits over the whole screen rather than in the layout: the
     * layout is a ScrollView, and a word meant to land in the middle of what
     * the player is looking at cannot live somewhere they may have scrolled
     * past. Never takes touches - it is a flourish, not a button.
     */
    private fun buildCallout() {
        val density = resources.displayMetrics.density
        callout = TextView(this).apply {
            textSize = 30f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.06f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            background = ContextCompat.getDrawable(context, R.drawable.bg_pill_tan)
            val h = (28 * density).toInt()
            val v = (12 * density).toInt()
            setPadding(h, v, h, v)
            compoundDrawablePadding = (10 * density).toInt()
            elevation = 16 * density
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
        findViewById<ViewGroup>(android.R.id.content).addView(
            callout,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            )
        )
    }

    /**
     * Pops [textRes] up in the middle of the screen: in fast and a touch
     * oversized, settles, holds, then fades. An icon rides alongside the
     * word, so the moment reads without leaning on colour.
     */
    private fun showCallout(textRes: Int, iconRes: Int) {
        if (isFinishing || isDestroyed) return
        val size = (26 * resources.displayMetrics.density).toInt()
        val icon = ContextCompat.getDrawable(this, iconRes)?.mutate()?.apply { setBounds(0, 0, size, size) }
        callout.setCompoundDrawablesRelative(icon, null, null, null)
        callout.setText(textRes)

        // Every stage sets its own start delay: a ViewPropertyAnimator keeps
        // the last one it was given, so a callout cut off mid-hold would
        // otherwise make the next one wait before it even appeared.
        callout.animate().cancel()
        callout.alpha = 0f
        callout.scaleX = 0.6f
        callout.scaleY = 0.6f
        callout.visibility = View.VISIBLE
        callout.animate()
            .alpha(1f).scaleX(1.08f).scaleY(1.08f).setStartDelay(0).setDuration(170)
            .withEndAction {
                callout.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(130)
                    .withEndAction {
                        callout.animate().alpha(0f).setStartDelay(650).setDuration(260)
                            .withEndAction { callout.visibility = View.GONE }
                    }
            }
    }

    /**
     * Boot Camp's guided practice fight, one lesson per round.
     *
     * Round 1 explains the two health bars and the next-move line, then has
     * the player tap Attack. Round 2 is Special, round 3 the parry - the
     * Training Bot's HEAVY always lands on round 3, and at its size it cannot
     * go down before then (see [BootCamp.raisePracticeFight]). After that the
     * fight is theirs. Skip ends the guide for the rest of the fight.
     */
    private fun showPracticeStage() {
        val steps = when (practiceStage) {
            0 -> listOf(
                CoachMark(R.id.botHpBar, R.string.guide_bot_health),
                CoachMark(R.id.ratHpBar, R.string.guide_rat_health),
                CoachMark(R.id.botIntentText, R.string.guide_intent),
                CoachMark(R.id.btnAttack, R.string.guide_attack, mustTap = true)
            )
            1 -> if (battle.specialAvailable) {
                listOf(CoachMark(R.id.btnSpecial, R.string.guide_special, mustTap = true))
            } else {
                listOf(CoachMark(R.id.btnAttack, R.string.guide_attack_again, mustTap = true))
            }
            2 -> if (battle.botIntent == BotIntent.HEAVY) {
                listOf(
                    CoachMark(R.id.botIntentText, R.string.guide_heavy),
                    CoachMark(R.id.btnDefend, R.string.guide_defend, mustTap = true)
                )
            } else {
                emptyList()
            }
            3 -> listOf(CoachMark(R.id.btnAttack, R.string.guide_finish))
            else -> emptyList()
        }
        if (practiceStage >= 3) practiceGuide = false
        CoachMarkOverlay.showIfDue(
            activity = this,
            shouldShow = steps.isNotEmpty(),
            steps = steps,
            onFinish = {},
            onSkip = { practiceGuide = false }
        )
    }

    /** The one callout a round earns, if any - rarest moment first. */
    private fun calloutFor(r: RoundResult) {
        when {
            r.botEnragedNow -> {
                showCallout(R.string.callout_enraged, R.drawable.ic_crown)
                Haptics.play(this, Haptics.Cue.DEFEAT)
            }
            r.parried -> {
                showCallout(R.string.callout_parry, R.drawable.ic_toughness)
                Haptics.play(this, Haptics.Cue.RELIC)
            }
            r.botIntent == BotIntent.REPAIR && r.damageDealt > 0 ->
                showCallout(R.string.callout_interrupted, R.drawable.ic_settings)
        }
    }

    /**
     * Once the rat is down to a quarter of its health, its bar beats -
     * a quiet "careful" rather than another number to read.
     */
    private fun renderHeartbeat() {
        val low = battle.outcome == BattleOutcome.ONGOING &&
            battle.ratHp > 0 && battle.ratHp <= battle.ratMaxHp / 4
        if (low && heartbeat == null) {
            heartbeat = ObjectAnimator.ofFloat(ratHpBar, View.ALPHA, 1f, 0.35f).apply {
                duration = 480
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        } else if (!low && heartbeat != null) {
            heartbeat?.cancel()
            heartbeat = null
            ratHpBar.alpha = 1f
        }
    }

    /**
     * A slow, continuous breathing loop on both portrait frames, so the
     * screen has some life to it between rounds rather than sitting
     * perfectly still. Targets the *frame* each portrait sits in, not the
     * ImageView itself - [lunge] animates the ImageView's own translationY
     * for the attack hop, and a child's local translation composes with its
     * parent's rather than fighting it, so the two motions layer cleanly
     * instead of one animator stomping the other's value.
     *
     * Started once from [onCreate] rather than per fight from
     * [bindStaticViews] - both portrait frames are fixed views for this
     * Activity's whole lifetime, so restarting this on every [loadFight]
     * (including an Arena run's own reload-in-place onward) would only pile
     * up duplicate animators driving the same property.
     */
    private fun startIdleAnimations() {
        val amplitude = -4 * resources.displayMetrics.density
        listOf(botPortraitFrame to 1100L, ratPortraitFrame to 950L).forEach { (view, duration) ->
            val animator = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, amplitude, 0f).apply {
                this.duration = duration
                repeatCount = ValueAnimator.INFINITE
                start()
            }
            idleAnimators += animator
        }
    }

    /**
     * Loads whichever encounter is pending and drops the screen into it.
     *
     * Called once from [onCreate] for an ordinary fight, and again in place -
     * no Activity relaunch - when an Arena run's breather popup continues to
     * the next one. [BattleActivity] is `singleTop`, so `startActivity`ing
     * itself from its own Continue button would not create a second instance
     * at all: it would hand the intent to `onNewIntent` on the very instance
     * already on top, which this class does not override, and the `finish()`
     * that used to follow it would tear down the only instance there was -
     * dropping the player onto whatever sat beneath it in the back stack
     * instead of the next fight. Reloading in place sidesteps that entirely.
     */
    private fun loadFight() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                Encounter.loadFightable(
                    RatRepository.prefs(this@BattleActivity),
                    RatRepository.dao(this@BattleActivity)
                )
            }

            // Either the encounter was auto-resolved from the notification
            // before this screen opened, or its rat is gone - in which case
            // loadFightable has just cleared it rather than leaving it to block
            // every future encounter.
            if (loaded == null) {
                // Nothing left to fight, so no practice fight either - a stale
                // flag would dress the next real Rustbot up as one.
                BootCamp.clearPractice(RatRepository.prefs(this@BattleActivity))
                Toast.makeText(this@BattleActivity, R.string.battle_gone, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            // The Rust King is raised with an ordinary variant name - ArenaRun
            // has no Context to read his real one - so he is named here,
            // before the fight or its result ever reads it.
            encounter = loaded.first.let {
                if (it.bossId == Bosses.RUST_KING.id) it.copy(botName = getString(R.string.boss_rust_king)) else it
            }
            rat = loaded.second

            val prefs = RatRepository.prefs(this@BattleActivity)

            // Whatever the Shop has armed rides on this fight; EncounterResolver
            // burns it when the fight settles. An Arena run past fight one
            // carries the rat's HP in from how the last fight ended - see
            // ArenaRun - rather than the full heal every other fight gets.
            val inArena = ArenaRun.isActive(prefs)

            // Combat still works without a designation - it just picks the
            // strongest rat, as it always did. Said once, here, because this is
            // where a player is looking at the consequence of not having chosen -
            // except in the Arena, where there is no Battle Rat to have chosen in
            // the first place: a run is built around whichever rat was picked at
            // ArenaSelectActivity, not the account-wide designation, and this
            // screen reloads in place for every fight of the run (see loadFight's
            // own doc comment), so without this guard the same toast would have
            // reopened after fight 2, fight 3, every fight after that.
            val practice = !inArena && BootCamp.isPracticePending(prefs)
            // Not on Boot Camp's practice fight either: a brand-new player has
            // one rat and has never heard of a Battle Rat yet.
            if (!inArena && !practice && !BattleRat.isSet(prefs)) {
                Toast.makeText(
                    this@BattleActivity,
                    R.string.toast_no_battle_rat,
                    Toast.LENGTH_SHORT
                ).show()
            }

            // Read before the fight can settle and clear it: the flag this
            // fight was carried into is what the badge shows for its whole
            // length, not whatever is armed by the time the player looks.
            armedBuff = when {
                ShopEffects.wrenchArmed(prefs) -> R.drawable.ic_sparkle to R.string.shop_name_wrench
                ShopEffects.powerSurgeArmed(prefs) -> R.drawable.ic_power to R.string.shop_name_surge
                else -> null
            }
            val startingHp = if (inArena) ArenaRun.carriedHpFor(prefs) else null
            // Gear's own flat Power/HP bonus stacks with whatever the Shop
            // already armed, rather than replacing it - see
            // Loadout.combinedWith's own comment.
            val loadout = ShopEffects.loadoutFor(prefs).combinedWith(GearEffects.combatLoadoutFor(prefs))
            val gearFactionBonuses = GearEffects.factionBonusesFor(prefs, rat.faction)
            battle = encounter.toBattle(
                rat,
                loadout,
                startingHp,
                bonusPower = if (inArena) ArenaRun.arenaPowerBonusFor(rat) else 0,
                bonusMaxHp = if (inArena) ArenaRun.arenaMaxHpBonusFor(rat) else 0,
                lifestealFraction = PermanentBuffs.lifestealFractionFor(prefs),
                incomingDamageReduction = if (inArena) PermanentBuffs.arenaDamageReductionFor(prefs) else 0.0,
                specialMultiplierBonus = gearFactionBonuses.specialMultiplierBonus,
                windfallChanceBonus = gearFactionBonuses.windfallChanceBonus,
                blockChanceBonus = gearFactionBonuses.blockChanceBonus
            )

            // Cleared rather than left standing - reloaded in place, this is
            // still the same Activity instance the last fight's log was
            // written into, and that log has no business floating above this
            // fight's own opening line.
            lines.clear()

            // Opens the log, so the card has something in it before round one
            // and the fight starts by saying what turned up rather than by
            // counting. Fixed for this encounter, not rolled per draw - see
            // RustbotFlavour. Reused as the boss intro's flavour line below,
            // rather than drawing a second one, so the two never disagree.
            val opening = getString(RustbotFlavour.openingFor(encounter), battle.botName)
            lines += opening
            practiceGuide = practice
            practiceStage = 0

            // Null for an ordinary Rustbot and for an Arena milestone fight
            // alike, even though the latter carries a real bossId too (see
            // ArenaRun.rustbotFor) - that id exists only to drive the named
            // Special/DOT/weakness kit inside Battle, not to make an Arena
            // fight look and behave like the ceremonial boss ladder it
            // deliberately stays separate from. Computed once and shared by
            // bindStaticViews' own portrait treatment below and the intro
            // dialog further down, so the two can never disagree about
            // whether this fight is a ceremonial boss.
            // The one exception is the Rust King: the Arena's own final boss
            // gets the full ceremony his ladder cousins do.
            val ceremonialBoss = when {
                encounter.bossId == Bosses.RUST_KING.id -> Bosses.RUST_KING
                inArena -> null
                else -> encounter.bossId?.let { Bosses.byId(it) }
            }

            bindStaticViews(ceremonialBoss)
            render()
            if (practiceGuide) showPracticeStage()

            // The intro replaces the normal drop straight into combat, and only
            // for a boss - an ordinary Rustbot falls straight through to the
            // screen already rendered above, exactly as it always has.
            ceremonialBoss?.let { spec -> showBossIntro(spec, opening) }
        }
    }

    /**
     * The splash shown before a boss fight, in place of the ordinary encounter's
     * silent drop into round one.
     *
     * A plain, non-cancelable [android.app.Dialog] rather than a second Activity
     * - the banked-boss hand-off that gets a fight onto this screen at all was
     * fragile enough to need fixing once already (see [AppScope]), and a second
     * screen in the navigation graph is a second place that fragility could
     * hide. This is presentation laid over a fight that is already fully built
     * and rendered underneath it; dismissing it reveals a screen already ready
     * to play, not one still being set up.
     */
    private fun showBossIntro(spec: BossSpec, flavourLine: String) {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_boss_intro)
        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.Animation_RatKing_Dialog)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialog.findViewById<ImageView>(R.id.bossIntroArt).setImageResource(spec.badgeRes)
        dialog.findViewById<TextView>(R.id.bossIntroName).text = getString(spec.nameRes)
        dialog.findViewById<TextView>(R.id.bossIntroFlavor).text = flavourLine
        dialog.findViewById<MaterialButton>(R.id.bossIntroBeginButton).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun bindStaticViews(ceremonialBoss: BossSpec?) {
        botName.text = battle.botName
        ratName.text = battle.ratName
        ratImage.setImageResource(rat.imageRes)
        botHpBar.max = battle.botMaxHp
        ratHpBar.max = battle.ratMaxHp

        // A new fight's first render should snap both bars straight to full,
        // never tween in from wherever the last fight's bars were left - see
        // animateHpBar and lastBotHpShown's own doc comment.
        lastBotHpShown = -1
        lastRatHpShown = -1

        bindBossPortrait(ceremonialBoss)

        // ic_settings and ic_sparkle both mean other things elsewhere on this
        // screen (Corrosive Charge's own Items-panel icon, the Special
        // button), so a status badge's colour has to be a tint on these
        // ImageViews rather than baked into the drawables - the same reason
        // activeBuffIcon's tint is set here rather than on the vector itself.
        val dotTint = ContextCompat.getColorStateList(this, R.color.ember_deep)
        botDotIcon.imageTintList = dotTint
        ratDotIcon.imageTintList = dotTint
        botChargingIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.brass_bright)

        val buff = armedBuff
        if (buff == null) {
            activeBuffBadge.visibility = View.GONE
        } else {
            val (iconRes, labelRes) = buff
            activeBuffIcon.setImageResource(iconRes)
            // ic_power and ic_sparkle both mean other things elsewhere on this
            // very screen (the Attack and Special buttons), so the teal has to
            // be a tint on this one ImageView rather than baked into the icon.
            activeBuffIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.teal_fill)
            activeBuffLabel.setText(labelRes)
            activeBuffBadge.visibility = View.VISIBLE
        }
    }

    /**
     * Escalates the bot portrait for a ceremonial boss fight: a bigger frame,
     * a brass_bright glow breathing behind it (the same [ArenaBadgeMedallion]
     * cadence an earned Arena badge already pulses at, and the same
     * bg_lantern_glow drawable the streak lantern lights up with - brass_bright
     * is already this app's own colour for "this one is lit"), and the
     * boss's own badge icon - the one [showBossIntro] and [showBossResult]
     * already pair with its name - pinned to the portrait's corner, so the
     * fight itself carries a hint of which boss this is, not just the two
     * screens bookending it.
     *
     * Null for an ordinary Rustbot, which is almost every fight, and reverts
     * the frame to [PLAIN_PORTRAIT_DP] with both add-ons hidden - the state a
     * freshly inflated screen already starts in, but this Activity is reused
     * across every fight an Arena run plays (see [loadFight]'s own doc
     * comment), so a boss fight's own escalation has to be explicitly undone
     * here rather than assumed absent.
     */
    private fun bindBossPortrait(spec: BossSpec?) {
        bossGlowAnimator?.cancel()

        val density = resources.displayMetrics.density
        val frameDp = if (spec != null) BOSS_PORTRAIT_DP else PLAIN_PORTRAIT_DP
        val frameSize = (frameDp * density).roundToInt()
        botPortraitFrame.layoutParams = botPortraitFrame.layoutParams.apply {
            width = frameSize
            height = frameSize
        }

        if (spec == null) {
            bossGlow.visibility = View.GONE
            bossBadge.visibility = View.GONE
            return
        }

        bossBadge.setImageResource(spec.badgeRes)
        bossBadge.imageTintList = ContextCompat.getColorStateList(this, R.color.brass_bright)
        bossBadge.visibility = View.VISIBLE

        bossGlow.visibility = View.VISIBLE
        bossGlow.background.mutate()
        bossGlow.background.alpha = BOSS_GLOW_MIN_ALPHA
        bossGlowAnimator = ObjectAnimator.ofInt(bossGlow.background, "alpha", BOSS_GLOW_MIN_ALPHA, 255).apply {
            duration = BOSS_GLOW_CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun wireActions() {
        btnAttack.setOnClickListener { play(BattleAction.ATTACK) }
        btnDefend.setOnClickListener { play(BattleAction.DEFEND) }
        btnSpecial.setOnClickListener { play(BattleAction.SPECIAL) }
        btnItems.setOnClickListener { showItemsDialog() }
    }

    private fun play(action: BattleAction, item: BattleItem? = null) {
        if (battle.outcome != BattleOutcome.ONGOING) return

        val result = battle.advance(action, item)
        lines += describe(result)
        render()
        animateRound(result)
        calloutFor(result)

        // Boot Camp's guided fight: the next lesson waits for this round's
        // hits and callout to play out first.
        if (practiceGuide && battle.outcome == BattleOutcome.ONGOING) {
            practiceStage += 1
            botCard.postDelayed({
                if (!isFinishing && !isDestroyed && battle.outcome == BattleOutcome.ONGOING) showPracticeStage()
            }, PRACTICE_STAGE_DELAY_MS)
        }

        if (battle.outcome != BattleOutcome.ONGOING) finishBattle()
    }

    /**
     * The battle screen's whole concession to motion: the rat's faction
     * glyph popping over its own portrait when Special fires, a lunge from
     * whichever side just swung, a hit-flash and floating damage number on
     * whichever side just took the swing, and a shake on that same card.
     * Purely cosmetic - reads [r] but never feeds back into [battle], so a
     * skipped or double-fired call here could never change how a fight
     * actually plays out.
     */
    private fun animateRound(r: RoundResult) {
        if (r.action == BattleAction.SPECIAL) flashFactionSpecial()

        if (r.damageDealt > 0) {
            lunge(ratImage, towardOpponent = -1f)
            flash(botHitFlash)
            popDamage(botDamagePopup, r.damageDealt, big = r.action == BattleAction.SPECIAL)
            shake(botCard, big = r.action == BattleAction.SPECIAL)
        }
        if (r.damageTaken > 0) {
            lunge(botImage, towardOpponent = 1f)
            flash(ratHitFlash)
            popDamage(ratDamagePopup, r.damageTaken, big = r.botUsedSpecial)
            shake(ratCard, big = r.botUsedSpecial)
        }
        // The parry's counter lands after the Rustbot's own swing, so it
        // follows it on screen rather than overlapping it.
        if (r.parried && r.counterDamage > 0) {
            botCard.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                lunge(ratImage, towardOpponent = -1f)
                flash(botHitFlash)
                popDamage(botDamagePopup, r.counterDamage, big = true)
                shake(botCard, big = true)
            }, PARRY_COUNTER_DELAY_MS)
        }
    }

    /**
     * A quick hop toward the opponent's card for whichever portrait just
     * landed a hit - the bot card sits above the rat card on this screen, so
     * "toward the opponent" is [towardOpponent] of vertical travel: negative
     * (up, toward the bot) for the rat's own swing, positive (down, toward
     * the rat) for the bot's reply.
     *
     * Targets the ImageView itself rather than its portrait frame, so this
     * composes with that frame's own idle breathing loop instead of the two
     * fighting over one view's translationY - see [startIdleAnimations].
     */
    private fun lunge(view: View, towardOpponent: Float) {
        val distance = 10 * resources.displayMetrics.density * towardOpponent
        view.animate().cancel()
        view.translationY = 0f
        view.animate()
            .translationY(distance)
            .setDuration(90)
            .withEndAction {
                view.animate().translationY(0f).setDuration(140).start()
            }
            .start()
    }

    /** A brief white flash over the portrait that just took a hit. */
    private fun flash(view: View) {
        view.animate().cancel()
        view.alpha = 0.75f
        view.animate().alpha(0f).setStartDelay(60).setDuration(180).start()
    }

    /**
     * The floating "-12" over whichever portrait just took a hit. [big]
     * (a Special or a boss's named move) reads brass_bright instead of the
     * usual ember_deep, the same "this one mattered more" colour
     * [flashFactionSpecial]'s own glyph already uses.
     */
    private fun popDamage(view: TextView, amount: Int, big: Boolean) {
        view.animate().cancel()
        view.text = getString(R.string.battle_damage_popup, amount)
        view.setTextColor(
            ContextCompat.getColor(this, if (big) R.color.brass_bright else R.color.ember_deep)
        )
        view.alpha = 1f
        view.translationY = 0f
        view.scaleX = 1.3f
        view.scaleY = 1.3f
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(120)
            .withEndAction {
                view.animate()
                    .translationY(-24 * resources.displayMetrics.density)
                    .alpha(0f)
                    .setStartDelay(260)
                    .setDuration(340)
                    .start()
            }
            .start()
    }

    /**
     * The rat's own faction glyph - the same icon [CardIcons.faction] draws
     * beside its name everywhere else - popped over its portrait and faded
     * back out. A faction with no glyph (shouldn't happen for a roster rat,
     * but [CardIcons.factionIconRes] can still say so) simply skips the
     * animation rather than showing an empty chip.
     */
    private fun flashFactionSpecial() {
        val resId = CardIcons.factionIconRes(rat.faction) ?: return

        specialGlyph.animate().cancel()
        specialGlyph.setImageResource(resId)
        specialGlyph.imageTintList = ContextCompat.getColorStateList(this, R.color.brass_bright)
        specialGlyph.alpha = 0f
        specialGlyph.scaleX = 0.4f
        specialGlyph.scaleY = 0.4f
        specialGlyph.visibility = View.VISIBLE
        specialGlyph.animate()
            .alpha(1f)
            .scaleX(1.15f)
            .scaleY(1.15f)
            .setDuration(180)
            .withEndAction {
                specialGlyph.animate()
                    .alpha(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay(260)
                    .setDuration(260)
                    .withEndAction { specialGlyph.visibility = View.GONE }
                    .start()
            }
            .start()
    }

    /**
     * A quick side-to-side rattle on whichever card's rat/bot just took a
     * hit. [big] widens it for a Special or a boss's named move, the same
     * "this one mattered more" treatment [popDamage]'s own colour swap gives.
     */
    private fun shake(view: View, big: Boolean = false) {
        val amplitude = (if (big) 12 else 8) * resources.displayMetrics.density
        ObjectAnimator.ofFloat(
            view, View.TRANSLATION_X,
            0f, -amplitude, amplitude, -amplitude * 0.6f, amplitude * 0.6f, 0f
        ).apply {
            duration = 320
            start()
        }
    }

    /**
     * The Items panel: one row per combat item, its held count read fresh
     * every time the dialog opens, using it spends a charge and plays the
     * round exactly like Attack/Defend/Special do.
     */
    private fun showItemsDialog() {
        val prefs = RatRepository.prefs(this)
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_battle_items)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.Animation_RatKing_Dialog)

        val rows = dialog.findViewById<ViewGroup>(R.id.battleItemRows)
        val inflater = layoutInflater

        val entries = listOf(
            Triple(BattleItem.HP_TONIC, ShopEffects.KEY_HP_TONIC, R.string.shop_name_hp_tonic),
            Triple(
                BattleItem.REINFORCED_PLATING, ShopEffects.KEY_REINFORCED_PLATING,
                R.string.shop_name_reinforced_plating
            ),
            Triple(
                BattleItem.CORROSIVE_CHARGE, ShopEffects.KEY_CORROSIVE_CHARGE,
                R.string.shop_name_corrosive_charge
            ),
            Triple(BattleItem.CLEANSE, ShopEffects.KEY_CLEANSE, R.string.shop_name_cleanse)
        )

        entries.forEach { (item, key, nameRes) ->
            val held = ShopEffects.charges(prefs, key)
            val row = inflater.inflate(R.layout.item_battle_item, rows, false)

            row.findViewById<ImageView>(R.id.battleItemIcon).setImageResource(iconFor(item))
            row.findViewById<TextView>(R.id.battleItemLabel).text =
                getString(R.string.battle_item_row, getString(nameRes), held)

            val useButton = row.findViewById<MaterialButton>(R.id.battleItemUseButton)
            useButton.isEnabled = held > 0
            useButton.setOnClickListener {
                ShopEffects.spendCharge(prefs, key)
                dialog.dismiss()
                play(BattleAction.USE_ITEM, item)
            }

            rows.addView(row)
        }

        dialog.findViewById<MaterialButton>(R.id.battleItemsCloseButton).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun iconFor(item: BattleItem): Int = when (item) {
        BattleItem.HP_TONIC -> R.drawable.ic_flask
        BattleItem.REINFORCED_PLATING -> R.drawable.ic_toughness
        BattleItem.CORROSIVE_CHARGE -> R.drawable.ic_settings
        BattleItem.CLEANSE -> R.drawable.ic_sparkle
    }

    private fun describe(r: RoundResult): String {
        // Both directions of the faction cycle share one marker, appended to
        // whichever side's hit actually carried it - the same visible-not-
        // hidden treatment the corrosion line below gets.
        val weakPoint = " " + getString(R.string.battle_weak_point)

        // The rat's name is folded into this clause rather than prefixed
        // uniformly the way the other three actions are, because the item
        // strings already read as a complete sentence on their own (and a
        // shiny rat's name can contain a space, which ruled out patching one
        // back together from a fixed prefix).
        val subject = when (r.action) {
            BattleAction.ATTACK -> "${battle.ratName} attacks for ${r.damageDealt}"
            BattleAction.SPECIAL ->
                "${battle.ratName} unleashes Special for ${r.damageDealt}" +
                    if (r.ratWeaknessBonusApplied) weakPoint else ""
            BattleAction.DEFEND -> "${battle.ratName} braces"
            BattleAction.USE_ITEM -> itemClause(r.itemUsed)
        }
        // Names the Rustbot's Special rather than letting a hit half again as
        // big as usual look like an unexplained spike - a boss's own named
        // move takes priority over the generic "overloads" every Rustbot's
        // Special otherwise gets. A charge round takes priority over the
        // plain "damageTaken <= 0" case below it, or the telegraph would read
        // as the boss simply doing nothing rather than winding up.
        val reply = when {
            r.botCharged -> " — ${getString(R.string.battle_bot_charging, battle.botName)}"
            r.damageTaken <= 0 -> ""
            r.bossMoveNameRes != null ->
                " — ${battle.botName} unleashes ${getString(r.bossMoveNameRes)} for ${r.damageTaken}" +
                    if (r.bossMoveBonusApplied) weakPoint else ""
            r.botUsedSpecial -> " — ${battle.botName} overloads for ${r.damageTaken}"
            else -> " — takes ${r.damageTaken}"
        }
        // Separate lines rather than folded into the reply above: neither
        // corrosion effect is this round's hit, each is a hit already landed
        // still costing something - the requirement was that it read as its
        // own visible event, not a bigger number on the swing that caused it.
        val dot = if (r.dotDamage > 0) "\n" + getString(R.string.battle_dot_tick, r.dotDamage) else ""
        val enemyDot = if (r.enemyDotDamage > 0) {
            "\n" + getString(R.string.battle_enemy_dot_tick, battle.botName, r.enemyDotDamage)
        } else {
            ""
        }
        // A faction Special's own secondary effect, its own line for the same
        // reason the corrosion lines above are - a separate visible event,
        // not a footnote on the swing that triggered it. At most one of
        // these is ever set on a single RoundResult, the same mutual
        // exclusion Battle.advance's own roll already guarantees.
        val factionSpecial = when {
            r.specialWindfall -> "\n" + getString(R.string.battle_special_windfall, battle.ratName)
            r.specialAppliedDot ->
                "\n" + getString(R.string.battle_special_dot_applied, battle.botName)
            r.specialArmedBlock -> "\n" + getString(R.string.battle_special_block_armed)
            r.specialRefundedCooldown -> "\n" + getString(R.string.battle_special_cooldown_refunded)
            else -> ""
        }
        // Its own line, not folded into factionSpecial above - Rusted Fang is
        // account-wide and can land on the same round as any faction's own
        // Special effect, so the two are never mutually exclusive.
        val buffHeal = if (r.buffLifesteal > 0) {
            "\n" + getString(R.string.battle_buff_lifesteal, battle.ratName, r.buffLifesteal)
        } else {
            ""
        }
        // Intents mode's own events, each on its own line like the rest.
        // A parry and a boss's rage can land on the same round, so these
        // stack rather than compete.
        val intentLine = buildString {
            if (r.parried) append("\n").append(getString(R.string.battle_parry, battle.ratName, r.counterDamage))
            if (r.botRepaired > 0) {
                append("\n").append(getString(R.string.battle_repaired, battle.botName, r.botRepaired))
            } else if (r.botIntent == BotIntent.REPAIR && r.damageDealt > 0) {
                append("\n").append(getString(R.string.battle_repair_interrupted))
            }
            if (r.botEnragedNow) append("\n").append(getString(R.string.battle_enraged, battle.botName))
        }
        return "Round ${r.round}: $subject$reply$intentLine$dot$enemyDot$factionSpecial$buffHeal"
    }

    /** The whole clause for whichever item this round spent - see [BattleActivity.play]. */
    private fun itemClause(item: BattleItem?): String = when (item) {
        BattleItem.HP_TONIC -> getString(R.string.battle_used_hp_tonic, battle.ratName)
        BattleItem.REINFORCED_PLATING -> getString(R.string.battle_used_reinforced_plating, battle.ratName)
        BattleItem.CORROSIVE_CHARGE ->
            getString(R.string.battle_used_corrosive_charge, battle.ratName, battle.botName)
        BattleItem.CLEANSE -> getString(R.string.battle_used_cleanse, battle.ratName)
        // Should never be reached - Battle.advance falls a null item back to
        // ATTACK before a RoundResult with USE_ITEM as its action can exist.
        null -> "${battle.ratName} fumbles with an empty pocket"
    }

    /**
     * Drains (or fills, on a Tonic/lifesteal round) [bar] toward [newValue],
     * tweened rather than snapped - the single biggest thing missing from
     * this screen's own "feel" next to an old Pokemon-style fight, where a
     * bar visibly running down is most of what sells a hit landing.
     *
     * [previouslyShown] is what this same bar last actually displayed, per
     * [lastBotHpShown]/[lastRatHpShown]'s own doc comment: -1 (nothing shown
     * yet, i.e. this fight's first render) or an unchanged value both skip
     * the tween and set the bar directly, so a fresh fight opens with full
     * bars already in place rather than visibly filling up to them.
     *
     * Returns [newValue], so a caller can fold the read-and-store into one
     * line rather than needing a separate assignment after.
     */
    private fun animateHpBar(bar: ProgressBar, newValue: Int, previouslyShown: Int): Int {
        if (previouslyShown < 0 || previouslyShown == newValue) {
            bar.progress = newValue
        } else {
            ObjectAnimator.ofInt(bar, "progress", bar.progress, newValue).apply {
                duration = 450
                start()
            }
        }
        return newValue
    }

    private fun render() {
        botStats.text = getString(R.string.battle_stats, battle.botCurrentPower, battle.botHp, battle.botMaxHp)
        ratStats.text = getString(R.string.battle_stats, battle.attackDamage(), battle.ratHp, battle.ratMaxHp)
        lastBotHpShown = animateHpBar(botHpBar, battle.botHp, lastBotHpShown)
        lastRatHpShown = animateHpBar(ratHpBar, battle.ratHp, lastRatHpShown)

        // Status icons - see Battle.botDotActive/ratDotActive/botCharging.
        // Charging takes priority over the DOT badge on the bot's own row: the
        // two can never actually coincide (a charging boss dealt no damage
        // last round to have applied anything with), but reads clearer with an
        // explicit order than relying on that never changing.
        botChargingIcon.visibility = if (battle.botCharging) View.VISIBLE else View.GONE
        botDotIcon.visibility =
            if (!battle.botCharging && battle.botDotActive) View.VISIBLE else View.GONE
        ratDotIcon.visibility = if (battle.ratDotActive) View.VISIBLE else View.GONE
        renderIntent()
        renderHeartbeat()

        // Stays hidden until there is something to read, so the screen never
        // shows an empty card. Driven by the lines actually about to be drawn
        // rather than by the line count: a blank or whitespace-only entry would
        // otherwise pass the count check and render as an empty white box.
        val shown = lines.takeLast(8)
        logView.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        typeInLatestLine(shown)

        val over = battle.outcome != BattleOutcome.ONGOING
        btnAttack.isEnabled = !over
        btnDefend.isEnabled = !over
        btnItems.isEnabled = !over

        // Special doubles as its own cooldown readout.
        btnSpecial.isEnabled = !over && battle.specialAvailable
        // Once the fight is over there is no cooldown to count down, and
        // "Special (0)" read as a bug.
        btnSpecial.text = if (battle.specialAvailable || over || battle.specialCooldownRemaining == 0) {
            getString(R.string.battle_special)
        } else {
            getString(R.string.battle_special_cooldown, battle.specialCooldownRemaining)
        }
    }

    /**
     * The announcement under the Rustbot's health bar - see [Battle.botIntent].
     * Icon and words together, so it never leans on colour alone.
     */
    private fun renderIntent() {
        val intent = battle.botIntent

        // A HEAVY throbs until it lands - the one announcement worth the
        // motion. Anything else stands still.
        if (intent == BotIntent.HEAVY) {
            if (intentPulse == null) {
                // Alpha, not scale: the pill is full width, and growing it
                // pushed its edge past the card on a real phone.
                intentPulse = ObjectAnimator.ofFloat(botIntentText, View.ALPHA, 1f, 0.55f).apply {
                    duration = 420
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    start()
                }
            }
        } else {
            intentPulse?.cancel()
            intentPulse = null
            botIntentText.alpha = 1f
        }

        if (intent == null) {
            botIntentText.visibility = View.GONE
            return
        }
        val (icon, text) = when (intent) {
            BotIntent.STRIKE ->
                R.drawable.ic_power to getString(R.string.battle_intent_strike, battle.botNextDamage())
            // A boss's named move can carry a faction bonus the screen cannot
            // know yet, so it is announced without a number rather than a
            // wrong one.
            BotIntent.HEAVY -> R.drawable.ic_warning to if (battle.isBoss) {
                getString(R.string.battle_intent_heavy_boss)
            } else {
                getString(R.string.battle_intent_heavy, battle.botNextDamage())
            }
            BotIntent.CHARGING -> R.drawable.ic_sparkle to getString(R.string.battle_intent_charging)
            BotIntent.REPAIR -> R.drawable.ic_settings to getString(R.string.battle_intent_repair)
        }
        val size = (18 * resources.displayMetrics.density).toInt()
        val drawable = ContextCompat.getDrawable(this, icon)?.mutate()?.apply { setBounds(0, 0, size, size) }
        botIntentText.setCompoundDrawablesRelative(drawable, null, null, null)
        botIntentText.text = text
        // A HEAVY is the one worth stopping for, so it reads bold as well as
        // carrying its own warning icon.
        botIntentText.setTypeface(null, if (intent == BotIntent.HEAVY) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        botIntentText.visibility = View.VISIBLE
    }

    /**
     * Types [shown]'s last entry out one character at a time, Pokemon-dialogue
     * style, with every earlier entry sitting above it already fully shown.
     *
     * [shown] is always [lines] with a new entry just appended - see every
     * call site of [render] - so "every earlier entry" only ever needs
     * [List.dropLast], never a second animation of its own: by the time an
     * entry stops being the last one, this function has already finished
     * typing it out (or [revealLogInstantly] jumped it there), so replaying
     * it in full costs nothing extra to look at.
     *
     * Cancels whatever this was still typing first. That leaves the previous
     * call's entry exactly where it stopped for one frame, but the very next
     * line this method sets folds that same entry into history in full -
     * [shown]'s second-to-last-or-earlier slice - so the stale partial text
     * is never actually visible.
     */
    private fun typeInLatestLine(shown: List<String>) {
        typewriterJob?.cancel()
        if (shown.isEmpty()) return

        val history = shown.dropLast(1).joinToString("\n")
        val current = shown.last()
        typewriterJob = lifecycleScope.launch {
            for (charCount in 1..current.length) {
                logView.text = if (history.isEmpty()) {
                    current.substring(0, charCount)
                } else {
                    "$history\n${current.substring(0, charCount)}"
                }
                delay(TYPEWRITER_MS)
            }
        }
    }

    /** A tap on the log jumps straight to what [typeInLatestLine] was still typing out. */
    private fun revealLogInstantly() {
        typewriterJob?.cancel()
        logView.text = lines.takeLast(8).joinToString("\n")
    }

    private fun finishBattle() {
        lifecycleScope.launch {
            val prefs = RatRepository.prefs(this@BattleActivity)
            // Read before EncounterResolver.apply can settle the run onward -
            // a win advances ArenaRun's own fight counter, so this is the only
            // point that still names the fight that was just played.
            val arenaFightNumber = if (ArenaRun.isActive(prefs)) ArenaRun.currentFight(prefs) else null
            val practice = arenaFightNumber == null && BootCamp.isPracticePending(prefs)

            val resolution = withContext(Dispatchers.IO) {
                EncounterResolver.apply(
                    this@BattleActivity, encounter, rat, battle,
                    isArenaFight = arenaFightNumber != null
                )
            }
            // Win or lose, the practice fight is over once it settles.
            if (practice) BootCamp.clearPractice(prefs)

            // Everything below is the fight settling exactly as it always has -
            // the payout, the badge, the log line, the revive offer on a loss.
            // A boss result or an Arena run only wraps that in their own
            // ceremonial screen; neither changes any of it.
            val bossSpec = resolution.bossId?.let { Bosses.byId(it) }

            if (resolution.won) {
                GameSounds.play(this@BattleActivity, GameSounds.Cue.VICTORY)
                Haptics.play(this@BattleActivity, Haptics.Cue.VICTORY)
                lines += getString(
                    R.string.battle_won, resolution.ratName, resolution.botName, resolution.reward
                )
                if (resolution.badgeEarned) {
                    bossSpec?.let {
                        lines += getString(R.string.boss_badge_earned, getString(it.nameRes))
                    }
                }
                // A little credit for reading the fight well.
                val parries = battle.log.count { it.parried }
                val interrupts = battle.log.count { it.botIntent == BotIntent.REPAIR && it.damageDealt > 0 }
                if (parries + interrupts > 0) {
                    lines += getString(R.string.battle_read_summary, parries, interrupts)
                }
                if (practice) lines += getString(R.string.bootcamp_battle_done)
                render()
                showCallout(R.string.callout_victory, R.drawable.ic_star)
            } else {
                GameSounds.play(this@BattleActivity, GameSounds.Cue.DEFEAT)
                Haptics.play(this@BattleActivity, Haptics.Cue.DEFEAT)
                lines += EncounterResolver.lossMessage(this@BattleActivity, resolution)
                render()
                showCallout(R.string.callout_defeat, R.drawable.ic_rustbot_silhouette)
            }

            when {
                arenaFightNumber != null -> finishArenaFight(prefs, resolution, arenaFightNumber)
                bossSpec != null -> showBossResult(bossSpec, resolution) {
                    if (!resolution.won) offerRevive(resolution)
                }
                !resolution.won -> offerRevive(resolution)
            }
        }
    }

    /**
     * Routes a settled Arena fight to its popup.
     *
     * A win banks the fight through [ArenaRun.recordWin] and shows either the
     * breather (fight < 15) or the Arena Cleared celebration (fight 15). A
     * loss ends the run outright - Scrap-revive is disabled in the Arena by
     * design, so this never falls through to [offerRevive] the way an
     * ordinary encounter's loss does.
     */
    private suspend fun finishArenaFight(
        prefs: SharedPreferences,
        resolution: EncounterResolver.Resolution,
        fightNumber: Int
    ) {
        if (resolution.won) {
            val outcome = withContext(Dispatchers.IO) {
                ArenaRun.recordWin(
                    RatRepository.dao(this@BattleActivity),
                    prefs,
                    fightNumber,
                    resolution.reward,
                    battle.ratHp,
                    GameEngine.levelOf(prefs)
                )
            }
            if (outcome.cleared) showArenaClearedDialog(outcome) else showArenaBreatherDialog(outcome)
        } else {
            val summary = withContext(Dispatchers.IO) { ArenaRun.endWithLossSummary(prefs) }
            showArenaLossDialog(resolution, summary)
        }
    }

    /** The between-fights popup: this fight's take, run progress, and a way onward. */
    private fun showArenaBreatherDialog(outcome: ArenaFightOutcome) {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_arena_breather)
        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.Animation_RatKing_Dialog)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialog.findViewById<TextView>(R.id.arenaBreatherTitle).text =
            getString(R.string.arena_breather_title, outcome.fightCleared)
        dialog.findViewById<TextView>(R.id.arenaBreatherProgress).text =
            getString(R.string.arena_breather_progress, outcome.fightCleared, ArenaRun.TOTAL_FIGHTS)
        dialog.findViewById<TextView>(R.id.arenaBreatherReward).text =
            getString(R.string.arena_breather_reward, outcome.scrapThisFight)

        outcome.relicEarned?.let { relic ->
            dialog.findViewById<TextView>(R.id.arenaBreatherRelic).apply {
                text = getString(R.string.arena_breather_relic, getString(relic.nameRes))
                visibility = View.VISIBLE
            }
        }

        // The badge line also carries fight 15's Champion's Banner, the
        // first time through - see ArenaRun.CHAMPION_FIGHT.
        val badgeLine = outcome.milestoneFightNewlyEarned?.let { fight ->
            getString(R.string.arena_milestone_earned, getString(arenaMilestoneNameRes(fight)))
        }
        val frameLine = outcome.cosmeticFrame?.let { frame ->
            getString(R.string.arena_cleared_cosmetic, getString(frame.nameRes))
        }
        listOfNotNull(badgeLine, frameLine).takeIf { it.isNotEmpty() }?.let { parts ->
            dialog.findViewById<TextView>(R.id.arenaBreatherMilestone).apply {
                text = parts.joinToString("\n")
                visibility = View.VISIBLE
            }
        }

        dialog.findViewById<MaterialButton>(R.id.arenaBreatherContinueButton).apply {
            text = getString(R.string.btn_arena_continue, outcome.fightCleared + 1)
            setOnClickListener {
                dialog.dismiss()
                loadFight()
            }
        }
        dialog.show()
    }

    /** The fight-15 celebration: final reward, the cosmetic drop, then back to the Workshop. */
    private fun showArenaClearedDialog(outcome: ArenaFightOutcome) {
        GameSounds.play(this, GameSounds.Cue.ARENA_CLEARED)
        Haptics.play(this, Haptics.Cue.ARENA_CLEARED)

        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_arena_cleared)
        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.Animation_RatKing_Dialog)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialog.findViewById<TextView>(R.id.arenaClearedBody).text =
            getString(R.string.arena_cleared_body, battle.ratName, ArenaRun.TOTAL_FIGHTS)
        dialog.findViewById<TextView>(R.id.arenaClearedReward).text =
            getString(R.string.arena_breather_reward, outcome.scrapThisFight)

        outcome.relicEarned?.let { relic ->
            dialog.findViewById<TextView>(R.id.arenaClearedRelic).apply {
                text = getString(R.string.arena_breather_relic, getString(relic.nameRes))
                visibility = View.VISIBLE
            }
        }

        val cosmeticView = dialog.findViewById<TextView>(R.id.arenaClearedCosmetic)
        when {
            outcome.cosmeticFrame != null -> {
                cosmeticView.text =
                    getString(R.string.arena_cleared_cosmetic, getString(outcome.cosmeticFrame.nameRes))
                cosmeticView.visibility = View.VISIBLE
            }
            outcome.cosmeticScrapFallback > 0 -> {
                cosmeticView.text =
                    getString(R.string.arena_cleared_cosmetic_fallback, outcome.cosmeticScrapFallback)
                cosmeticView.visibility = View.VISIBLE
            }
        }

        outcome.milestoneFightNewlyEarned?.let { fight ->
            dialog.findViewById<TextView>(R.id.arenaClearedMilestone).apply {
                text = getString(R.string.arena_milestone_earned, getString(arenaMilestoneNameRes(fight)))
                visibility = View.VISIBLE
            }
        }

        dialog.findViewById<MaterialButton>(R.id.arenaClearedReturnButton).setOnClickListener {
            dialog.dismiss()
            returnToWorkshop()
        }
        dialog.show()
    }

    /** The run-ending loss popup: what was banked before the fall, no continue option. */
    private fun showArenaLossDialog(resolution: EncounterResolver.Resolution, summary: ArenaLossSummary) {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_arena_loss)
        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.Animation_RatKing_Dialog)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialog.findViewById<TextView>(R.id.arenaLossBody).text = getString(
            R.string.arena_loss_body, resolution.ratName, summary.fightReached
        )
        dialog.findViewById<TextView>(R.id.arenaLossSummary).text = if (summary.relicsRunTotal > 0) {
            getString(R.string.arena_loss_summary_with_relics, summary.scrapRunTotal, summary.relicsRunTotal)
        } else {
            getString(R.string.arena_loss_summary, summary.scrapRunTotal)
        }

        val tokensHeld = ShopEffects.charges(RatRepository.prefs(this), ShopEffects.KEY_REVIVE_TOKENS)
        if (tokensHeld > 0) {
            dialog.findViewById<MaterialButton>(R.id.arenaLossReviveButton).apply {
                visibility = View.VISIBLE
                text = getString(R.string.arena_loss_revive_button, tokensHeld)
                setOnClickListener {
                    lifecycleScope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            EncounterResolver.reviveWithToken(this@BattleActivity, rat.id)
                        }
                        if (ok) {
                            Toast.makeText(
                                this@BattleActivity,
                                getString(R.string.arena_loss_revive_done, resolution.ratName),
                                Toast.LENGTH_SHORT
                            ).show()
                            dialog.dismiss()
                            returnToWorkshop()
                        }
                    }
                }
            }
        }

        dialog.findViewById<MaterialButton>(R.id.arenaLossReturnButton).setOnClickListener {
            dialog.dismiss()
            returnToWorkshop()
        }
        dialog.show()
    }

    private fun arenaMilestoneNameRes(fight: Int): Int =
        ArenaRun.MILESTONES.firstOrNull { it.fight == fight }?.nameRes ?: R.string.arena_milestone_15_name

    /** Pops every Arena screen off the stack and returns to the Workshop. */
    private fun returnToWorkshop() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    /**
     * The ceremonial screen a boss fight ends on, bigger than the plain log
     * line an ordinary Rustbot settles for. Shown after [EncounterResolver.apply]
     * has already banked everything - the reward, the badge, the recovery
     * timer - so this is purely how the same result is presented, never a
     * second place any of that gets decided.
     *
     * [onDone] runs once the player dismisses it, which is where the existing
     * revive offer on a loss still belongs - after the ceremony, not instead
     * of it.
     */
    private fun showBossResult(
        spec: BossSpec,
        resolution: EncounterResolver.Resolution,
        onDone: () -> Unit
    ) {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_boss_result)
        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.Animation_RatKing_Dialog)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialog.findViewById<ImageView>(R.id.bossResultArt).setImageResource(spec.badgeRes)
        dialog.findViewById<TextView>(R.id.bossResultTitle).setText(
            if (resolution.won) R.string.boss_result_title_win else R.string.boss_result_title_loss
        )
        dialog.findViewById<TextView>(R.id.bossResultBossName).text = getString(spec.nameRes)
        dialog.findViewById<TextView>(R.id.bossResultBody).text = if (resolution.won) {
            getString(R.string.battle_won, resolution.ratName, resolution.botName, resolution.reward)
        } else {
            EncounterResolver.lossMessage(this, resolution)
        }

        if (resolution.won) {
            dialog.findViewById<TextView>(R.id.bossResultReward).apply {
                text = getString(R.string.boss_result_reward, resolution.reward)
                visibility = View.VISIBLE
            }
        }
        if (resolution.badgeEarned) {
            dialog.findViewById<TextView>(R.id.bossResultBadge).apply {
                text = getString(R.string.boss_badge_earned, getString(spec.nameRes))
                visibility = View.VISIBLE
            }
        }

        dialog.findViewById<MaterialButton>(R.id.bossResultCloseButton).setOnClickListener {
            dialog.dismiss()
        }
        dialog.setOnDismissListener { onDone() }
        dialog.show()
    }

    /** A loss is not final if the player would rather pay to skip the wait. */
    private fun offerRevive(resolution: EncounterResolver.Resolution) {
        val cost = RustbotFactory.reviveCost(
            GameEngine.levelOf(RatRepository.prefs(this))
        )

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.notif_result_title_loss)
            .setMessage(EncounterResolver.lossMessage(this, resolution))
            .setPositiveButton(getString(R.string.battle_revive, cost)) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        EncounterResolver.revive(this@BattleActivity, rat.id)
                    }
                    // The DB half only frees the rat up for next time - see
                    // Battle.revive. This is the half that actually puts the
                    // fight back in the player's hands.
                    if (ok) {
                        battle.revive()
                        lines += getString(R.string.battle_revive_done)
                        render()
                    }
                    Toast.makeText(
                        this@BattleActivity,
                        if (ok) R.string.battle_revive_done else R.string.battle_revive_poor,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.btn_close, null)
            .show()
    }
}
