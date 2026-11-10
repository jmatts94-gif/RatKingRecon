package io.github.jmatts94.ratkingrecon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Seven bars, one per day, each with its count above and its day below.
 *
 * Every bar carries its number and name as text, and today is marked by the
 * word "Today" and a heavy outline, so nothing here depends on telling colours
 * apart.
 */
class StepChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var days: List<StepHistory.Day> = emptyList()

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val lexend = ResourcesCompat.getFont(context, R.font.lexend)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.amber)
    }
    private val todayOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textAlign = Paint.Align.CENTER
        textSize = 13f * scaledDensity
        typeface = lexend
    }
    private val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted)
        textAlign = Paint.Align.CENTER
        textSize = 13f * scaledDensity
        typeface = lexend
    }

    private val bar = RectF()

    fun setDays(newDays: List<StepHistory.Day>) {
        days = newDays
        contentDescription = newDays.joinToString(", ") { day ->
            context.getString(R.string.chart_day_description, labelFor(day), day.steps)
        }
        invalidate()
    }

    private fun labelFor(day: StepHistory.Day): String =
        if (day === days.lastOrNull()) context.getString(R.string.chart_today)
        else SimpleDateFormat("EEE", Locale.getDefault()).format(Date(day.millis))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = resolveSize((190 * density).toInt(), heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (days.isEmpty()) return

        val columnWidth = width.toFloat() / days.size
        val barWidth = columnWidth * 0.56f
        val corner = 6f * density

        val labelBand = dayPaint.fontSpacing + 6f * density
        val countBand = countPaint.fontSpacing + 4f * density
        val baseline = height - labelBand
        val tallest = baseline - countBand
        val best = days.maxOf { it.steps }.coerceAtLeast(1)
        val minimumBar = 3f * density

        days.forEachIndexed { index, day ->
            val centre = columnWidth * (index + 0.5f)
            val isToday = index == days.lastIndex
            val barHeight = (tallest * day.steps / best).coerceAtLeast(minimumBar)
            val top = baseline - barHeight

            bar.set(centre - barWidth / 2, top, centre + barWidth / 2, baseline)
            canvas.drawRoundRect(bar, corner, corner, barPaint)
            if (isToday) canvas.drawRoundRect(bar, corner, corner, todayOutline)

            canvas.drawText(StepHistory.compact(day.steps), centre, top - 5f * density, countPaint)

            dayPaint.isFakeBoldText = isToday
            canvas.drawText(labelFor(day), centre, height - 4f * density, dayPaint)
        }
    }
}
