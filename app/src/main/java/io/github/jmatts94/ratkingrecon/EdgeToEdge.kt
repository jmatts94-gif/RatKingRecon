package io.github.jmatts94.ratkingrecon

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Pads each screen's root view for the system bars and any display cutout,
 * in place of the deprecated `fitsSystemWindows` + themed bar-color combo
 * every layout used to rely on.
 *
 * Both stop being honored once an app targets API 35: edge-to-edge is
 * enforced regardless, and content that isn't explicitly padded for the
 * insets draws underneath the status bar, nav bar, and any cutout - on a
 * device with none of that reserved space accounted for, the visible
 * content area shrinks and everything above/below it reads as cropped.
 * Every activity here targets API 36, so every one of them needs this.
 */
object EdgeToEdge {

    fun apply(activity: Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)

        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) ?: return

        // Whatever padding the layout already declared is the baseline the
        // inset gets added on top of, not replaced by - a screen with its
        // own edge padding should keep it once the bars are accounted for.
        val basePadding = Rect(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view: View, insets: WindowInsetsCompat ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                basePadding.left + bars.left,
                basePadding.top + bars.top,
                basePadding.right + bars.right,
                basePadding.bottom + bars.bottom
            )
            insets
        }
    }
}
