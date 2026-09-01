package io.github.jmatts94.ratkingrecon

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picks one rat to name for a Ledger Task's faction bonus, or none at all.
 *
 * A screen of its own rather than the five-row faction dialog it replaces -
 * that dialog resolved a chosen faction to that faction's best rat
 * automatically, so a player could name a bonus but never the actual rat
 * carrying it. This shows every owned rat instead, the same grid the Binder
 * and the Fusion Pot already use, and lets the player tap the one they mean.
 *
 * Every rat is shown, Battle Rat and any already busy included - naming one
 * here has never locked it up, see [TasksActivity]'s own doc comment on that.
 *
 * Returns [EXTRA_RAT_ID] via [setResult] - a real id, or [LedgerTasks.NO_RAT]
 * for the explicit "no rat" choice - and [Activity.RESULT_CANCELED] for a
 * dismissal with nothing picked. The caller already knows which task tier
 * this was for, so this screen does not need to.
 *
 * [EXTRA_REQUIREMENT_KIND]/[EXTRA_REQUIREMENT_AMOUNT] carry the tier's own
 * Start-button requirement, so a rat that would not itself clear it - a
 * middling Toughness rat under a "Requires: 7+ Toughness" job - reads as
 * dimmed rather than an equally valid pick. The job unlocked off the
 * roster's best stat before this screen ever opened, not off whichever rat
 * ends up named, so nothing here stops that rat from still being sent; it
 * would just have been an odd thing to let the grid suggest.
 */
class TaskRatPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RAT_ID = "rat_id"
        const val EXTRA_REQUIREMENT_KIND = "requirement_kind"
        const val EXTRA_REQUIREMENT_AMOUNT = "requirement_amount"
        const val REQUIRES_POWER = "POWER"
        const val REQUIRES_TOUGHNESS = "TOUGHNESS"
        const val REQUIRES_SHINY = "SHINY"
    }

    /** Drives the animated Binder frames, if one is equipped - see [FrameAnimator]. */
    private val frameAnimator = FrameAnimator()

    private lateinit var adapter: RatCardAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task_rat_pick)
        EdgeToEdge.apply(this)

        val prefs = RatRepository.prefs(this)
        val kind = intent.getStringExtra(EXTRA_REQUIREMENT_KIND)
        val amount = intent.getIntExtra(EXTRA_REQUIREMENT_AMOUNT, 0)

        findViewById<TextView>(R.id.taskRatPickRequirement).text = getString(
            R.string.task_rat_pick_requirement,
            if (kind == REQUIRES_SHINY) {
                getString(R.string.task_requires_shiny)
            } else {
                getString(R.string.task_requires_stat, amount, statNameFor(kind))
            }
        )

        adapter = RatCardAdapter(
            prefs = prefs,
            equippedFrame = Frames.byId(ShopEffects.equippedCosmetic(prefs)),
            animator = frameAnimator,
            onCardClick = { pet -> pick(pet.id) },
            isDisabled = { pet -> !meetsRequirement(pet, kind, amount) }
        )
        findViewById<RecyclerView>(R.id.taskRatPickGrid).adapter = adapter

        findViewById<MaterialButton>(R.id.taskRatPickNoneButton).setOnClickListener {
            pick(LedgerTasks.NO_RAT)
        }
        findViewById<MaterialButton>(R.id.taskRatPickCancelButton).setOnClickListener { finish() }

        load()
    }

    private fun statNameFor(kind: String?): String = getString(
        if (kind == REQUIRES_TOUGHNESS) R.string.stat_toughness else R.string.stat_power
    )

    /** Whether [pet] alone would clear the job's own Start requirement. */
    private fun meetsRequirement(pet: RatEntity, kind: String?, amount: Int): Boolean = when (kind) {
        REQUIRES_TOUGHNESS -> pet.effectiveToughness >= amount
        REQUIRES_SHINY -> pet.shiny
        else -> pet.effectivePower >= amount
    }

    override fun onResume() {
        super.onResume()
        frameAnimator.start()
    }

    /** Nothing should be turning behind a dialog, another screen or a dark display. */
    override fun onPause() {
        super.onPause()
        frameAnimator.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        frameAnimator.stop()
        frameAnimator.clear()
    }

    private fun load() {
        lifecycleScope.launch {
            val roster = withContext(Dispatchers.IO) {
                RatRepository.dao(this@TaskRatPickerActivity).all()
            }
            adapter.submitList(roster)
            findViewById<View>(R.id.taskRatPickGridEmpty).visibility =
                if (roster.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun pick(ratId: Long) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RAT_ID, ratId))
        finish()
    }
}
