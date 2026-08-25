package io.github.jmatts94.ratkingrecon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
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
class TasksActivity : AppCompatActivity() {

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

    /**
     * Keeps the countdowns honest while the screen is open.
     *
     * Both sections move on their own - a Ledger Task towards being claimable
     * and a contract towards its deadline - so both are redrawn.
     */
    private val tick = object : Runnable {
        override fun run() {
            rosterStats?.let { bindTasks(it) }
            bindContracts()
            ticker.postDelayed(this, TICK_MS)
        }
    }

    private companion object {
        const val TICK_MS = 30_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tasks)

        prefs = getSharedPreferences("SaveData", Context.MODE_PRIVATE)

        // Wired here rather than in the refresh, so it works while stats load.
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
    }

    // ---- contracts -----------------------------------------------------------

    /**
     * Draws whichever face the Contracts section should be showing.
     *
     * The board and the contract in progress were two dialogs opened from the
     * home screen; they are two views in one section now, and exactly one of
     * them is visible. Nothing about how a contract is accepted, measured or
     * paid has moved - [ActiveContract] and [GameEngine] still own all of that.
     */
    private fun bindContracts() {
        val running = ActiveContract.load(prefs)
        val offers = findViewById<View>(R.id.contractOffers)
        val active = findViewById<View>(R.id.contractActive)

        findViewById<TextView>(R.id.bountyStepCountText).text =
            DailySteps.today(prefs).toString()

        if (running == null) {
            offers.visibility = View.VISIBLE
            active.visibility = View.GONE

            // Rolled fresh on every visit, exactly as opening the board used to
            // re-roll it, so the names and payouts change each time.
            bindOffer(R.id.btnBountyShort, Bounties.SHORT.roll())
            bindOffer(R.id.btnBountyMedium, Bounties.MEDIUM.roll())
            bindOffer(R.id.btnBountyLong, Bounties.LONG.roll())
            return
        }

        offers.visibility = View.GONE
        active.visibility = View.VISIBLE
        drawActiveContract(running)
    }

    /**
     * Puts a rolled offer on a button, label and payout in step.
     *
     * The reward shown is the same value [ActiveContract.accept] banks, so the
     * board cannot advertise a figure it does not pay.
     */
    private fun bindOffer(buttonId: Int, offer: BountyOffer) {
        val button = findViewById<Button>(buttonId)
        button.text = getString(
            R.string.bounty_label,
            offer.name,
            offer.steps.toInt(),
            offer.minutes,
            offer.reward
        )
        button.setOnClickListener {
            ActiveContract.accept(
                prefs = prefs,
                offer = offer,
                currentTotalSteps = GameEngine.totalStepsOf(prefs)
            )
            Toast.makeText(
                this,
                getString(R.string.toast_contract_accepted, offer.minutes),
                Toast.LENGTH_LONG
            ).show()
            bindContracts()
        }
    }

    /**
     * Re-reads the running contract rather than trusting a captured copy: it may
     * have been paid out or run out of time since this screen was drawn.
     */
    private fun drawActiveContract(contract: ActiveContract) {
        findViewById<TextView>(R.id.activeContractName).text =
            contract.name.ifBlank { getString(R.string.contract_active_unnamed) }
        findViewById<TextView>(R.id.activeContractReward).text =
            getString(R.string.contract_active_reward, contract.reward)

        val total = GameEngine.totalStepsOf(prefs)
        val stepsText = findViewById<TextView>(R.id.activeContractSteps)
        val bar = findViewById<ProgressBar>(R.id.activeContractProgress)

        if (contract.knowsRequirement) {
            stepsText.text = getString(
                R.string.contract_active_steps,
                contract.stepsWalked(total),
                contract.requiredSteps.toInt()
            )
            bar.visibility = View.VISIBLE
            bar.progress = contract.percentComplete(total)
        } else {
            // Accepted before the requirement was recorded, so the only honest
            // thing to show is the distance still owed.
            stepsText.text =
                getString(R.string.contract_active_steps_left, contract.stepsRemaining(total))
            bar.visibility = View.GONE
        }

        findViewById<TextView>(R.id.activeContractTime).text = if (contract.hasExpired()) {
            getString(R.string.contract_active_expired)
        } else {
            getString(R.string.contract_active_time, contract.minutesRemaining())
        }
    }

    /**
     * Keeps the contract section honest while the screen is open.
     *
     * The service banks steps whether or not anything is looking, so a contract
     * can be paid out or expire underneath this screen. Registered only while
     * the screen is up, and it redraws rather than assuming what changed.
     */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = bindContracts()
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

        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(StepTrackerService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        LedgerTasks.rerollForNewDay(prefs)
        bindContracts()
        refresh()
        ticker.postDelayed(tick, TICK_MS)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
        unregisterReceiver(stateReceiver)
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
                val dao = RatRepository.dao(this@TasksActivity)
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
                btn.setOnClickListener { pickRatFor(tier) }
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

    /**
     * Which flat Foundry-born EXP amount a tier's slot pays - see
     * [TaskBonuses.expFor]. A plain id switch rather than a field on
     * [LedgerTaskTier] itself, since nothing else about a tier needs to know
     * this and [LedgerTaskTier] is meant to stay a plain data description.
     */
    private fun activityFor(tier: LedgerTaskTier): TaskBonuses.Activity = when (tier.id) {
        "M1" -> TaskBonuses.Activity.LEDGER_M1
        "M2" -> TaskBonuses.Activity.LEDGER_M2
        else -> TaskBonuses.Activity.LEDGER_M3
    }

    /**
     * Offers to name one rat for [tier]'s bonus before starting it - see
     * [TaskBonuses]. Purely optional: dismissing without picking, or picking
     * "No rat", starts the job at baseline exactly as it always has.
     *
     * The rat named here is not reserved by this - a Ledger Task has never
     * locked anything up, and naming one for a bonus does not change that.
     */
    private fun pickRatFor(tier: LedgerTaskTier) {
        lifecycleScope.launch {
            val roster = withContext(Dispatchers.IO) { RatRepository.dao(this@TasksActivity).all() }

            val labels = ArrayList<String>(roster.size + 1)
            labels += getString(R.string.task_pick_no_rat)
            labels += roster.map { rat ->
                val bonus = TaskBonuses.descriptionFor(rat.faction)?.let { getString(it) }
                if (bonus != null) "${rat.name} — $bonus" else rat.name
            }

            android.app.AlertDialog.Builder(this@TasksActivity)
                .setTitle(R.string.task_pick_rat_title)
                .setItems(labels.toTypedArray()) { _, which ->
                    val ratId = if (which == 0) LedgerTasks.NO_RAT else roster[which - 1].id
                    start(tier, ratId)
                }
                .setNegativeButton(R.string.btn_close, null)
                .show()
        }
    }

    private fun start(tier: LedgerTaskTier, ratId: Long) {
        lifecycleScope.launch {
            val faction = withContext(Dispatchers.IO) {
                if (ratId == LedgerTasks.NO_RAT) {
                    null
                } else {
                    RatRepository.dao(this@TasksActivity).byId(ratId)?.faction
                }
            }

            val startTime = System.currentTimeMillis()
            val endAt = TaskBonuses.endTimeFor(startTime, tier.durationMs, faction)
            prefs.edit()
                .putBoolean(LedgerTasks.activeKey(tier.id), true)
                .putLong(LedgerTasks.endTimeKey(tier.id), endAt)
                .putLong(LedgerTasks.assignedRatKey(tier.id), ratId)
                .apply()

            // Nothing else is watching the clock, so the alert is armed here.
            // Armed for the bonus-adjusted time, so a Scavenger or an instant
            // Brawler completion does not leave a stale alert far in the future.
            LedgerTaskAlarms.schedule(this@TasksActivity, tier.id, endAt)
            refresh()
        }
    }

    private fun claim(tier: LedgerTaskTier, rewardAmount: Int) {
        lifecycleScope.launch {
            val dao = RatRepository.dao(this@TasksActivity)
            val ratId = prefs.getLong(LedgerTasks.assignedRatKey(tier.id), LedgerTasks.NO_RAT)
            val faction = withContext(Dispatchers.IO) {
                if (ratId == LedgerTasks.NO_RAT) null else dao.byId(ratId)?.faction
            }

            val reward = TaskBonuses.scrapFor(rewardAmount, faction)
            val relicChance = TaskBonuses.relicChanceFor(tier.relicChance, faction)
            val relic = withContext(Dispatchers.IO) { Relics.rollFor(relicChance) }

            val editor = prefs.edit()
            relic?.let { Relics.grant(prefs, editor, it) }
            editor.putInt(GameEngine.KEY_SCRAP, prefs.getInt(GameEngine.KEY_SCRAP, 0) + reward)
            editor.putBoolean(LedgerTasks.activeKey(tier.id), false)
            editor.remove(LedgerTasks.assignedRatKey(tier.id))

            // Rerolled here rather than waiting for the next daily pass, so the
            // slot offers a new job the moment this one is banked.
            LedgerTasks.reroll(editor, tier)
            editor.apply()

            if (relic != null) GameSounds.play(this@TasksActivity, GameSounds.Cue.RELIC)

            // Foundry-born's bonus EXP, banked through the same path a walked
            // step would use - see GameEngine.bankBonusExp. A hatch from this
            // gets no home-screen reveal (nothing broadcasts off this screen),
            // just this toast naming the new rat - a deliberately small
            // consolation for what should be a rare event given how small the
            // bonus is.
            val expBonus = TaskBonuses.expFor(activityFor(tier), faction)
            val hatched = if (expBonus > 0) {
                withContext(Dispatchers.IO) { GameEngine.bankBonusExp(dao, prefs, expBonus) }
            } else {
                null
            }

            var message = if (relic != null) {
                getString(R.string.task_success_relic, reward, getString(relic.nameRes))
            } else {
                getString(R.string.task_success, reward)
            }
            if (hatched != null) {
                message += "\n" + getString(R.string.task_success_hatch, hatched.name)
            }

            Toast.makeText(this@TasksActivity, message, Toast.LENGTH_LONG).show()

            // A hatch can change the roster's best stats, so the cache that
            // would otherwise skip straight back to the old numbers has to go.
            rosterStats = null
            refresh()
        }
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
