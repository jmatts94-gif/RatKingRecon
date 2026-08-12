package io.github.jmatts94.ratkingrecon

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Save export and import.
 *
 * Both halves go through the Storage Access Framework, so the file lands
 * wherever the player chooses and survives uninstalling the app - which is the
 * point of a backup, and something app-internal storage could not offer.
 *
 * All the real work blocks (Room queries, file I/O, a committing preference
 * write), so every path here hops to [Dispatchers.IO] and comes back to report.
 */
class SettingsActivity : AppCompatActivity() {

    private companion object {
        const val MIME_JSON = "application/json"

        /**
         * Import accepts anything.
         *
         * Filtering on application/json looks tidier but hides the player's own
         * backup whenever the provider it was saved to reports a different type,
         * which is common enough to make the feature look broken.
         */
        val IMPORT_TYPES = arrayOf("*/*")

        /** What the debug top-up hands over. Debug builds only; see [grantDebugBundle]. */
        const val DEBUG_SCRAP = 1_500
        const val DEBUG_RATS = 10
    }

    private lateinit var statusText: TextView

    /** Export: the player names the file, then we write into whatever they picked. */
    private val exportPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument(MIME_JSON)
    ) { uri -> uri?.let { writeSaveTo(it) } }

    private val importPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { readSaveFrom(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        statusText = findViewById(R.id.saveStatusText)

        bindToggles()
        bindAbout()

        findViewById<MaterialButton>(R.id.exportSaveButton).setOnClickListener {
            exportPicker.launch(defaultFileName())
        }

        findViewById<MaterialButton>(R.id.resetSaveButton).setOnClickListener {
            confirmReset()
        }

        // The warning comes before the picker, so the player is not asked to
        // find a file and only then told what it is about to do.
        findViewById<MaterialButton>(R.id.importSaveButton).setOnClickListener {
            confirmImport()
        }

        findViewById<MaterialButton>(R.id.settingsBackButton).setOnClickListener { finish() }
    }

    // ---- toggles -------------------------------------------------------------

    /**
     * Both switches write straight through on change.
     *
     * Nothing caches them: [StepTrackerService] reads the flag at the moment it
     * is about to post, so a switch flipped here takes effect on the very next
     * alert without the service needing to be told.
     */
    private fun bindToggles() {
        val prefs = RatRepository.prefs(this)

        val notifications = findViewById<MaterialSwitch>(R.id.notificationsSwitch)
        notifications.isChecked = GameSettings.notificationsEnabled(prefs)
        notifications.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(GameSettings.KEY_NOTIFICATIONS, on).apply()
        }

        val sound = findViewById<MaterialSwitch>(R.id.soundSwitch)
        sound.isChecked = GameSettings.soundEnabled(prefs)
        sound.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(GameSettings.KEY_SOUND, on).apply()
        }
    }

    /** Reads the version from the installed package, so it can never drift from the build. */
    private fun bindAbout() {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

        val versionText = findViewById<TextView>(R.id.aboutVersionText)
        versionText.text = getString(R.string.about_version, info.versionName, code)

        // Debug builds only, and behind a long press with nothing advertising
        // it. BuildConfig.DEBUG is a genuine compile-time false in release - AGP
        // writes `= false` there and `Boolean.parseBoolean("true")` in debug
        // precisely so this folds - so the registrations below are compiled out
        // and there is no way to reach either in a release build.
        //
        // R8 is on for release now, so the methods they call are dropped from
        // the release DEX as well: nothing reaches them, so nothing keeps them.
        // The guarantee is absence rather than merely unreachability.
        if (BuildConfig.DEBUG) {
            versionText.setOnLongClickListener {
                forceDebugEncounter()
                true
            }

            // The line above the version, in the same card and behind the same
            // gesture, so both cheats are found the same way or not at all.
            findViewById<TextView>(R.id.aboutNameText).setOnLongClickListener {
                grantDebugBundle()
                true
            }
        }
    }

    /**
     * Debug only: tops up Scrap and fills out the Ledger.
     *
     * The rats come from [GameEngine.mintRat], which is the roll a walked hatch
     * makes - same species pool, same stat range, same shiny odds, same Room
     * insert, same milestone refresh. Nothing here mints a rat of its own, so a
     * cheated Ledger cannot hold anything the game could not have produced, and
     * this cannot drift from the real hatch as that changes.
     *
     * One consequence of using the real path: an armed serum or gleam is spent
     * by the first rat, exactly as it would be by the next walked hatch.
     */
    private fun grantDebugBundle() {
        lifecycleScope.launch {
            val prefs = RatRepository.prefs(this@SettingsActivity)

            val minted = withContext(Dispatchers.IO) {
                val dao = RatRepository.dao(this@SettingsActivity)
                List(DEBUG_RATS) { GameEngine.mintRat(dao, prefs) }
            }

            // Re-read rather than captured before the inserts: a bounty or a
            // task could have paid out while those were running.
            prefs.edit()
                .putInt(GameEngine.KEY_SCRAP, GameEngine.scrapOf(prefs) + DEBUG_SCRAP)
                .apply()

            Toast.makeText(
                this@SettingsActivity,
                getString(R.string.debug_bundle_granted, DEBUG_SCRAP, minted.size),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Debug only: raises a Rustbot on the spot and opens the fight.
     *
     * Builds nothing of its own. [GameEngine.raiseEncounter] is the same call
     * the step roll makes once it has decided an encounter happens, so a forced
     * fight is indistinguishable from a walked one - and it stays that way
     * without anyone remembering to keep two copies in step.
     *
     * An encounter already waiting is opened rather than replaced, so this
     * cannot quietly discard a fight the player was about to have.
     */
    private fun forceDebugEncounter() {
        lifecycleScope.launch {
            val prefs = RatRepository.prefs(this@SettingsActivity)

            val encounter = withContext(Dispatchers.IO) {
                val dao = RatRepository.dao(this@SettingsActivity)

                // A waiting encounter is opened rather than replaced. One whose
                // rat is gone is cleared by loadFightable, so this falls through
                // and raises a fresh fight instead of reopening a dead one.
                Encounter.loadFightable(prefs, dao)?.first
                    ?: GameEngine.raiseEncounter(
                        dao = dao,
                        prefs = prefs,
                        playerLevel = GameEngine.levelOf(prefs)
                    )
            }

            if (encounter == null) {
                // strongestAvailable found nobody: an empty roster, or every
                // rat still recovering from a loss.
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.debug_encounter_no_rat),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            Toast.makeText(
                this@SettingsActivity,
                getString(R.string.debug_encounter_raised, encounter.botName),
                Toast.LENGTH_SHORT
            ).show()
            startActivity(Intent(this@SettingsActivity, BattleActivity::class.java))
        }
    }

    // ---- reset ---------------------------------------------------------------

    private fun confirmReset() {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_confirm_reset)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.Animation_RatKing_Dialog)

        dialog.findViewById<Button>(R.id.resetConfirmButton).setOnClickListener {
            dialog.dismiss()
            resetSave()
        }
        dialog.findViewById<Button>(R.id.resetCancelButton).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    /**
     * Wipes the save.
     *
     * Both halves go, for the same reason export writes both: the roster lives
     * in Room, so clearing only the preferences would leave a Level 1 player
     * still holding every rat they had.
     *
     * The two switches are put back afterwards. They are settings rather than
     * progress, and having them silently flip themselves on from this very
     * screen would be a surprise.
     */
    private fun resetSave() {
        setBusy(true)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val prefs = RatRepository.prefs(this@SettingsActivity)
                val notifications = GameSettings.notificationsEnabled(prefs)
                val sound = GameSettings.soundEnabled(prefs)

                // As with import: keep a sensor event from landing mid-wipe.
                stopService(Intent(this@SettingsActivity, StepTrackerService::class.java))

                prefs.edit().clear()
                    .putBoolean(GameSettings.KEY_NOTIFICATIONS, notifications)
                    .putBoolean(GameSettings.KEY_SOUND, sound)
                    .commit()

                RatRepository.dao(this@SettingsActivity).deleteAll()
            }

            setBusy(false)
            StepTrackerService.start(this@SettingsActivity)
            report(getString(R.string.reset_done))
        }
    }

    private fun defaultFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "ratkingrecon-save-$stamp.json"
    }

    private fun confirmImport() {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_confirm_import)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setWindowAnimations(R.style.Animation_RatKing_Dialog)

        dialog.findViewById<Button>(R.id.importConfirmButton).setOnClickListener {
            dialog.dismiss()
            importPicker.launch(IMPORT_TYPES)
        }
        dialog.findViewById<Button>(R.id.importCancelButton).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    // ---- export --------------------------------------------------------------

    private fun writeSaveTo(uri: Uri) {
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = SaveTransfer.export(
                        RatRepository.prefs(this@SettingsActivity),
                        RatRepository.dao(this@SettingsActivity)
                    )

                    // "wt" truncates. Overwriting a longer previous export
                    // without it would leave the old tail behind the new JSON.
                    contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                        stream.write(json.toByteArray(Charsets.UTF_8))
                    } ?: throw IOException("that file could not be opened for writing")

                    json.toByteArray(Charsets.UTF_8).size
                }
            }

            setBusy(false)
            result
                .onSuccess { bytes ->
                    report(getString(R.string.export_done, bytes / 1024f))
                }
                .onFailure { error ->
                    report(getString(R.string.export_failed, reasonFor(error)))
                }
        }
    }

    // ---- import --------------------------------------------------------------

    private fun readSaveFrom(uri: Uri) {
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    } ?: throw IOException("that file could not be opened")

                    // Parsed and checked before anything is written, so a file
                    // that turns out to be junk leaves the save untouched.
                    val snapshot = SaveTransfer.parse(raw)

                    // The step service writes to the same preferences from its
                    // sensor thread. Stopping it first keeps a stray reading
                    // from landing in the middle of the restore.
                    stopService(Intent(this@SettingsActivity, StepTrackerService::class.java))

                    SaveTransfer.apply(
                        snapshot,
                        RatRepository.prefs(this@SettingsActivity),
                        RatRepository.dao(this@SettingsActivity)
                    )

                    snapshot
                }
            }

            setBusy(false)

            // Tracking goes back on either way: it was stopped before the write,
            // and a failed import must not leave the player not counting steps.
            StepTrackerService.start(this@SettingsActivity)

            result
                .onSuccess { snapshot ->
                    report(getString(R.string.import_done, snapshot.rats.size))
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.import_done_toast,
                        Toast.LENGTH_LONG
                    ).show()
                }
                .onFailure { error ->
                    report(getString(R.string.import_failed, reasonFor(error)))
                }
        }
    }

    // ---- shared --------------------------------------------------------------

    /**
     * A sentence fit to show a player.
     *
     * [SaveFormatException] messages are written to be read; anything else is a
     * plumbing failure whose class name would mean nothing, so it gets the
     * message if there is one and a generic line if there is not.
     */
    private fun reasonFor(error: Throwable): String = when (error) {
        is SaveFormatException -> error.message ?: "that save could not be read"
        else -> error.message ?: "something went wrong"
    }

    private fun report(message: String) {
        statusText.text = message
    }

    private fun setBusy(busy: Boolean) {
        findViewById<MaterialButton>(R.id.exportSaveButton).isEnabled = !busy
        findViewById<MaterialButton>(R.id.importSaveButton).isEnabled = !busy
    }
}
