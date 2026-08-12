package io.github.jmatts94.ratkingrecon

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ledger Tasks: timed jobs the roster qualifies for on its best stats.
 *
 * Distinct from the Scrap Run, which sends one named rat away for four hours.
 * A task checks the collection's best Power, best Toughness or whether a shiny
 * exists, and locks nothing up while it runs.
 *
 * The M1/M2/M3 preference keys are deliberately unchanged by the rename: they
 * hold tasks players already have running.
 */
class LedgerTasksActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    /**
     * What the tasks gate on: best Power, best Toughness, owns-a-shiny.
     *
     * Held twice. The first three leave the Battle Rat out, and are what a task
     * is actually checked against - a rat on combat duty does not count towards
     * the roster's strength. The [allPower] set counts everything, and exists
     * only so a locked task can say whether the designation is the sole reason
     * it is locked.
     */
    private data class RosterStats(
        val power: Int,
        val toughness: Int,
        val shiny: Boolean,
        val allPower: Int,
        val allToughness: Int,
        val allShiny: Boolean
    )

    /**
     * Cached after the first read so the ticker below can redraw without going
     * back to the database every half minute. Only a claim changes it, and that
     * clears the cache itself.
     */
    private var rosterStats: RosterStats? = null

    private val ticker = Handler(Looper.getMainLooper())

    /** Keeps the countdown honest while the screen is open. */
    private val tick = object : Runnable {
        override fun run() {
            rosterStats?.let { bindTasks(it) }
            ticker.postDelayed(this, TICK_MS)
        }
    }

    private companion object {
        const val TICK_MS = 30_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mission)

        prefs = getSharedPreferences("SaveData", Context.MODE_PRIVATE)

        // Wired here rather than in the refresh, so it works while stats load.
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
    }

    /**
     * Everything is drawn from here rather than from onCreate.
     *
     * Coming back to a screen that was left running used to show a frozen
     * countdown and a button still marked "Exploring…" long after the task had
     * finished; now returning to it redraws, and the ticker keeps it moving.
     */
    override fun onResume() {
        super.onResume()
        LedgerTasks.rerollForNewDay(prefs)
        refresh()
        ticker.postDelayed(tick, TICK_MS)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    private fun refresh() {
        // Total held, not distinct kinds. The old label said "recovered" while
        // showing a set size that could never pass four however many dropped.
        findViewById<TextView>(R.id.relicCountText).text =
            getString(R.string.task_relics, Relics.total(prefs))

        val cached = rosterStats
        if (cached != null) {
            bindTasks(cached)
            return
        }

        // Aggregated in SQL rather than by loading every rat, and off the main
        // thread because Room insists.
        lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) {
                val dao = RatRepository.dao(this@LedgerTasksActivity)
                // NONE is -1 and no row can hold it, so "everything" and
                // "everything but the Battle Rat" are the same query twice.
                val excluded = BattleRat.exclusionId(dao, prefs)
                RosterStats(
                    power = dao.maxPowerExcluding(excluded),
                    toughness = dao.maxToughnessExcluding(excluded),
                    shiny = dao.ownsShinyExcluding(excluded),
                    allPower = dao.maxPowerExcluding(BattleRat.NONE),
                    allToughness = dao.maxToughnessExcluding(BattleRat.NONE),
                    allShiny = dao.ownsShinyExcluding(BattleRat.NONE)
                )
            }
            rosterStats = stats
            bindTasks(stats)
        }
    }

    private fun bindTasks(stats: RosterStats) {
        setupTask(
            LedgerTasks.M1, stats.power, stats.allPower,
            findViewById(R.id.titleM1), findViewById(R.id.reqM1),
            findViewById(R.id.rewardM1), findViewById(R.id.btnMission1),
            R.string.stat_power
        )

        setupTask(
            LedgerTasks.M2, stats.toughness, stats.allToughness,
            findViewById(R.id.titleM2), findViewById(R.id.reqM2),
            findViewById(R.id.rewardM2), findViewById(R.id.btnMission2),
            R.string.stat_toughness
        )

        // The shiny task has no numeric stat, so it passes a value that clears
        // its stored requirement of 1 only when a shiny actually exists.
        setupTask(
            LedgerTasks.M3, if (stats.shiny) 1 else 0, if (stats.allShiny) 1 else 0,
            findViewById(R.id.titleM3), findViewById(R.id.reqM3),
            findViewById(R.id.rewardM3), findViewById(R.id.btnMission3),
            statNameRes = 0
        )
    }

    /**
     * [unrestrictedStat] is the same measure counting the Battle Rat.
     *
     * Used for one thing: telling a player whose task is locked that their own
     * designation is what locked it. Without that the requirement line simply
     * stops being met, with nothing on screen connecting it to the rat they put
     * on combat duty.
     */
    private fun setupTask(
        tier: LedgerTaskTier, playerStat: Int, unrestrictedStat: Int,
        titleTxt: TextView, reqTxt: TextView, rewardTxt: TextView, btn: Button,
        @StringRes statNameRes: Int
    ) {
        val isActive = LedgerTasks.isRunning(prefs, tier)
        val endTime = prefs.getLong(LedgerTasks.endTimeKey(tier.id), 0L)
        val offer = LedgerTasks.stored(prefs, tier)
        val reqAmount = offer.requirement
        val rewardAmount = offer.reward
        val now = System.currentTimeMillis()

        titleTxt.text = offer.title.ifBlank { getString(R.string.task_unknown_sector) }
        reqTxt.text = if (statNameRes == 0) {
            getString(R.string.task_requires_shiny)
        } else {
            getString(R.string.task_requires_stat, reqAmount, getString(statNameRes))
        }
        rewardTxt.text = getString(R.string.task_reward_line, tier.hours, rewardAmount)

        // Re-enabled explicitly: the screen redraws in place now rather than
        // being recreated, so a button disabled by a previous pass would stay
        // disabled once the task became claimable.
        btn.isEnabled = true

        when {
            isActive && now >= endTime -> {
                btn.text = getString(R.string.task_claim)
                tintButton(btn, R.color.amber)
                btn.setOnClickListener { claim(tier, rewardAmount) }
            }

            isActive -> {
                val minsLeft = ((endTime - now) / (1000 * 60)).toInt()
                btn.text = getString(R.string.task_exploring, minsLeft)
                tintButton(btn, R.color.card_border)
                btn.isEnabled = false
            }

            playerStat >= reqAmount -> {
                btn.text = getString(R.string.task_start)
                tintButton(btn, R.color.amber)
                btn.setOnClickListener { start(tier) }
            }

            else -> {
                btn.text = getString(R.string.task_too_weak)
                tintButton(btn, R.color.disabled_fill)
                btn.isEnabled = false

                // The roster does clear this task - the Battle Rat is simply
                // not part of the roster for these purposes. Say so, rather
                // than leaving the player to work out why a rat they can see
                // does not count.
                if (unrestrictedStat >= reqAmount) {
                    reqTxt.text = getString(R.string.task_battle_rat_locked)
                }
            }
        }
    }

    private fun start(tier: LedgerTaskTier) {
        val endAt = System.currentTimeMillis() + tier.durationMs
        prefs.edit()
            .putBoolean(LedgerTasks.activeKey(tier.id), true)
            .putLong(LedgerTasks.endTimeKey(tier.id), endAt)
            .apply()

        // Nothing else is watching the clock, so the alert is armed here.
        LedgerTaskAlarms.schedule(this, tier.id, endAt)
        refresh()
    }

    private fun claim(tier: LedgerTaskTier, rewardAmount: Int) {
        val editor = prefs.edit()

        var message = getString(R.string.task_success, rewardAmount)

        // Scaled by tier now, and counted rather than collected: a second Rusted
        // Gear used to be dropped on the floor by the Set that stored it.
        Relics.rollFor(tier)?.let { relic ->
            Relics.grant(prefs, editor, relic)
            message = getString(R.string.task_success_relic, rewardAmount, getString(relic.nameRes))
            GameSounds.play(this, GameSounds.Cue.RELIC)
        }

        editor.putInt(GameEngine.KEY_SCRAP, prefs.getInt(GameEngine.KEY_SCRAP, 0) + rewardAmount)
        editor.putBoolean(LedgerTasks.activeKey(tier.id), false)

        // Rerolled here rather than waiting for the next daily pass, so the slot
        // offers a new job the moment this one is banked.
        LedgerTasks.reroll(editor, tier)
        editor.apply()

        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        refresh()
    }

    /**
     * Sets a MaterialButton's fill.
     *
     * setBackgroundColor() replaces the whole background drawable on a
     * MaterialButton, which throws away its rounded corners, so the tint has to
     * be applied as a tint list instead.
     */
    private fun tintButton(btn: Button, colorRes: Int) {
        btn.backgroundTintList = ContextCompat.getColorStateList(this, colorRes)
    }
}
