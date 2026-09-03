package io.github.jmatts94.ratkingrecon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.sin

/**
 * A ticked, needled instrument dial - the Recon Card's stand-in for a
 * pressure gauge, read out under a steps milestone the same way the app's
 * other steampunk instruments (the streak lantern, the Arena medallions) are
 * never plain icons either.
 *
 * The needle position is decorative, not a literal fraction of the milestone
 * reached: the six step milestones span 10,000 to 1,000,000, and no single
 * linear scale reads sensibly across that range. [needleFraction] always
 * pins near the top of the sweep, the same "redlined - just cleared it"
 * reading regardless of which milestone this card is for. See ReconCard.
 */
class GaugeDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var needleFraction: Float = 0.85f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card_white)
        style = Paint.Style.FILL
    }
    private val faceStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card_border)
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.tan)
        style = Paint.Style.STROKE
        strokeWidth = 1 * density
    }
    private val tickMajorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.amber_dark)
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val tickMinorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card_border)
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.terracotta)
        style = Paint.Style.STROKE
        strokeWidth = 3.4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brass_bright)
        style = Paint.Style.FILL
    }
    private val hubStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.amber_dark)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    // Sweeps clockwise from lower-left to lower-right, the same reading as
    // any analogue pressure gauge - see toXY.
    private val startDeg = 140f
    private val endDeg = 400f

    private fun toXY(cx: Float, cy: Float, r: Float, deg: Float): FloatArray {
        val rad = Math.toRadians(deg.toDouble())
        return floatArrayOf(cx + r * cos(rad).toFloat(), cy + r * sin(rad).toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val rOuter = (width.coerceAtMost(height) / 2f) - faceStrokePaint.strokeWidth
        val rTickOut = rOuter - 6 * density
        val rTickIn = rOuter - 18 * density
        val rFace = rOuter - 22 * density

        canvas.drawCircle(cx, cy, rOuter, facePaint)
        canvas.drawCircle(cx, cy, rOuter, faceStrokePaint)
        canvas.drawCircle(cx, cy, rFace, innerRingPaint)

        val ticks = 24
        for (i in 0..ticks) {
            val deg = startDeg + (endDeg - startDeg) * i / ticks
            val major = i % 4 == 0
            val outer = toXY(cx, cy, if (major) rTickOut else rTickOut - 5 * density, deg)
            val inner = toXY(cx, cy, rTickIn, deg)
            canvas.drawLine(outer[0], outer[1], inner[0], inner[1], if (major) tickMajorPaint else tickMinorPaint)
        }

        val needleDeg = startDeg + (endDeg - startDeg) * needleFraction
        val tip = toXY(cx, cy, rFace - 6 * density, needleDeg)
        val tail = toXY(cx, cy, 14 * density, needleDeg + 180f)
        canvas.drawLine(tail[0], tail[1], tip[0], tip[1], needlePaint)

        val hubRadius = 7 * density
        canvas.drawCircle(cx, cy, hubRadius, hubPaint)
        canvas.drawCircle(cx, cy, hubRadius, hubStrokePaint)
    }
}
