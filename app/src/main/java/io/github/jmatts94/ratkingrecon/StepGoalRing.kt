package io.github.jmatts94.ratkingrecon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * The home tile's goal ring: it fills as today's steps climb, with a footprint
 * inside, and turns into a full ring with a tick once the goal is met.
 *
 * Shape tells the story (how full the ring is, footprint or tick), so it never
 * relies on colour alone.
 */
class StepGoalRing @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var fraction = 0f
    private var met = false

    private val density = resources.displayMetrics.density
    private val stroke = 3.5f * density

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = ContextCompat.getColor(context, R.color.disabled_fill)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.amber_dark)
    }
    private val footprint = ContextCompat.getDrawable(context, R.drawable.ic_footprint)?.mutate()
    private val tick = ContextCompat.getDrawable(context, R.drawable.ic_check)?.mutate()?.apply {
        setTint(ContextCompat.getColor(context, R.color.text_primary))
    }

    private val oval = RectF()

    fun setProgress(steps: Int, goal: Int) {
        met = StepGoal.isMet(steps, goal)
        fraction = if (goal <= 0) 0f else (steps.toFloat() / goal).coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = stroke / 2
        oval.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(oval, track)
        if (fraction > 0f) canvas.drawArc(oval, -90f, 360f * fraction, false, fill)

        val icon = if (met) tick else footprint
        val pad = (width * 0.27f).toInt()
        icon?.setBounds(pad, pad, width - pad, height - pad)
        icon?.draw(canvas)
    }
}
