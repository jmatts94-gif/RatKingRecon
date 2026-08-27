package io.github.jmatts94.ratkingrecon

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.core.content.ContextCompat

/**
 * Binds and animates one Arena badge medallion - a small circular icon used
 * both on the home screen's Arena tile and the Achievements screen's Arena
 * Badges section, inflated from the same view_arena_badge_medallion.xml in
 * both places so the two never drift apart.
 *
 * Locked reads the way every other locked badge in the app already does -
 * see AchievementsActivity.row() - a desaturated icon, dimmed alpha, muted
 * tint, and nothing moving. Earned tints the gear itself to the badge's tier
 * colour - there is no disc behind it to carry that any more, on purpose:
 * this is meant to read as the gear sitting on whatever screen it is on, not
 * as an icon inside a badge-shaped container - and additionally spins it
 * and, where the milestone calls for one (see [ArenaMilestone.glowAlpha]),
 * breathes a glow behind it - the streak tile's own bg_lantern_glow, tinted
 * to the same tier colour instead of the lantern's brass.
 */
object ArenaBadgeMedallion {

    private const val SPIN_MS = 6_000L
    private const val GLOW_CYCLE_MS = 1_600L
    private const val GLOW_MIN_ALPHA = 30

    /** Sets every static piece of [root]: icon, tint, lock state. Call before [start]. */
    fun bind(root: View, milestone: ArenaMilestone, earned: Boolean) {
        val context = root.context
        val icon = root.findViewById<ImageView>(R.id.medallionIcon)
        val glow = root.findViewById<View>(R.id.medallionGlow)

        icon.setImageResource(milestone.iconRes)
        icon.rotation = 0f

        if (earned) {
            icon.imageTintList = ContextCompat.getColorStateList(context, milestone.tierColorRes)
            icon.colorFilter = null
            icon.alpha = 1f

            if (milestone.glowAlpha > 0) {
                glow.visibility = View.VISIBLE
                glow.scaleX = milestone.glowScale
                glow.scaleY = milestone.glowScale
                glow.background.mutate()
                glow.backgroundTintList = ContextCompat.getColorStateList(context, milestone.tierColorRes)
                glow.background.alpha = GLOW_MIN_ALPHA
            } else {
                glow.visibility = View.GONE
            }
        } else {
            icon.imageTintList = ContextCompat.getColorStateList(context, R.color.text_muted)
            icon.colorFilter = LOCKED_FILTER
            icon.alpha = 0.35f
            glow.visibility = View.GONE
        }
    }

    /**
     * Starts this medallion's animations - a slow continuous spin, and (for
     * a milestone with a glow) a breathing alpha pulse. Only ever called for
     * an earned badge; a locked one has nothing to animate.
     *
     * Returned so the caller can [stop] them - both screens this appears on
     * already pause their own animation when they are not the one in front
     * (see MainActivity's streak lantern, AchievementsActivity's onPause),
     * and these must not keep spinning behind them.
     */
    fun start(root: View, milestone: ArenaMilestone): List<ObjectAnimator> {
        val icon = root.findViewById<ImageView>(R.id.medallionIcon)
        val animators = mutableListOf<ObjectAnimator>()

        animators += ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, 360f).apply {
            duration = SPIN_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        if (milestone.glowAlpha > 0) {
            val glow = root.findViewById<View>(R.id.medallionGlow)
            animators += ObjectAnimator.ofInt(glow.background, "alpha", GLOW_MIN_ALPHA, milestone.glowAlpha).apply {
                duration = GLOW_CYCLE_MS
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }

        return animators
    }

    fun stop(animators: List<ObjectAnimator>) {
        animators.forEach { it.cancel() }
    }

    /** The same desaturation every other locked icon in the app uses - see AchievementsActivity.row(). */
    private val LOCKED_FILTER = android.graphics.ColorMatrixColorFilter(
        android.graphics.ColorMatrix().apply { setSaturation(0f) }
    )
}
