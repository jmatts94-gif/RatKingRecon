package com.example.ratkingrecon

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

        findViewById<MaterialButton>(R.id.exportSaveButton).setOnClickListener {
            exportPicker.launch(defaultFileName())
        }

        // The warning comes before the picker, so the player is not asked to
        // find a file and only then told what it is about to do.
        findViewById<MaterialButton>(R.id.importSaveButton).setOnClickListener {
            confirmImport()
        }

        findViewById<MaterialButton>(R.id.settingsBackButton).setOnClickListener { finish() }
    }

    private fun defaultFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "ratkingrecon-save-$stamp.json"
    }

    private fun confirmImport() {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_confirm_import)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

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
