package io.github.jmatts94.ratkingrecon

import android.app.Activity
import android.app.Application
import android.os.Bundle

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
