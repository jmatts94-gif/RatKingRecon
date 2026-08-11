package io.github.jmatts94.ratkingrecon

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

            // Combat still works without a designation - it just picks the
            // strongest rat, as it always did. Said once, here, because this is
            // where a player is looking at the consequence of not having chosen.
            if (!BattleRat.isSet(RatRepository.prefs(this@BattleActivity))) {
                Toast.makeText(
                    this@BattleActivity,
                    R.string.toast_no_battle_rat,
                    Toast.LENGTH_SHORT
                ).show()
            }
            // Whatever the Shop has armed rides on this fight; EncounterResolver
            // burns it when the fight settles.
            battle = encounter.toBattle(
                rat,
                ShopEffects.loadoutFor(RatRepository.prefs(this@BattleActivity))
            )

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

            if (resolution.won) {
                lines += getString(
                    R.string.battle_won, resolution.ratName, resolution.botName, resolution.reward
                )
                if (resolution.badgeEarned) {
                    Bosses.byId(resolution.bossId)?.let {
                        lines += getString(R.string.boss_badge_earned, getString(it.nameRes))
                    }
                }
                render()
            } else {
                lines += EncounterResolver.lossMessage(this@BattleActivity, resolution)
                render()
                offerRevive(resolution)
            }
        }
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
