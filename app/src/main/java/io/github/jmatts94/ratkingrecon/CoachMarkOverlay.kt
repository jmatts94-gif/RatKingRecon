package io.github.jmatts94.ratkingrecon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import com.google.android.material.button.MaterialButton

/**
 * The first-visit walkthrough of the workshop screen.
 *
 * A view rather than an Activity or a Dialog, added over MainActivity's own
 * content. That is the whole reason it can point at anything: the pills and the
 * lantern being described are the real ones, still on screen underneath, so
 * there is nothing to keep in step with them and no second copy of the home
 * screen to maintain. Retiring it is removing a view.
 *
 * The dimming and the hole are a single [Path] filled EVEN_ODD - a rectangle the
 * size of the screen with a rounded rectangle inside it, where the doubled
 * winding leaves the target untouched. The obvious alternatives (an offscreen
 * layer and PorterDuff.CLEAR, or four rectangles around the hole) either cost a
 * saveLayer every frame or fall apart at the corners.
 */
class CoachMarkOverlay private constructor(
    private val activity: AppCompatActivity
) : FrameLayout(activity) {

    /**
     * The stops this player is owed, read once when the overlay is built.
     *
     * Not [CoachMarks.steps] - that is the whole walkthrough, and somebody
     * returning after an update is owed only the part of it that changed. Read
     * once rather than per step so the list cannot shift underneath the counter
     * while it is being walked.
     */
    private val steps = CoachMarks.stepsFor(RatRepository.prefs(activity))

    private val density = resources.displayMetrics.density
    private val holePad = 6 * density
    private val holeRadius = 14 * density
    private val gap = 12 * density
    private val side = 20 * density

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.coach_scrim)
    }

    // A warm outline turns a gap in the dimming into something deliberate.
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = ContextCompat.getColor(context, R.color.amber)
    }

    private val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val hole = RectF()
    private var holeReady = false

    private val card: View
    private val caption: TextView
    private val counter: TextView
    private val nextButton: MaterialButton
    private val skipButton: MaterialButton

    private var index = 0

    private val backCallback = object : OnBackPressedCallback(true) {
        // Back steps backwards, and closes from the first stop. Trapping someone
        // behind four captions would be worse than letting them out, and the
        // flag is written either way so it cannot become a loop.
        override fun handleOnBackPressed() {
            if (index == 0) finish() else showStep(index - 1)
        }
    }

    init {
        setWillNotDraw(false)
        // Above the tile's 14dp, so nothing the depth pass lifted pokes through.
        elevation = 24 * density
        // Eats every touch that is not a button: the screen underneath is real
        // and would otherwise be tappable through the dimming.
        isClickable = true
        setOnClickListener { advance() }

        card = LayoutInflater.from(context).inflate(R.layout.view_coach_mark, this, false)
        (card.layoutParams as LayoutParams).apply {
            leftMargin = side.toInt()
            rightMargin = side.toInt()
        }
        addView(card)

        caption = card.findViewById(R.id.coachCaption)
        counter = card.findViewById(R.id.coachStepCounter)
        nextButton = card.findViewById(R.id.coachNextButton)
        skipButton = card.findViewById(R.id.coachSkipButton)

        nextButton.setOnClickListener { advance() }
        skipButton.setOnClickListener { finish() }
    }

    private fun advance() {
        if (index == steps.lastIndex) finish() else showStep(index + 1)
    }

    private fun showStep(position: Int) {
        index = position
        val step = steps[position]

        // A stop whose target has gone is a stop that cannot be drawn. Skipping
        // it is better than dimming the screen around nothing.
        val target = activity.findViewById<View>(step.targetId)
        if (target == null || target.visibility != View.VISIBLE) {
            if (position == steps.lastIndex) finish() else showStep(position + 1)
            return
        }

        caption.setText(step.captionRes)
        counter.text = context.getString(
            R.string.coach_step_counter, position + 1, steps.size
        )
        nextButton.setText(
            if (position == steps.lastIndex) R.string.coach_done
            else R.string.onboarding_next
        )

        // On a short screen the lantern sits below the fold, and a spotlight on
        // something off-screen is just a dark rectangle. Ask for it first, then
        // measure on the frame after the scroll has actually happened.
        target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
        doOnPreDraw { measureHole(target) }
    }

    /**
     * Where the target is, in this overlay's coordinates.
     *
     * Taken from the window rather than from layout because the targets live
     * inside a ScrollView - their position relative to their parent says nothing
     * about where they currently are on screen.
     */
    private fun measureHole(target: View) {
        val mine = IntArray(2)
        val theirs = IntArray(2)
        getLocationInWindow(mine)
        target.getLocationInWindow(theirs)

        val left = (theirs[0] - mine[0]).toFloat()
        val top = (theirs[1] - mine[1]).toFloat()
        hole.set(
            left - holePad,
            top - holePad,
            left + target.width + holePad,
            top + target.height + holePad
        )

        holeReady = true
        positionCard()
        invalidate()
    }

    /** Below the hole where it fits, above it where it does not. */
    private fun positionCard() {
        val available = width - 2 * side
        if (available <= 0) return

        card.measure(
            MeasureSpec.makeMeasureSpec(available.toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val cardHeight = card.measuredHeight

        val below = hole.bottom + gap
        card.translationY = if (below + cardHeight <= height - gap) below
        else (hole.top - gap - cardHeight).coerceAtLeast(gap)
    }

    override fun onDraw(canvas: Canvas) {
        if (!holeReady) return

        path.reset()
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.addRoundRect(hole, holeRadius, holeRadius, Path.Direction.CW)
        canvas.drawPath(path, scrimPaint)
        canvas.drawRoundRect(hole, holeRadius, holeRadius, ringPaint)
    }

    /** Retires the walkthrough, whether it was finished or skipped. */
    private fun finish() {
        CoachMarks.markComplete(RatRepository.prefs(activity))
        backCallback.isEnabled = false
        (parent as? ViewGroup)?.removeView(this)
    }

    companion object {

        /**
         * Runs the walkthrough over [activity], if it is owed one.
         *
         * Safe to call on every resume - the flag decides, and the flag is
         * written on the way out by both the last Next and Skip.
         */
        fun showIfDue(activity: AppCompatActivity) {
            if (!CoachMarks.shouldShow(RatRepository.prefs(activity))) return

            val host = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            val overlay = CoachMarkOverlay(activity)
            host.addView(
                overlay,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            activity.onBackPressedDispatcher.addCallback(activity, overlay.backCallback)

            // The home screen is still being laid out on the resume that gets
            // here first; nothing can be measured until it has settled.
            overlay.doOnPreDraw { overlay.showStep(0) }
        }
    }
}
