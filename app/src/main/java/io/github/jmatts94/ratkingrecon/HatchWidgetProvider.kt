package io.github.jmatts94.ratkingrecon

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews
import java.text.NumberFormat

/**
 * Home-screen widget: today's steps first, then the next hatch.
 *
 * Steps lead because many players open the game only to check them.
 *
 * Android's own 30-minute timer only matters after midnight, so yesterday's
 * count does not sit there until the first step. [StepTrackerService] and the
 * home screen push every other update through [refresh].
 */
class HatchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        draw(context, manager, appWidgetIds)
    }

    companion object {

        /** Redraws every placed widget. Does nothing if none are placed. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, HatchWidgetProvider::class.java))
            if (ids.isNotEmpty()) draw(context, manager, ids)
        }

        private fun draw(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val prefs = context.getSharedPreferences("SaveData", Context.MODE_PRIVATE)
            val level = GameEngine.levelOf(prefs)
            val needed = GameEngine.maxExpFor(level)
            val exp = GameEngine.expOf(prefs).coerceIn(0, needed)
            val left = GameEngine.stepsToNextHatch(prefs)
            val numbers = NumberFormat.getInstance()

            val stepsToday = numbers.format(DailySteps.today(prefs))
            val hatchLine = context.getString(R.string.widget_steps_left, numbers.format(left))
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(
                    context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            val goal = StepGoal.goalOf(prefs)
            val stepsLabel = if (StepGoal.isMet(DailySteps.today(prefs), goal)) {
                context.getString(R.string.widget_goal_met)
            } else {
                context.getString(R.string.widget_goal_of, StepGoal.format(goal))
            }
            val spoken = context.getString(R.string.widget_spoken, stepsToday, stepsLabel, hatchLine)

            // Only set what a layout has: a RemoteViews call on a missing view
            // breaks the whole widget.
            fun fill(layout: Int, kind: Kind) =
                RemoteViews(context.packageName, layout).apply {
                    when (kind) {
                        Kind.WIDE -> {
                            setText(context, R.id.widgetStepsTitle,
                                Segment(stepsToday, 17f, bold = true), Segment(" $stepsLabel", 14f))
                            setText(context, R.id.widgetHatchLine, Segment(hatchLine, 13f))
                        }
                        Kind.SMALL -> {
                            setText(context, R.id.widgetStepsNumber, Segment(stepsToday, 20f, bold = true))
                            setText(context, R.id.widgetStepsLabel, Segment(stepsLabel, 13f))
                        }
                        Kind.TALL -> {
                            setText(context, R.id.widgetStepsNumber, Segment(stepsToday, 28f, bold = true))
                            setText(context, R.id.widgetStepsLabel, Segment(stepsLabel, 15f))
                            setText(context, R.id.widgetHatchLine, Segment(hatchLine, 13f))
                        }
                    }
                    setContentDescription(R.id.widgetHatchRoot, spoken)
                    setProgressBar(R.id.widgetHatchBar, needed, exp, false)
                    // The normal launch route, so onboarding still comes first on a new save.
                    launch?.let { setOnClickPendingIntent(R.id.widgetHatchRoot, it) }
                }

            val wide = fill(R.layout.widget_hatch, Kind.WIDE)
            val tall = fill(R.layout.widget_hatch_tall, Kind.TALL)
            val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ swaps layouts by itself as the widget is resized.
                RemoteViews(
                    mapOf(
                        SizeF(110f, 40f) to fill(R.layout.widget_hatch_small, Kind.SMALL),
                        SizeF(220f, 40f) to wide,
                        // Android picks the biggest size that fits, so a wide two-row
                        // widget needs its own entry or it stays on the one-row layout.
                        SizeF(110f, 110f) to tall,
                        SizeF(220f, 110f) to tall
                    )
                )
            } else {
                wide
            }

            manager.updateAppWidget(ids, views)
        }

        private enum class Kind { WIDE, SMALL, TALL }

        /** One run of text on a shared baseline. Sizes are in sp, so phone font scaling still applies. */
        private class Segment(val text: String, val sizeSp: Float, val bold: Boolean = false)

        /**
         * Draws [segments] in Lexend as one white picture, into an ImageView.
         *
         * A widget cannot use an app's own font, so the words are drawn here.
         * White, because the layout tints it: the launcher then picks the light
         * or dark colour to match the card behind it. The picture's natural
         * height is also its largest, so a narrow widget shrinks it rather
         * than cutting it off, and nothing ever grows past its size.
         */
        private fun RemoteViews.setText(context: Context, viewId: Int, vararg segments: Segment) {
            val metrics = context.resources.displayMetrics
            val regular = ResourcesCompat.getFont(context, R.font.lexend) ?: Typeface.DEFAULT
            val paints = segments.map { segment ->
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    typeface = if (segment.bold) Typeface.create(regular, Typeface.BOLD) else regular
                    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, segment.sizeSp, metrics)
                }
            }
            val ascent = paints.maxOf { -it.fontMetrics.ascent }
            val descent = paints.maxOf { it.fontMetrics.descent }
            val width = segments.indices.sumOf { paints[it].measureText(segments[it].text).toDouble() }
            val bitmap = Bitmap.createBitmap(
                kotlin.math.ceil(width).toInt().coerceAtLeast(1),
                kotlin.math.ceil(ascent + descent).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            var x = 0f
            segments.forEachIndexed { index, segment ->
                canvas.drawText(segment.text, x, ascent, paints[index])
                x += paints[index].measureText(segment.text)
            }
            setImageViewBitmap(viewId, bitmap)
            setInt(viewId, "setMaxHeight", bitmap.height)
        }
    }
}
