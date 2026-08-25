package io.github.jmatts94.ratkingrecon

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
        btnLeave = findViewById(R.id.btnLeave)

        btnLeave.setOnClickListener { finish() }

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
            // burns it when the fight settles.
            battle = encounter.toBattle(rat, ShopEffects.loadoutFor(prefs))

            // Opens the log, so the card has something in it before round one
            // and the fight starts by saying what turned up rather than by
            // counting. Fixed for this encounter, not rolled per draw - see
            // RustbotFlavour. Reused as the boss intro's flavour line below,
            // rather than drawing a second one, so the two never disagree.
            val opening = getString(RustbotFlavour.openingFor(encounter), battle.botName)
            lines += opening

            bindStaticViews()
            wireActions()
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
    }

    private fun play(action: BattleAction) {
        if (battle.outcome != BattleOutcome.ONGOING) return

        val result = battle.advance(action)
        lines += describe(result)
        render()

        if (battle.outcome != BattleOutcome.ONGOING) finishBattle()
    }

    private fun describe(r: RoundResult): String {
        // Both directions of the faction cycle share one marker, appended to
        // whichever side's hit actually carried it - the same visible-not-
        // hidden treatment the corrosion line below gets.
        val weakPoint = " " + getString(R.string.battle_weak_point)

        val verb = when (r.action) {
            BattleAction.ATTACK -> "attacks for ${r.damageDealt}"
            BattleAction.SPECIAL ->
                "unleashes Special for ${r.damageDealt}" + if (r.ratWeaknessBonusApplied) weakPoint else ""
            BattleAction.DEFEND -> "braces"
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
        // A separate line rather than folded into the reply above: the corrosion
        // is not this round's hit, it is last landing still costing something -
        // the requirement was that it read as its own visible event, not a
        // bigger number on the swing that caused it.
        val dot = if (r.dotDamage > 0) "\n" + getString(R.string.battle_dot_tick, r.dotDamage) else ""
        return "Round ${r.round}: ${battle.ratName} $verb$reply$dot"
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
            val resolution = withContext(Dispatchers.IO) {
                EncounterResolver.apply(this@BattleActivity, encounter, rat, battle)
            }

            // Everything below is the fight settling exactly as it always has -
            // the payout, the badge, the log line, the revive offer on a loss.
            // A boss result only wraps that in the ceremonial screen; it does
            // not change any of it.
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

            if (bossSpec != null) {
                showBossResult(bossSpec, resolution) {
                    if (!resolution.won) offerRevive(resolution)
                }
            } else if (!resolution.won) {
                offerRevive(resolution)
            }
        }
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
