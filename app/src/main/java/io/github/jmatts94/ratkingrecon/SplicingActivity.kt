package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Lets the player choose both rats the Fusion Pot consumes, rather than it
 * auto-picking the two weakest.
 *
 * What the splice produces is still decided entirely by [Fusion] and stays
 * unknown until it happens - this screen only replaces which two rats go in,
 * never what comes out.
 */
class SplicingActivity : AppCompatActivity() {

    private companion object {
        const val SCRAP_COST = 25
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: RatCardAdapter

    private var roster: List<RatEntity> = emptyList()
    private var excludedIds: Set<Long> = emptySet()
    private val selectedIds = mutableSetOf<Long>()
    private var scrap = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splicing)
        EdgeToEdge.apply(this)

        prefs = RatRepository.prefs(this)

        adapter = RatCardAdapter(
            prefs = prefs,
            equippedFrame = Frames.byId(ShopEffects.equippedCosmetic(prefs)),
            animator = FrameAnimator(),
            onCardClick = { onPetTapped(it) },
            isDisabled = { it.id in excludedIds },
            isSelected = { it.id in selectedIds }
        )
        findViewById<RecyclerView>(R.id.splicePetGrid).adapter = adapter

        findViewById<MaterialButton>(R.id.spliceCancelButton).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.spliceConfirmButton).setOnClickListener { splice() }

        load()

        CoachMarkOverlay.showIfDue(
            activity = this,
            shouldShow = SplicingCoachMarks.shouldShow(prefs),
            steps = SplicingCoachMarks.stepsFor(prefs),
            onFinish = { SplicingCoachMarks.markComplete(prefs) }
        )
    }

    private fun onPetTapped(pet: RatEntity) {
        if (selectedIds.remove(pet.id)) {
            refresh()
            return
        }
        if (selectedIds.size >= 2) {
            Toast.makeText(this, getString(R.string.toast_splice_max_selected), Toast.LENGTH_SHORT).show()
            return
        }
        selectedIds += pet.id
        refresh()
    }

    private fun load() {
        lifecycleScope.launch {
            val (loadedRoster, loadedExcluded) = withContext(Dispatchers.IO) {
                val dao = RatRepository.dao(this@SplicingActivity)
                val all = dao.all()
                all to SpliceEligibility.excludedIds(prefs, all)
            }
            roster = loadedRoster
            excludedIds = loadedExcluded
            scrap = prefs.getInt(GameEngine.KEY_SCRAP, 0)
            adapter.submitList(roster)
            refresh()
        }
    }

    /**
     * Updates the confirm button/status text for the current selection, and
     * forces every visible card to re-read it.
     *
     * A plain [RatCardAdapter.notifyDataSetChanged] rather than another
     * submitList: the roster itself has not changed, only which of its rats
     * are selected, and DiffUtil has no way to see that since it lives outside
     * [RatEntity].
     */
    private fun refresh() {
        adapter.notifyDataSetChanged()

        val eligibleCount = roster.count { it.id !in excludedIds }
        val canSplice = selectedIds.size == 2 && scrap >= SCRAP_COST

        findViewById<View>(R.id.splicePetGridEmpty).visibility =
            if (roster.isEmpty()) View.VISIBLE else View.GONE

        val statusText = findViewById<TextView>(R.id.spliceStatusText)
        statusText.text = when {
            eligibleCount < 2 || scrap < SCRAP_COST -> getString(R.string.toast_splice_requirements)
            selectedIds.size < 2 -> getString(R.string.splice_pick_two_rats)
            else -> ""
        }

        val confirmButton = findViewById<MaterialButton>(R.id.spliceConfirmButton)
        confirmButton.isEnabled = canSplice
        // Tinted by hand, the same reason ShopActivity does: the palette is
        // hardcoded warm, so Material's own disabled colours would drop a grey
        // button into a cream screen.
        confirmButton.backgroundTintList = ContextCompat.getColorStateList(
            this, if (canSplice) R.color.amber else R.color.disabled_fill
        )
        confirmButton.setTextColor(
            ContextCompat.getColor(this, if (canSplice) R.color.text_primary else R.color.text_muted)
        )
    }

    /**
     * Burns the Scrap cost and the two chosen rats to mint one stronger mutant.
     *
     * The delete-and-insert runs inside a Room transaction, so a crash
     * mid-splice cannot consume the parents without producing the mutant - see
     * [Fusion] for the base outcome roll and [SpliceEffects] for what each
     * parent's own faction can add on top of it.
     */
    private fun splice() {
        val parents = roster.filter { it.id in selectedIds }
        if (parents.size != 2 || scrap < SCRAP_COST) return

        lifecycleScope.launch {
            val dao = withContext(Dispatchers.IO) { RatRepository.dao(this@SplicingActivity) }

            // Each parent rolls its own faction's effect independently - a
            // same-faction pair rolls the same effect twice, and both
            // successes stack rather than one overriding the other.
            val triggered = listOfNotNull(
                SpliceEffects.rollFor(parents[0].faction),
                SpliceEffects.rollFor(parents[1].faction)
            )

            val species = Fusion.roll(
                boosted = SpliceEffects.Kind.TINKERER in triggered,
                bonusFraction = PermanentBuffs.spliceOddsBonusFor(prefs)
            )

            val deltas = triggered.map { SpliceEffects.statDeltaFor(it) }
            val basePower = maxOf(parents[0].power, parents[1].power) + 1
            val baseToughness = maxOf(parents[0].toughness, parents[1].toughness) + 1

            // Floored once, on the final sum - never per effect - so a later
            // positive roll is never wasted against an already-floored
            // intermediate value. No ceiling: a mutant's stats are free to
            // pass every rat hatched so far, on purpose.
            val mutant = RatEntity(
                artKey = species.artKey,
                power = (basePower + deltas.sumOf { it.power }).coerceAtLeast(1),
                toughness = (baseToughness + deltas.sumOf { it.toughness }).coerceAtLeast(1),
                name = species.name,
                shiny = (1..5).random() == 1,
                isSpliced = true
            )

            // The id RatEntity(...) was built with is still the placeholder
            // 0 - splice() returns the real one the insert assigned, which
            // the result card needs for its Battle Rat / Scrap Run buttons
            // to act on the right row.
            val mutantId = withContext(Dispatchers.IO) {
                val id = dao.splice(parents, mutant)
                Milestones.recordSplice(prefs)
                if (SpliceEffects.Kind.TINKERER in triggered) Milestones.recordTinkererTrigger(prefs)
                Milestones.refresh(prefs, Milestones.readProgress(dao, prefs), dao)
                DailyQuest.record(prefs, QuestType.SPLICE)?.let {
                    DailyAlerts.postQuestPaid(applicationContext, it)
                }
                id
            }

            val scavengerHits = triggered.count { it == SpliceEffects.Kind.SCAVENGER }
            val refund = scavengerRefund() * scavengerHits
            prefs.edit().putInt(GameEngine.KEY_SCRAP, scrap - SCRAP_COST + refund).apply()

            // The result card is the confirmation - which faction effects
            // fired shows right on it, below Power/Toughness, so there is
            // nothing left for a toast to say.
            //
            // HATCH rather than a splice-specific cue: a new rat joining the
            // roster is the same beat whether it walked out of an egg or the
            // Fusion Pot, and a near-identical second cue would only earn its
            // keep once there is a distinct sound to pair it with.
            GameSounds.play(this@SplicingActivity, GameSounds.Cue.HATCH)
            Haptics.play(this@SplicingActivity, Haptics.Cue.HATCH)
            EnlargedRatDialog.show(
                activity = this@SplicingActivity,
                pet = mutant.copy(id = mutantId),
                prefs = prefs,
                extraLines = effectLines(triggered, refund),
                onDismiss = { finish() }
            )
        }
    }

    private fun scavengerRefund(): Int =
        (SCRAP_COST * SpliceEffects.SCAVENGER_REFUND_FRACTION).roundToInt()

    /**
     * One line per distinct effect that fired, in a fixed order regardless of
     * which parent slot rolled it - stat effects first, then the tier boost,
     * then the Scrap refund last.
     *
     * A same-faction pair that hits twice gets one merged line with the
     * already-stacked numbers and an "x2" wording, rather than two
     * near-identical lines - two Brawler rolls both landing is one event
     * worth reading, not two.
     */
    private fun effectLines(triggered: List<SpliceEffects.Kind>, refund: Int): List<String> {
        val lines = mutableListOf<String>()

        fun statLine(kind: SpliceEffects.Kind, single: Int, x2: Int) {
            val hits = triggered.count { it == kind }
            if (hits == 0) return
            val delta = SpliceEffects.statDeltaFor(kind)
            val power = abs(delta.power * hits)
            val toughness = abs(delta.toughness * hits)
            val resId = if (hits == 2) x2 else single
            // Foundry-born's wording leads with Toughness ("+N Toughness /
            // -N Power"), the reverse of Smuggler's and Brawler's - the
            // format args have to match whichever order that specific string
            // actually names them in, not a single fixed (power, toughness)
            // order for all three.
            lines += if (kind == SpliceEffects.Kind.FOUNDRY_BORN) {
                getString(resId, toughness, power)
            } else {
                getString(resId, power, toughness)
            }
        }

        statLine(SpliceEffects.Kind.SMUGGLER, R.string.splice_effect_smuggler, R.string.splice_effect_smuggler_x2)
        statLine(SpliceEffects.Kind.BRAWLER, R.string.splice_effect_brawler, R.string.splice_effect_brawler_x2)
        statLine(
            SpliceEffects.Kind.FOUNDRY_BORN,
            R.string.splice_effect_foundry_born,
            R.string.splice_effect_foundry_born_x2
        )

        if (SpliceEffects.Kind.TINKERER in triggered) {
            lines += getString(R.string.splice_effect_tinkerer)
        }
        if (refund > 0) {
            lines += getString(R.string.splice_effect_scavenger, refund)
        }

        return lines
    }
}
