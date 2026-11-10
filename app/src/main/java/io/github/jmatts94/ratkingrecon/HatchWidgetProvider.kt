package io.github.jmatts94.ratkingrecon

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import java.text.NumberFormat

/**
 * Home-screen widget: steps to the next hatch.
 *
 * Android's own timer for widgets runs at most every 30 minutes, so the system
 * only draws this when it is placed. [StepTrackerService] and the home screen
 * push every later update through [refresh].
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

            val views = RemoteViews(context.packageName, R.layout.widget_hatch)
            views.setTextViewText(
                R.id.widgetHatchTitle,
                context.getString(R.string.widget_steps_left, NumberFormat.getInstance().format(left))
            )
            views.setTextViewText(R.id.widgetHatchLevel, context.getString(R.string.widget_level, level))
            views.setProgressBar(R.id.widgetHatchBar, needed, exp, false)

            // The normal launch route, so onboarding still comes first on a new save.
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                views.setOnClickPendingIntent(
                    R.id.widgetHatchRoot,
                    PendingIntent.getActivity(
                        context,
                        0,
                        launch,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

            manager.updateAppWidget(ids, views)
        }
    }
}
