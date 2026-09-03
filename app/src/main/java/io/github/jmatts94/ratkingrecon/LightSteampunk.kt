package io.github.jmatts94.ratkingrecon

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AppCompatActivity

/**
 * A Context pinned to light mode regardless of the player's own Dark
 * Steampunk setting.
 *
 * Used by both ReconCard.kt and EnlargedRatDialog.kt's dialog_enlarged_rat.xml,
 * for the same reason: the Recon Card look - cream-to-tan card, brass frame,
 * circular gear-ring portrait - is a fixed identity, not something that
 * should read differently depending on the app's current theme, the same
 * way the Battle Arena screens are a deliberate exception to the light
 * palette everywhere else (see colors.xml's arena_bg_deep). Both places
 * still draw every colour from the app's own tokens (@color/text_primary
 * and the rest) rather than a parallel "always light" set that would drift
 * from them over time - forcing the configuration this narrowly is what
 * makes reusing those tokens safe.
 */
fun lightSteampunkContext(activity: AppCompatActivity): Context {
    val config = Configuration(activity.resources.configuration)
    config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
        Configuration.UI_MODE_NIGHT_NO
    return ContextThemeWrapper(
        activity.createConfigurationContext(config), R.style.Theme_RatKingRecon
    )
}
