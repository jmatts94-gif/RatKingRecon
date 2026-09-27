package io.github.jmatts94.ratkingrecon

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google's consent form (UMP) - the privacy popup AdMob requires for players
 * in the UK and EEA before it will serve them an ad.
 *
 * Asked lazily, from [RewardedAds.showRewardedAd], not at launch: ads here are
 * never forced, so a player who never taps "watch an ad" should never meet a
 * consent popup either. Outside the UK/EEA the form is never required and
 * [gather] answers straight away.
 *
 * What the form actually says is set in the AdMob console (Privacy &
 * messaging), not here - with no message published there, UMP reports
 * nothing required and ads are requested as normal.
 */
object AdConsent {

    private val adsInitialised = AtomicBoolean(false)

    private fun info(context: Context): ConsentInformation =
        UserMessagingPlatform.getConsentInformation(context)

    /**
     * Refreshes consent status, shows the form if this player still needs to
     * answer it, then calls [onDone] exactly once with whether an ad may be
     * requested. A failed refresh (offline, say) falls back to whatever the
     * player answered last time.
     */
    fun gather(activity: Activity, onDone: (canRequestAds: Boolean) -> Unit) {
        val info = info(activity)
        val finish = {
            val can = info.canRequestAds()
            if (can) initialiseAds(activity)
            onDone(can)
        }
        info.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            { UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { finish() } },
            { finish() }
        )
    }

    /** Whether Settings should offer a way back into the form - only true for UK/EEA players. */
    fun privacyOptionsRequired(context: Context): Boolean =
        info(context).privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    /** Reopens the form so a player can change their answer - Google requires this be reachable. */
    fun showPrivacyOptions(activity: Activity, onDone: () -> Unit) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { onDone() }
    }

    /**
     * AdMob's own init, once, and only after consent allows it. Its call
     * blocks on a network round trip, so it runs off the main thread; an ad
     * requested before it finishes still loads, just a little slower.
     */
    private fun initialiseAds(context: Context) {
        if (!adsInitialised.compareAndSet(false, true)) return
        val app = context.applicationContext
        AppScope.launch { MobileAds.initialize(app) }
    }
}
