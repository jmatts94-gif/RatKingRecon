package com.example.ratkingrecon

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var logView: TextView
    private lateinit var btnAttack: MaterialButton
    private lateinit var btnDefend: MaterialButton
    private lateinit var btnSpecial: MaterialButton
    private lateinit var btnLeave: MaterialButton

    private val lines = mutableListOf<String>()

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
        logView = findViewById(R.id.battleLog)
        btnAttack = findViewById(R.id.btnAttack)
        btnDefend = findViewById(R.id.btnDefend)
        btnSpecial = findViewById(R.id.btnSpecial)
        btnLeave = findViewById(R.id.btnLeave)

        btnLeave.setOnClickListener { finish() }

        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val prefs = RatRepository.prefs(this@BattleActivity)
                val enc = Encounter.load(prefs) ?: return@withContext null
                val fighter = RatRepository.dao(this@BattleActivity).byId(enc.ratId)
                    ?: return@withContext null
                enc to fighter
            }

            // The encounter may have been auto-resolved from the notification,
            // or the rat spliced away, between the alert and this screen.
            if (loaded == null) {
                Toast.makeText(this@BattleActivity, R.string.battle_gone, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            encounter = loaded.first
            rat = loaded.second
            battle = encounter.toBattle(rat)

            bindStaticViews()
            wireActions()
            render()
        }
    }

    private fun bindStaticViews() {
        botName.text = battle.botName
        ratName.text = battle.ratName
        ratImage.setImageResource(rat.imageRes)
        botHpBar.max = battle.botMaxHp
        ratHpBar.max = battle.ratMaxHp
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
        val verb = when (r.action) {
            BattleAction.ATTACK -> "attacks for ${r.damageDealt}"
            BattleAction.SPECIAL -> "unleashes Special for ${r.damageDealt}"
            BattleAction.DEFEND -> "braces"
        }
        val reply = if (r.damageTaken > 0) " — takes ${r.damageTaken}" else ""
        return "Round ${r.round}: ${battle.ratName} $verb$reply"
    }

    private fun render() {
        botStats.text = getString(R.string.battle_stats, battle.botPower, battle.botHp, battle.botMaxHp)
        ratStats.text = getString(R.string.battle_stats, battle.attackDamage(), battle.ratHp, battle.ratMaxHp)
        botHpBar.progress = battle.botHp
        ratHpBar.progress = battle.ratHp
        // Stays hidden until round 1, so the screen never shows an empty card.
        logView.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        logView.text = lines.takeLast(8).joinToString("\n")

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

            if (resolution.won) {
                lines += getString(
                    R.string.battle_won, resolution.ratName, resolution.botName, resolution.reward
                )
                render()
            } else {
                lines += getString(R.string.battle_lost, resolution.ratName, resolution.botName)
                render()
                offerRevive()
            }
        }
    }

    /** A loss is not final if the player would rather pay to skip the wait. */
    private fun offerRevive() {
        val cost = RustbotFactory.reviveCost(
            GameEngine.levelOf(RatRepository.prefs(this))
        )

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.notif_result_title_loss)
            .setMessage(getString(R.string.battle_lost, rat.name, encounter.botName))
            .setPositiveButton(getString(R.string.battle_revive, cost)) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        EncounterResolver.revive(this@BattleActivity, rat.id)
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
