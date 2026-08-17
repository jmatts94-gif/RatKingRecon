package io.github.jmatts94.ratkingrecon

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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

    private lateinit var badgeList: LinearLayout
    private lateinit var stepsList: LinearLayout
    private lateinit var rosterList: LinearLayout
    private lateinit var hatchingList: LinearLayout
    private lateinit var summary: TextView
    private lateinit var stepsSubtitle: TextView
    private lateinit var rosterSubtitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_achievements)

        badgeList = findViewById(R.id.badgeList)
        stepsList = findViewById(R.id.stepsList)
        rosterList = findViewById(R.id.rosterList)
        hatchingList = findViewById(R.id.hatchingList)
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
                read
            }
            render(progress)
        }
    }

    private fun render(progress: MilestoneProgress) {
        val prefs = RatRepository.prefs(this)

        summary.text = getString(
            R.string.achievements_summary,
            Bosses.defeatedCount(prefs) + Milestones.earnedCount(prefs),
            Bosses.all.size + Milestones.all.size
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
        renderMilestones(stepsList, Milestones.steps, prefs, progress)
        renderMilestones(rosterList, Milestones.roster, prefs, progress)
        renderMilestones(hatchingList, Milestones.hatching, prefs, progress)
    }

    private fun renderBadges(prefs: android.content.SharedPreferences) {
        badgeList.removeAllViews()
        Bosses.all.forEach { spec ->
            val earned = Bosses.isDefeated(prefs, spec.id)
            badgeList.addView(
                row(
                    parent = badgeList,
                    iconRes = spec.badgeRes,
                    name = if (earned) getString(spec.nameRes) else getString(R.string.badge_locked),
                    detail = getString(
                        if (earned) R.string.badge_earned_detail else R.string.badge_locked_detail,
                        spec.minLevel
                    ),
                    unlocked = earned,
                    progress = null
                )
            )
        }
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

            // A yes/no milestone has nothing to count towards, so it gets a
            // plain "not yet" rather than "0 of 1".
            val detail = when {
                earned -> getString(R.string.milestone_done)
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
