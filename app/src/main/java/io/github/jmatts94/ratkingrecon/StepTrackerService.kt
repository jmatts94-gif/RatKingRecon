package io.github.jmatts94.ratkingrecon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Keeps counting steps while the app is closed or the screen is locked, and
 * fires a notification the moment an egg hatches.
 *
 * This service is the *only* owner of the step sensor. MainActivity no longer
 * registers a listener; it reads progress from SharedPreferences and refreshes
 * when this service broadcasts [ACTION_STATE_CHANGED].
 */
class StepTrackerService : Service(), SensorEventListener {

    companion object {
        /** Quiet, permanent notification Android requires for a foreground service. */
        private const val CHANNEL_ONGOING = "step_tracking"

        /**
         * Hatch alerts. A channel's sound is fixed when it is created, so this id
         * is versioned - bump it if the sound or importance ever changes.
         */
        private const val CHANNEL_HATCH = "hatch_alerts_v1"

        /**
         * The same alert, without the sound or the buzz.
         *
         * Android fixes a channel's sound and vibration when it is created and
         * ignores any later attempt to change them - deleting and recreating the
         * channel does not help either, because the old settings are remembered.
         * So the sound switch cannot mute a channel; it picks a different one.
         */
        private const val CHANNEL_HATCH_QUIET = "hatch_alerts_quiet_v1"

        /**
         * The live daily step count. Low importance and permanently silent, so
         * the running update never makes a sound however often it is re-posted.
         */
        private const val CHANNEL_STEPS = "steps_today_v1"

        private const val NOTIF_ONGOING = 1
        private const val NOTIF_HATCH = 2

        /**
         * The daily count, and a banked boss.
         *
         * 5 and 6 because 3 and 4 belong to [EncounterActionReceiver] - the
         * encounter alert and its result. Ids are per-app, not per-class, and
         * these two both shipped on top of that pair: the step count is
         * re-posted on every batch of steps, so it was overwriting the encounter
         * alert, which is the only way in to a fight.
         *
         * [LedgerTaskAlerts] owns 20 upwards.
         */
        private const val NOTIF_STEPS = 5
        private const val NOTIF_BOSS = 6

        /**
         * Shortest gap between two step-count posts.
         *
         * The sensor can report several times a second while walking, and this
         * notification was being re-posted on every one of them. Android's
         * notification service rate limits how fast a package may enqueue and
         * drops what comes in over the line - silently, like every other
         * notification failure - so an update per sensor event is a good way to
         * have most of them thrown away. Once a second is far below the limit
         * and still reads as live.
         */
        private const val STEP_POST_MIN_GAP_MS = 1_000L

        const val ACTION_STATE_CHANGED = "io.github.jmatts94.ratkingrecon.STATE_CHANGED"

        // Carried so a visible Activity can react in-app rather than re-deriving
        // what just happened from the save.
        const val EXTRA_HATCHED_ART = "hatched_art"
        const val EXTRA_HATCHED_NAME = "hatched_name"
        const val EXTRA_BOUNTY_REWARD = "bounty_reward"
        const val EXTRA_BOUNTY_FAILED = "bounty_failed"

        /**
         * Starts tracking, returning whether the start was accepted.
         *
         * Legal from a visible Activity, and from BOOT_COMPLETED /
         * MY_PACKAGE_REPLACED, which Android exempts from the background
         * foreground-service restriction. Any other caller may be refused.
         *
         * Never throws. A refusal here is recoverable - the next app launch
         * starts tracking - whereas an uncaught exception in a boot receiver
         * would surface as a crash while the phone is starting up.
         */
        fun start(context: Context): Boolean = try {
            val intent = Intent(context, StepTrackerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException on API 31+, SecurityException
            // if ACTIVITY_RECOGNITION was revoked. Neither is worth crashing over.
            Log.w(TAG, "Step tracking could not start: ${e.javaClass.simpleName}: ${e.message}")
            false
        }

        private const val TAG = "StepTrackerService"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var dao: RatDao
    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null

    /**
     * Sensor events are delivered here rather than on the main thread, because
     * hatching now writes to Room and Room refuses main-thread I/O.
     */
    private var sensorThread: HandlerThread? = null

    /** When the step count was last posted; see [STEP_POST_MIN_GAP_MS]. */
    private var lastStepPostAt = 0L

    override fun onCreate() {
        super.onCreate()

        // START_STICKY can bring us back long after the fact - if the permission
        // has since been revoked, going foreground would throw SecurityException
        // and crash the process, so bow out quietly instead.
        if (!hasActivityRecognition()) {
            stopSelf()
            return
        }

        prefs = getSharedPreferences("SaveData", Context.MODE_PRIVATE)
        createChannels()
        startInForeground()

        // One line at startup saying whether anything we post can be seen at
        // all. Without it, a blocked app and a broken feature look identical
        // from the outside - both are simply nothing appearing.
        Log.i(
            TAG,
            "Step tracking started. Notifications enabled for this app: " +
                NotificationManagerCompat.from(this).areNotificationsEnabled()
        )

        sensorThread = HandlerThread("step-sensor").apply { start() }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        stepSensor?.let { sensor ->
            val handler = Handler(sensorThread!!.looper)
            // Opening the database and running the legacy import both block, so
            // they happen on the sensor thread, not here.
            handler.post { dao = RatRepository.dao(this) }
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restart if the system kills us; the sensor is cumulative so nothing is lost.
        return START_STICKY
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        sensorThread?.quitSafely()
        sensorThread = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasActivityRecognition(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_STEP_COUNTER) return

        // Guards the window between registering the listener and the posted
        // initialisation above completing.
        if (!::dao.isInitialized) return

        val outcome = GameEngine.onSteps(dao, prefs, event.values[0])
        if (!outcome.changed) return

        // Stay quiet if the player is already looking at the app - MainActivity
        // reveals the rat in-app from the broadcast below - or if they have
        // turned hatch notifications off in Settings. The hatch itself still
        // happened either way; only the announcement is suppressed.
        outcome.hatched?.let {
            if (!AppVisibility.isForeground && GameSettings.notificationsEnabled(prefs)) {
                notifyHatch(it, outcome.newLevel)
            }
        }

        // An encounter is always announced, even with the app open: it needs a
        // decision from the player, unlike a hatch which is just news. That is
        // also why the notifications switch does not cover it - this alert is
        // the only way in to the fight, so hiding it would strand the encounter.
        outcome.encounter?.let { notifyEncounter(it) }

        // A banked boss follows the hatch rules instead: suppressed when the app
        // is open and covered by the notifications switch. Both are safe here in
        // a way they would not be for an encounter, because the boss is already
        // recorded in the save - MainActivity raises the fight on its next
        // onResume whether or not this alert was ever shown.
        outcome.bossBanked?.let {
            if (!AppVisibility.isForeground && GameSettings.notificationsEnabled(prefs)) {
                notifyBossBanked(it)
            }
        }

        notifyStepsToday(outcome.stepsToday)

        sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_HATCHED_ART, outcome.hatched?.artKey)
                .putExtra(EXTRA_HATCHED_NAME, outcome.hatched?.name)
                .putExtra(EXTRA_BOUNTY_REWARD, outcome.bountyReward)
                .putExtra(EXTRA_BOUNTY_FAILED, outcome.bountyFailed)
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---- notifications -------------------------------------------------------

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ONGOING,
                getString(R.string.channel_tracking_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_tracking_desc)
                setShowBadge(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                EncounterActionReceiver.CHANNEL_ENCOUNTER,
                getString(R.string.channel_encounter_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_encounter_desc)
                enableVibration(true)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                EncounterActionReceiver.CHANNEL_ENCOUNTER_QUIET,
                getString(R.string.channel_encounter_quiet_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_encounter_desc)
                enableVibration(false)
                setSound(null, null)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_HATCH,
                getString(R.string.channel_hatch_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_hatch_desc)
                enableVibration(true)
                setSound(
                    Uri.parse("android.resource://$packageName/${R.raw.hatch}"),
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                )
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_HATCH_QUIET,
                getString(R.string.channel_hatch_quiet_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_hatch_desc)
                enableVibration(false)
                setSound(null, null)
            }
        )

        // Never alerts: this one is re-posted every time the sensor reports, so
        // an audible channel would buzz continuously all day.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STEPS,
                getString(R.string.channel_steps_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_steps_desc)
                enableVibration(false)
                setSound(null, null)
                setShowBadge(false)
            }
        )
    }

    private fun startInForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setContentTitle(getString(R.string.notif_tracking_title))
            .setContentText(getString(R.string.notif_tracking_text))
            .setSmallIcon(R.drawable.ic_footprint)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ONGOING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIF_ONGOING, notification)
        }
    }

    private fun notifyHatch(card: RatEntity, newLevel: Int) {
        val body = if (newLevel > 0) {
            getString(R.string.notif_hatch_text_level, card.name, newLevel)
        } else {
            getString(R.string.notif_hatch_text, card.name)
        }

        val quiet = !GameSettings.soundEnabled(prefs)

        val notification = NotificationCompat.Builder(
            this,
            if (quiet) CHANNEL_HATCH_QUIET else CHANNEL_HATCH
        )
            .setContentTitle(getString(R.string.notif_hatch_title))
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_egg)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            // Show on the lock screen - there is nothing private in a rat name.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            // The channel covers Android 8 and up; this is what silences the
            // same notification on 7 and below, where there are no channels.
            .setSilent(quiet)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_HATCH, notification)
    }

    /**
     * The running count of today's steps.
     *
     * Deliberately a second notification rather than the foreground-service one
     * above. That notification is pinned - Android will not let a foreground
     * service drop it - and the player asked to be able to clear this away. So
     * it stands on its own: not ongoing, so it swipes off like anything else,
     * and re-posted on the next batch of steps, so clearing it costs the feature
     * only until the player walks again.
     *
     * setOnlyAlertOnce is belt and braces next to a silent low-importance
     * channel; on Android 7 and below, where there are no channels, it and
     * setSilent are what keep the update quiet.
     */
    private fun notifyStepsToday(steps: Int) {
        // notify() is a silent no-op when the app cannot post - POST_NOTIFICATIONS
        // denied on 13+, or the app or channel muted in system settings. Nothing
        // throws and nothing returns a failure, so without this the feature just
        // appears not to exist. See [canPostNotifications].
        if (!canPostNotifications(CHANNEL_STEPS)) return

        // elapsedRealtime rather than wall clock, so changing the phone's time
        // cannot stall the counter or make every post look overdue.
        val now = SystemClock.elapsedRealtime()
        if (lastStepPostAt != 0L && now - lastStepPostAt < STEP_POST_MIN_GAP_MS) return
        lastStepPostAt = now

        val notification = NotificationCompat.Builder(this, CHANNEL_STEPS)
            .setContentTitle(getString(R.string.notif_steps_title))
            .setContentText(getString(R.string.notif_steps_text, steps))
            .setSmallIcon(R.drawable.ic_footprint)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            // Left standing when tapped: it is a live readout, not an
            // announcement, so dismissing it should be the player's choice.
            .setAutoCancel(false)
            .setContentIntent(openAppIntent())
            .build()

        val manager = getSystemService(NotificationManager::class.java)

        Log.d(
            TAG,
            "About to post step count: $steps steps. " +
                "NOTIF_STEPS=$NOTIF_STEPS channel=$CHANNEL_STEPS | " +
                "NOTIF_ONGOING=$NOTIF_ONGOING channel=$CHANNEL_ONGOING | " +
                "same id? ${NOTIF_STEPS == NOTIF_ONGOING}"
        )

        try {
            manager.notify(NOTIF_STEPS, notification)
            Log.d(TAG, "notify($NOTIF_STEPS) returned without throwing")
        } catch (t: Throwable) {
            Log.e(TAG, "notify($NOTIF_STEPS) threw ${t.javaClass.simpleName}: ${t.message}", t)
            return
        }

        logActiveNotifications(manager)
    }

    /**
     * What the system says this app actually has on screen, right after posting.
     *
     * The decisive check. [NotificationManager.notify] reports nothing back, so
     * a post that is accepted and a post that is dropped look identical at the
     * call site. This asks the other side of the fence: if the id just posted is
     * missing from the active list, the system took it and discarded it, and
     * whatever is in the list instead says what it kept.
     */
    private fun logActiveNotifications(manager: NotificationManager) {
        val active = try {
            manager.activeNotifications
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read active notifications: ${t.javaClass.simpleName}")
            return
        }

        val summary = active.joinToString(", ") { posted ->
            "id=${posted.id} channel=${posted.notification.channelId}"
        }

        Log.d(TAG, "Active notifications for this app (${active.size}): [$summary]")

        if (active.none { it.id == NOTIF_STEPS }) {
            Log.w(
                TAG,
                "id=$NOTIF_STEPS is NOT in the active list immediately after posting it. " +
                    "The system accepted the call and dropped the notification."
            )
        }
    }

    /**
     * Whether a notification posted to [channelId] can actually be seen.
     *
     * There is no error path out of [NotificationManager.notify]: if the app is
     * not allowed to post, it returns normally and nothing appears. Three
     * separate things cause that, and none of them is visible from the call
     * site - POST_NOTIFICATIONS denied on Android 13+, notifications turned off
     * for the whole app, or this one channel muted to IMPORTANCE_NONE.
     *
     * Logged rather than merely returned, because the symptom on a device is a
     * feature that looks unimplemented, and the log is the only thing that
     * distinguishes "never fired" from "fired and was dropped".
     */
    private fun canPostNotifications(channelId: String): Boolean {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Log.w(
                TAG,
                "Notifications are blocked for this app, so nothing will be posted. " +
                    "On Android 13+ this is usually POST_NOTIFICATIONS being denied; " +
                    "it is also what the system's per-app notification toggle does."
            )
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = getSystemService(NotificationManager::class.java)
                .getNotificationChannel(channelId)

            if (channel == null) {
                Log.w(TAG, "Channel $channelId does not exist; createChannels() has not run.")
                return false
            }
            if (channel.importance == NotificationManager.IMPORTANCE_NONE) {
                Log.w(TAG, "Channel $channelId is muted in system settings.")
                return false
            }
        }

        return true
    }

    /**
     * Announces a boss banked while the player was walking.
     *
     * Carries no action buttons, and that is the point. An ordinary encounter
     * offers Auto-Resolve so it can be settled from the lock screen; a boss must
     * not be, so the only thing this notification does is open the app, where
     * [MainActivity] builds the fight in front of the player.
     */
    private fun notifyBossBanked(spec: BossSpec) {
        val quiet = !GameSettings.soundEnabled(prefs)

        val notification = NotificationCompat.Builder(
            this,
            EncounterActionReceiver.channelFor(quiet)
        )
            .setContentTitle(getString(R.string.notif_boss_title))
            .setContentText(getString(R.string.notif_boss_text, getString(spec.nameRes)))
            .setSmallIcon(spec.badgeRes)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .setSilent(quiet)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_BOSS, notification)
    }

    /**
     * Offers the two ways to settle an encounter.
     *
     * Auto-Resolve is a broadcast rather than an activity so it works from the
     * lock screen without the phone ever being unlocked.
     */
    private fun notifyEncounter(encounter: Encounter) {
        val fight = PendingIntent.getActivity(
            this,
            1,
            Intent(this, BattleActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val auto = PendingIntent.getBroadcast(
            this,
            2,
            Intent(this, EncounterActionReceiver::class.java)
                .setAction(EncounterActionReceiver.ACTION_AUTO_RESOLVE)
                .setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val ratName = RatRepository.dao(this).byId(encounter.ratId)?.name ?: "Your rat"

        val quiet = !GameSettings.soundEnabled(prefs)

        val notification = NotificationCompat.Builder(
            this,
            EncounterActionReceiver.channelFor(quiet)
        )
            .setContentTitle(getString(R.string.notif_encounter_title))
            .setContentText(getString(R.string.notif_encounter_text, ratName, encounter.botName))
            .setSmallIcon(R.drawable.ic_power)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(fight)
            .addAction(0, getString(R.string.notif_action_fight), fight)
            .addAction(0, getString(R.string.notif_action_auto), auto)
            .setSilent(quiet)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(EncounterActionReceiver.NOTIF_ENCOUNTER, notification)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE
    )
}
