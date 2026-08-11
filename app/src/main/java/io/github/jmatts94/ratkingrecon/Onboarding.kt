package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** One full-screen card in the first-launch walkthrough. */
data class OnboardingPage(
    @param:DrawableRes val iconRes: Int,
    @param:StringRes val titleRes: Int,
    @param:StringRes val bodyRes: Int
)

/**
 * The first-launch walkthrough, and the flag that retires it.
 *
 * [KEY_COMPLETE] lives in the same "SaveData" preferences as everything else,
 * which is what makes "never again unless the save is reset" fall out for free:
 * Reset Save clears the file, so the walkthrough comes back for the fresh start
 * that follows. It also means the flag travels with an exported save.
 *
 * Every icon here is already in the app - the walkthrough introduces no artwork
 * of its own, so nothing has to be redrawn when the real art lands.
 */
object Onboarding {

    const val KEY_COMPLETE = "onboarding_complete"

    fun isComplete(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_COMPLETE, false)

    fun markComplete(prefs: SharedPreferences) {
        prefs.edit().putBoolean(KEY_COMPLETE, true).apply()
    }

    val pages = listOf(
        OnboardingPage(
            iconRes = R.drawable.ic_footprint,
            titleRes = R.string.onboarding_welcome_title,
            bodyRes = R.string.onboarding_welcome_body
        ),
        OnboardingPage(
            iconRes = R.drawable.ic_egg,
            titleRes = R.string.onboarding_hatch_title,
            bodyRes = R.string.onboarding_hatch_body
        ),
        OnboardingPage(
            iconRes = R.drawable.ic_book,
            titleRes = R.string.onboarding_ledger_title,
            bodyRes = R.string.onboarding_ledger_body
        ),
        OnboardingPage(
            iconRes = R.drawable.ic_power,
            titleRes = R.string.onboarding_fight_title,
            bodyRes = R.string.onboarding_fight_body
        ),
        OnboardingPage(
            iconRes = R.drawable.ic_pets,
            titleRes = R.string.onboarding_ready_title,
            bodyRes = R.string.onboarding_ready_body
        )
    )
}
