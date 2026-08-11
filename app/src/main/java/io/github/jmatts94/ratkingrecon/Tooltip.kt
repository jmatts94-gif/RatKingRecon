package io.github.jmatts94.ratkingrecon

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.StringRes

/**
 * Long-press explainers for any view.
 *
 * Deliberately generic: nothing in here knows about a particular button, so
 * attaching one elsewhere is a single call. Wire a view up with
 *
 *     Tooltip.attachTo(myButton, R.string.my_title, R.string.my_body)
 *
 * and the long-press handler, bubble and dismissal are all taken care of.
 */
object Tooltip {

    private const val MIN_WIDTH_DP = 220
    private const val WIDTH_FRACTION = 0.92f

    /** Long-pressing [view] pops up an explainer. Replaces any existing long-press handler. */
    fun attachTo(view: View, @StringRes titleRes: Int, @StringRes bodyRes: Int) {
        view.setOnLongClickListener {
            show(it, titleRes, bodyRes)
            true // consume it, so the press does not also fire a click
        }
    }

    /** Shows the bubble anchored under [anchor]. Public so a tooltip can be triggered manually. */
    fun show(anchor: View, @StringRes titleRes: Int, @StringRes bodyRes: Int) {
        val bubble = LayoutInflater.from(anchor.context)
            .inflate(R.layout.view_tooltip, null)

        bubble.findViewById<TextView>(R.id.tooltipTitle).setText(titleRes)
        bubble.findViewById<TextView>(R.id.tooltipBody).setText(bodyRes)

        val width = maxOf((anchor.width * WIDTH_FRACTION).toInt(), anchor.dp(MIN_WIDTH_DP))

        val popup = PopupWindow(bubble, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            // A background is required for taps outside the bubble to dismiss it;
            // the visible surface comes from the layout itself.
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = anchor.dp(8).toFloat()
        }

        bubble.setOnClickListener { popup.dismiss() }
        popup.showAsDropDown(anchor, anchor.dp(8), anchor.dp(-4))
    }

    private fun View.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
