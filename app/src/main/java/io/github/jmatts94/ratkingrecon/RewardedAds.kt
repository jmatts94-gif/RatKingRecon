package io.github.jmatts94.ratkingrecon

import android.app.Activity
import android.content.SharedPreferences
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * "Watch an ad for Scrap" - up to [DAILY_LIMIT] times a day, on the player's
 * own local clock (see [DailySteps.dayStamp], shared with [LedgerTasks] for
 * the same reason).
 *
 * A watch is only ever counted, and Scrap only ever paid, from
 * [RewardedAd.OnUserEarnedRewardListener] - the one callback AdMob guarantees
 * fires if and only if the player watched to completion. Closing the ad early
 * costs nothing and pays nothing, the same as declining any other offer here.
 */
object RewardedAds {

    const val DAILY_LIMIT = 3

    /** Real in release, Google's test unit in debug - see the build types in app/build.gradle.kts. */
    private val AD_UNIT_ID = BuildConfig.REWARDED_AD_UNIT_ID

    /** Scrap one completed watch pays - a bit above the short 50-step bounty (8-15), never a way to skip the economy. */
    val SCRAP_REWARD = 25..40

    private const val KEY_DAY = "REWARDED_AD_DAY"
    private const val KEY_COUNT = "REWARDED_AD_COUNT"

    /** Watches spent today, resetting the moment a call here lands on a new local day. */
    fun watchesToday(prefs: SharedPreferences, now: Long = System.currentTimeMillis()): Int {
        val today = DailySteps.dayStamp(now)
        return if (prefs.getInt(KEY_DAY, 0) == today) prefs.getInt(KEY_COUNT, 0) else 0
    }

    fun canWatch(prefs: SharedPreferences, now: Long = System.currentTimeMillis()): Boolean =
        watchesToday(prefs, now) < DAILY_LIMIT

    /** Books one watch against today's count, rolling the day over first if needed. */
    private fun recordWatch(prefs: SharedPreferences, now: Long = System.currentTimeMillis()) {
        val today = DailySteps.dayStamp(now)
        val current = watchesToday(prefs, now)
        prefs.edit()
            .putInt(KEY_DAY, today)
            .putInt(KEY_COUNT, current + 1)
            .apply()
    }

    /**
     * Loads and immediately shows a rewarded ad, calling [onResult] exactly
     * once with the Scrap earned (0 for "declined, failed to load, or closed
     * early").
     *
     * Loads on demand rather than pre-caching, trading a few seconds of
     * spinner for not holding a loaded ad hostage while the player does
     * something else first - simple over snappy, for a button that is not on
     * the game's critical path.
     */
    fun showRewardedAd(activity: Activity, prefs: SharedPreferences, onResult: (Int) -> Unit) {
        // Consent first: a UK/EEA player sees Google's consent form on their
        // first watch, never at launch, so a player who never taps an ad
        // never sees a privacy popup either.
        AdConsent.gather(activity) { canRequestAds ->
            if (!canRequestAds) onResult(0) else loadAndShow(activity, prefs, onResult)
        }
    }

    private fun loadAndShow(activity: Activity, prefs: SharedPreferences, onResult: (Int) -> Unit) {
        RewardedAd.load(
            activity,
            AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    onResult(0)
                }

                override fun onAdLoaded(ad: RewardedAd) {
                    // Set before show(), not after: show() only starts the ad,
                    // it does not block, so a callback attached afterward
                    // could lose a dismiss that fires first.
                    var earned = 0
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            // Zero here means the player backed out before
                            // the reward below ever fired.
                            onResult(earned)
                        }

                        override fun onAdFailedToShowFullScreenContent(error: AdError) {
                            onResult(0)
                        }
                    }

                    ad.show(activity) {
                        // Fires only on a genuine completed watch - see this
                        // object's own doc comment.
                        earned = SCRAP_REWARD.random()
                        recordWatch(prefs)
                    }
                }
            }
        )
    }
}
