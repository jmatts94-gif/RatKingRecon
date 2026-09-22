package io.github.jmatts94.ratkingrecon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * The Recon Card's hero visual for a Lifetime Steps milestone - a brass
 * medallion face, not [GaugeDialView]'s old pressure gauge.
 *
 * The gauge it replaces read as a prop rather than a badge: its needle
 * always pinned to the same "redlined" position regardless of which
 * milestone the card was for (see that class's own comment, since deleted),
 * so every step card looked identical apart from its number. This carries
 * no needle and no reading - just the same milestone icon already shown
 * against it on the Achievements row (see [ReconCard.shareStepsMilestone]),
 * blown up and struck into a medal the way the rest of the app's steampunk
 * furniture - the streak lantern, the Arena medallions - already reads as
 * regalia rather than instrumentation.
 */
class MilestoneMedallionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card_white)
        style = Paint.Style.FILL
    }
    private val ringOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.amber_dark)
        style = Paint.Style.STROKE
        strokeWidth = 10 * density
    }

    /** The bevel: a bright brass hairline just inside the dark outer ring. */
    private val ringHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brass_bright)
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.tan)
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val rOuter = (width.coerceAtMost(height) / 2f) - ringOuterPaint.strokeWidth / 2f
        val rHighlight = rOuter - ringOuterPaint.strokeWidth / 2f - ringHighlightPaint.strokeWidth
        val rInner = rHighlight - 16 * density

        canvas.drawCircle(cx, cy, rOuter - ringOuterPaint.strokeWidth / 2f, facePaint)
        canvas.drawCircle(cx, cy, rOuter, ringOuterPaint)
        canvas.drawCircle(cx, cy, rHighlight, ringHighlightPaint)
        canvas.drawCircle(cx, cy, rInner, innerRingPaint)
    }
}
