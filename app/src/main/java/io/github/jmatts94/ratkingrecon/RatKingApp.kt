package io.github.jmatts94.ratkingrecon

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Tracks whether any of our screens is currently in front.
 *
 * [StepTrackerService] uses this to stay quiet when the player is already
 * looking at the app - the in-app reveal covers it, so a notification would just
 * be telling them something they can see.
 */
object AppVisibility {

    @Volatile
    private var startedActivities = 0

    val isForeground: Boolean get() = startedActivities > 0

    internal fun onStarted() {
        synchronized(this) { startedActivities++ }
    }

    internal fun onStopped() {
        synchronized(this) { if (startedActivities > 0) startedActivities-- }
    }
}

/**
 * A coroutine scope that outlives any single Activity.
 *
 * [MainActivity.maybeStartBankedBoss] used to run on `lifecycleScope`, which is
 * cancelled the moment the Activity that started it is destroyed. That left a
 * real gap: [Bosses.startBanked] writes the pending [Encounter] and clears the
 * bank *before* the coroutine resumes to actually open [BattleActivity], so an
 * Activity torn down in that narrow window - a resume that loses the screen
 * almost as soon as it gets it, which is exactly what "mid walk" looks like -
 * silently dropped the launch. The fight was still saved, but nothing was left
 * in the app that ever routed back to it. Work that must run to completion once
 * it has mutated the save belongs here instead, where only process death - not
 * a destroyed Activity - can interrupt it.
 */
object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default)

class RatKingApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Forces AppCompatDelegate's own night mode from the player's saved
        // toggle - see GameSettings.KEY_DARK_STEAMPUNK - independent of the
        // system's own dark mode setting, which this app otherwise ignores
        // (see Base.Theme.RatKingRecon's own comment). Has to happen before
        // any Activity is created: this is what makes values-night resolve
        // from the very first screen rather than only after a later toggle.
        AppCompatDelegate.setDefaultNightMode(
            if (GameSettings.darkSteampunkEnabled(RatRepository.prefs(this))) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )

        // Decoding happens off-thread, so this is done at launch rather than at
        // the moment a cue is wanted - otherwise the first hatch of a session,
        // the one most worth hearing, is the one that makes no sound.
        GameSounds.warmUp(this)

        // AdMob's own init call blocks on a network round trip; off the main
        // thread so a slow connection cannot stall the very first frame. A
        // rewarded ad requested before this finishes just fails to load - see
        // RewardedAds.showRewardedAd - rather than crashing, so there is
        // nothing here to gate on.
        AppScope.launch { MobileAds.initialize(this@RatKingApp) }

        // Counting started/stopped rather than resumed/paused means a dialog or a
        // transient overlay does not read as "the app went away".
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = AppVisibility.onStarted()
            override fun onActivityStopped(activity: Activity) = AppVisibility.onStopped()

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
