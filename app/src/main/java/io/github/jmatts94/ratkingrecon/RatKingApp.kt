package io.github.jmatts94.ratkingrecon

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

        // Decoding happens off-thread, so this is done at launch rather than at
        // the moment a cue is wanted - otherwise the first hatch of a session,
        // the one most worth hearing, is the one that makes no sound.
        GameSounds.warmUp(this)

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
