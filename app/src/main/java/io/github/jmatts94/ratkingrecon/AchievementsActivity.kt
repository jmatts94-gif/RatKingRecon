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

/**
 * Boss badges and lifetime walking milestones, on one screen.
 *
 * One home-screen entry rather than two: the two lists answer the same question
 * - what has this save actually achieved - and splitting them would have cost a
 * second button on a home screen that already carries seven.
 *
 * Read-only, and cheap enough to rebuild wholesale in onResume rather than
 * diffing: eleven rows, all from preferences already in memory.
 */
class AchievementsActivity : AppCompatActivity() {

    private lateinit var badgeList: LinearLayout
    private lateinit var milestoneList: LinearLayout
    private lateinit var summary: TextView
    private lateinit var milestoneSubtitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_achievements)

        badgeList = findViewById(R.id.badgeList)
        milestoneList = findViewById(R.id.milestoneList)
        summary = findViewById(R.id.achievementsSummary)
        milestoneSubtitle = findViewById(R.id.milestoneSubtitle)

        findViewById<View>(R.id.achievementsCloseButton).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val prefs = RatRepository.prefs(this)
        val lifetime = GameEngine.lifetimeStepsOf(prefs)

        val badgesEarned = Bosses.defeatedCount(prefs)
        val milestonesHit = Milestones.reachedCount(lifetime)
        summary.text = getString(
            R.string.achievements_summary,
            badgesEarned + milestonesHit,
            Bosses.all.size + Milestones.all.size
        )

        milestoneSubtitle.text = getString(
            R.string.achievements_milestones_sub,
            lifetime,
            Milestones.kilometresFor(lifetime)
        )

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

        milestoneList.removeAllViews()
        Milestones.all.forEach { milestone ->
            val reached = Milestones.reached(lifetime, milestone)
            milestoneList.addView(
                row(
                    parent = milestoneList,
                    iconRes = milestone.iconRes,
                    name = getString(milestone.nameRes),
                    detail = if (reached) {
                        getString(R.string.milestone_done, milestone.steps)
                    } else {
                        getString(R.string.milestone_progress, lifetime, milestone.steps)
                    },
                    unlocked = reached,
                    progress = if (reached) null else {
                        Milestones.percentTowards(lifetime, milestone)
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
