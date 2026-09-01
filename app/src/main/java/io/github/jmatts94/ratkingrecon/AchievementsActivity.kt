package io.github.jmatts94.ratkingrecon

import android.animation.ObjectAnimator
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Boss badges and the three milestone categories, on one screen.
 *
 * One home-screen entry rather than four: they all answer the same question -
 * what has this save actually achieved - and the home screen already carries
 * eight buttons.
 *
 * Rebuilt wholesale on each resume rather than diffed. There are two dozen rows,
 * all from one preferences read and three counting queries.
 *
 * The refresh here is also the safety net. Milestones latch at the point they
 * are earned - walking, hatching, splicing, a Masterwork pull - but opening this
 * screen re-evaluates them all, so a trigger that was missed because the process
 * died mid-write cannot leave a badge permanently unearned.
 */
class AchievementsActivity : AppCompatActivity() {

    companion object {
        /** Opens the screen already scrolled to the Arena Badges section - see the home tile. */
        const val EXTRA_SCROLL_TO_ARENA = "scroll_to_arena"
    }

    private lateinit var scrollView: ScrollView
    private lateinit var badgeList: LinearLayout
    private lateinit var arenaBadgeList: LinearLayout
    private lateinit var stepsList: LinearLayout
    private lateinit var rosterList: LinearLayout
    private lateinit var hatchingList: LinearLayout
    private lateinit var buffsList: LinearLayout
    private lateinit var summary: TextView
    private lateinit var stepsSubtitle: TextView
    private lateinit var rosterSubtitle: TextView

    /** The Arena medallions' own spin/glow animators, live only while this screen is in front. */
    private val arenaAnimators = mutableListOf<ObjectAnimator>()

