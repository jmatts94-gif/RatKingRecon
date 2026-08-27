package io.github.jmatts94.ratkingrecon

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The manual battle screen.
 *
 * Owns no combat rules of its own: it drives the same [Battle] that
 * Auto-Resolve does and only renders the result, so the two paths cannot drift
 * apart.
 */
class BattleActivity : AppCompatActivity() {

    private lateinit var battle: Battle
    private lateinit var encounter: Encounter
    private lateinit var rat: RatEntity

    private lateinit var botName: TextView
    private lateinit var botStats: TextView
    private lateinit var botHpBar: ProgressBar
    private lateinit var ratName: TextView
    private lateinit var ratStats: TextView
    private lateinit var ratHpBar: ProgressBar
    private lateinit var ratImage: ImageView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_battle)

        botName = findViewById(R.id.botName)
        botStats = findViewById(R.id.botStats)
        botHpBar = findViewById(R.id.botHpBar)
        ratName = findViewById(R.id.ratName)
        ratStats = findViewById(R.id.ratStats)
        ratHpBar = findViewById(R.id.ratHpBar)
        ratImage = findViewById(R.id.ratImage)
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
        wireActions()

        loadFight()
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
                Toast.makeText(this@BattleActivity, R.string.battle_gone, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            encounter = loaded.first
            rat = loaded.second

            val prefs = RatRepository.prefs(this@BattleActivity)

            // Combat still works without a designation - it just picks the
            // strongest rat, as it always did. Said once, here, because this is
            // where a player is looking at the consequence of not having chosen.
            if (!BattleRat.isSet(prefs)) {
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

            // Whatever the Shop has armed rides on this fight; EncounterResolver
            // burns it when the fight settles. An Arena run past fight one
            // carries the rat's HP in from how the last fight ended - see
            // ArenaRun - rather than the full heal every other fight gets.
            val startingHp = if (ArenaRun.isActive(prefs)) ArenaRun.carriedHpFor(prefs) else null
            battle = encounter.toBattle(rat, ShopEffects.loadoutFor(prefs), startingHp)

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

            bindStaticViews()
            render()

            // The intro replaces the normal drop straight into combat, and only
            // for a boss - an ordinary Rustbot falls straight through to the
            // screen already rendered above, exactly as it always has.
            encounter.bossId?.let { Bosses.byId(it) }?.let { spec ->
                showBossIntro(spec, opening)
            }
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

    private fun bindStaticViews() {
        botName.text = battle.botName
        ratName.text = battle.ratName
        ratImage.setImageResource(rat.imageRes)
        botHpBar.max = battle.botMaxHp
        ratHpBar.max = battle.ratMaxHp

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

        if (battle.outcome != BattleOutcome.ONGOING) finishBattle()
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
        // Special otherwise gets.
        val reply = when {
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
        return "Round ${r.round}: $subject$reply$dot$enemyDot"
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

    private fun render() {
        botStats.text = getString(R.string.battle_stats, battle.botPower, battle.botHp, battle.botMaxHp)
        ratStats.text = getString(R.string.battle_stats, battle.attackDamage(), battle.ratHp, battle.ratMaxHp)
        botHpBar.progress = battle.botHp
        ratHpBar.progress = battle.ratHp
        // Stays hidden until there is something to read, so the screen never
        // shows an empty card. Driven by the text actually about to be drawn
        // rather than by the line count: a blank or whitespace-only entry would
        // otherwise pass the count check and render as an empty white box.
        val log = lines.takeLast(8).joinToString("\n")
        logView.text = log
        logView.visibility = if (log.isBlank()) View.GONE else View.VISIBLE

        val over = battle.outcome != BattleOutcome.ONGOING
        btnAttack.isEnabled = !over
        btnDefend.isEnabled = !over
        btnItems.isEnabled = !over

        // Special doubles as its own cooldown readout.
        btnSpecial.isEnabled = !over && battle.specialAvailable
        btnSpecial.text = if (battle.specialAvailable) {
            getString(R.string.battle_special)
        } else {
            getString(R.string.battle_special_cooldown, battle.specialCooldownRemaining)
        }
    }

    private fun finishBattle() {
        lifecycleScope.launch {
            val prefs = RatRepository.prefs(this@BattleActivity)
            // Read before EncounterResolver.apply can settle the run onward -
            // a win advances ArenaRun's own fight counter, so this is the only
            // point that still names the fight that was just played.
            val arenaFightNumber = if (ArenaRun.isActive(prefs)) ArenaRun.currentFight(prefs) else null

            val resolution = withContext(Dispatchers.IO) {
                EncounterResolver.apply(this@BattleActivity, encounter, rat, battle)
            }

            // Everything below is the fight settling exactly as it always has -
            // the payout, the badge, the log line, the revive offer on a loss.
            // A boss result or an Arena run only wraps that in their own
            // ceremonial screen; neither changes any of it.
            val bossSpec = resolution.bossId?.let { Bosses.byId(it) }

            if (resolution.won) {
                GameSounds.play(this@BattleActivity, GameSounds.Cue.VICTORY)
                lines += getString(
                    R.string.battle_won, resolution.ratName, resolution.botName, resolution.reward
                )
                if (resolution.badgeEarned) {
                    bossSpec?.let {
                        lines += getString(R.string.boss_badge_earned, getString(it.nameRes))
                    }
                }
                render()
            } else {
                lines += EncounterResolver.lossMessage(this@BattleActivity, resolution)
                render()
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

        outcome.milestoneFightNewlyEarned?.let { fight ->
            dialog.findViewById<TextView>(R.id.arenaBreatherMilestone).apply {
                text = getString(R.string.arena_milestone_earned, getString(arenaMilestoneNameRes(fight)))
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