    /** So a rotation (onResume firing again) does not scroll the player back down a second time. */
    private var scrolledToArena = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_achievements)
        EdgeToEdge.apply(this)

        scrollView = findViewById(R.id.achievementsScroll)
        badgeList = findViewById(R.id.badgeList)
        arenaBadgeList = findViewById(R.id.arenaBadgeList)
        stepsList = findViewById(R.id.stepsList)
        rosterList = findViewById(R.id.rosterList)
        hatchingList = findViewById(R.id.hatchingList)
        buffsList = findViewById(R.id.buffsList)
        summary = findViewById(R.id.achievementsSummary)
        stepsSubtitle = findViewById(R.id.stepsSubtitle)
        rosterSubtitle = findViewById(R.id.rosterSubtitle)

        findViewById<View>(R.id.achievementsCloseButton).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()

        lifecycleScope.launch {
            val prefs = RatRepository.prefs(this@AchievementsActivity)
            val progress = withContext(Dispatchers.IO) {
                val dao = RatRepository.dao(this@AchievementsActivity)
                val read = Milestones.readProgress(dao, prefs)
                Milestones.refresh(prefs, read)
                PermanentBuffs.refresh(prefs)
                read
            }
            render(progress)

            if (intent.getBooleanExtra(EXTRA_SCROLL_TO_ARENA, false) && !scrolledToArena) {
                scrolledToArena = true
                scrollView.post { scrollView.smoothScrollTo(0, arenaBadgeList.top) }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Every card frame in the Ledger stops turning the same way when this
        // screen is not the one in front - see FrameAnimator.stop via
        // GalleryActivity.onPause - so a medallion left spinning behind a
        // dialog or another screen would be the one animation in the app
        // that did not follow that rule.
        ArenaBadgeMedallion.stop(arenaAnimators)
        arenaAnimators.clear()
    }

    private fun render(progress: MilestoneProgress) {
        val prefs = RatRepository.prefs(this)

        summary.text = getString(
            R.string.achievements_summary,
            Bosses.defeatedCount(prefs) + Milestones.earnedCount(prefs) + ArenaRun.milestonesEarnedCount(prefs) +
                PermanentBuffs.earnedCount(prefs),
            Bosses.all.size + Milestones.all.size + ArenaRun.MILESTONES.size + PermanentBuffs.all.size
        )

        stepsSubtitle.text = getString(
            R.string.achievements_steps_sub,
            progress.lifetimeSteps,
            Milestones.kilometresFor(progress.lifetimeSteps)
        )
        rosterSubtitle.text = getString(
            R.string.achievements_roster_sub,
            progress.ratsHeld,
            progress.speciesFound,
            Roster.all.size
        )

        renderBadges(prefs)
        renderArenaBadges(prefs)
        renderMilestones(stepsList, Milestones.steps, prefs, progress)
        renderMilestones(rosterList, Milestones.roster, prefs, progress)
        renderMilestones(hatchingList, Milestones.hatching, prefs, progress)
        renderBuffs(prefs)
    }

    /**
     * The four permanent buffs - see [PermanentBuffs].
     *
     * Reuses [row] exactly as every other section does, but an earned row's
     * detail line says what the buff now does rather than the plain "Earned."
     * [Milestones.currentFor] gives every other section - these are always-on
     * stat changes, worth the reminder every time this screen opens.
     */
    private fun renderBuffs(prefs: android.content.SharedPreferences) {
        buffsList.removeAllViews()

        val hatches = GameEngine.lifetimeHatchesOf(prefs)
        val streak = Streak.count(prefs)
        val steps = GameEngine.lifetimeStepsOf(prefs)

        PermanentBuffs.all.forEach { buff ->
            val earned = PermanentBuffs.isEarned(prefs, buff)

            val detail: String
            val percent: Int?
            when {
                earned -> {
                    detail = getString(R.string.buff_active_detail, getString(buff.effectRes))
                    percent = null
                }
                buff == PermanentBuffs.COLLECTORS_INSTINCT -> {
                    val target = PermanentBuffs.COLLECTORS_INSTINCT_HATCH_TARGET
                    detail = getString(R.string.milestone_progress, hatches.coerceAtMost(target), target)
                    percent = ((hatches.toDouble() / target) * 100).toInt().coerceIn(0, 100)
                }
                buff == PermanentBuffs.STEADFAST_MOMENTUM -> {
                    val target = PermanentBuffs.STEADFAST_MOMENTUM_STREAK_TARGET
                    detail = getString(R.string.milestone_progress, streak.coerceAtMost(target), target)
                    percent = ((streak.toDouble() / target) * 100).toInt().coerceIn(0, 100)
                }
                buff == PermanentBuffs.RUSTED_FANG -> {
                    // Boolean, the same shape the Arena badges above already use.
                    detail = getString(R.string.arena_badge_locked_detail, PermanentBuffs.RUSTED_FANG_ARENA_FIGHT)
                    percent = null
                }
                else -> {
                    val target = PermanentBuffs.IRON_BOOTS_STEP_TARGET
                    detail = getString(R.string.milestone_progress, steps.coerceAtMost(target), target)
                    percent = ((steps.toDouble() / target) * 100).toInt().coerceIn(0, 100)
                }
            }

            buffsList.addView(
                row(
                    parent = buffsList,
                    iconRes = buff.iconRes,
                    name = if (earned) getString(buff.nameRes) else getString(R.string.badge_locked),
                    detail = detail,
                    unlocked = earned,
                    progress = percent
                )
            )
        }
    }

    private fun renderBadges(prefs: android.content.SharedPreferences) {
        badgeList.removeAllViews()
        Bosses.all.forEach { spec ->
            val earned = Bosses.isDefeated(prefs, spec.id)
            val reward = AchievementRewards.forBoss(spec.id)?.let { AchievementRewards.describe(this, it) }
            val detail = when {
                earned && reward != null -> getString(R.string.badge_earned_detail_with_reward, reward)
                earned -> getString(R.string.badge_earned_detail)
                reward != null -> getString(R.string.badge_locked_detail_with_reward, spec.minLevel, reward)
                else -> getString(R.string.badge_locked_detail, spec.minLevel)
            }
            badgeList.addView(
                row(
                    parent = badgeList,
                    iconRes = spec.badgeRes,
                    name = if (earned) getString(spec.nameRes) else getString(R.string.badge_locked),
                    detail = detail,
                    unlocked = earned,
                    progress = null
                )
            )
        }
    }

    private fun renderArenaBadges(prefs: android.content.SharedPreferences) {
        ArenaBadgeMedallion.stop(arenaAnimators)
        arenaAnimators.clear()
        arenaBadgeList.removeAllViews()

        ArenaRun.MILESTONES.forEach { milestone ->
            val earned = ArenaRun.isMilestoneEarned(prefs, milestone.fight)
            arenaBadgeList.addView(arenaRow(milestone, earned))
        }
    }

    /**
     * One Arena badge row - the same shape [row] builds for every other list
     * on this screen, but with an animated [ArenaBadgeMedallion] in place of
     * the plain static icon [row] uses, since these are the one badge on
     * this screen that spins and glows rather than sitting still.
     */
    private fun arenaRow(milestone: ArenaMilestone, earned: Boolean): View {
        val view = LayoutInflater.from(this)
            .inflate(R.layout.item_achievement_arena, arenaBadgeList, false)

        view.setBackgroundResource(if (earned) R.drawable.bg_card_earned else R.drawable.bg_card_white)
        view.findViewById<View>(R.id.achievementCheck).visibility = if (earned) View.VISIBLE else View.GONE

        val medallion = view.findViewById<View>(R.id.achievementMedallion)
        ArenaBadgeMedallion.bind(medallion, milestone, earned)
        if (earned) arenaAnimators += ArenaBadgeMedallion.start(medallion, milestone)

        view.findViewById<TextView>(R.id.achievementName).text =
            if (earned) getString(milestone.nameRes) else getString(R.string.badge_locked)
        view.findViewById<TextView>(R.id.achievementDetail).text = getString(
            if (earned) R.string.arena_badge_earned_detail else R.string.arena_badge_locked_detail,
            milestone.fight
        )

        return view
    }

    private fun renderMilestones(
        into: LinearLayout,
        milestones: List<Milestone>,
        prefs: android.content.SharedPreferences,
        progress: MilestoneProgress
    ) {
        into.removeAllViews()
        milestones.forEach { milestone ->
            val earned = Milestones.isEarned(prefs, milestone)
            val current = Milestones.currentFor(milestone, progress)
            val reward = AchievementRewards.forMilestone(milestone.id)?.let { AchievementRewards.describe(this, it) }

            // A yes/no milestone has nothing to count towards, so it gets a
            // plain "not yet" rather than "0 of 1". Reward text only joins
            // the yes/no rows - a numeric progress row already has the bar
            // beneath it, and "37 of 100000. Reward: ..." reads as clutter a
            // bar-only row does not have room to spare.
            val detail = when {
                earned && reward != null -> getString(R.string.milestone_done_with_reward, reward)
                earned -> getString(R.string.milestone_done)
                milestone.target == 1L && reward != null ->
                    getString(R.string.milestone_not_yet_with_reward, reward)
                milestone.target == 1L -> getString(R.string.milestone_not_yet)
                else -> getString(R.string.milestone_progress, current, milestone.target)
            }

            into.addView(
                row(
                    parent = into,
                    iconRes = milestone.iconRes,
                    name = getString(milestone.nameRes),
                    detail = detail,
                    unlocked = earned,
                    progress = if (earned || milestone.target == 1L) null else {
                        Milestones.percentTowards(milestone, progress)
                    }
                )
            )
        }
    }

    /**
     * One row.
     *
     * A locked row is greyed by draining the icon's colour rather than by
     * swapping in a second "locked" drawable, which would have meant shipping a
     * silhouette for every badge and keeping the two in step forever.
     *
     * Earned adds a checkmark and swaps the card background for an amber-washed
     * one - both driven by the same [unlocked] boolean the icon dimming already
     * was, so every category that calls this (badges, all three milestone
     * lists) gets the same treatment for free rather than needing its own.
     */
    private fun row(
        parent: ViewGroup,
        iconRes: Int,
        name: String,
        detail: String,
        unlocked: Boolean,
        progress: Int?
    ): View {
        val view = LayoutInflater.from(this)
            .inflate(R.layout.item_achievement, parent, false)

        view.setBackgroundResource(if (unlocked) R.drawable.bg_card_earned else R.drawable.bg_card_white)
        view.findViewById<View>(R.id.achievementCheck).visibility =
            if (unlocked) View.VISIBLE else View.GONE

        val icon = view.findViewById<ImageView>(R.id.achievementIcon)
        icon.setImageResource(iconRes)

        if (unlocked) {
            icon.colorFilter = null
            icon.alpha = 1f
            icon.imageTintList = ContextCompat.getColorStateList(this, R.color.text_primary)
        } else {
            icon.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            icon.alpha = 0.3f
            icon.imageTintList = ContextCompat.getColorStateList(this, R.color.text_muted)
        }

        view.findViewById<TextView>(R.id.achievementName).text = name
        view.findViewById<TextView>(R.id.achievementDetail).text = detail

        view.findViewById<ProgressBar>(R.id.achievementProgress).apply {
            if (progress == null) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                this.progress = progress
            }
        }

        return view
    }
}
