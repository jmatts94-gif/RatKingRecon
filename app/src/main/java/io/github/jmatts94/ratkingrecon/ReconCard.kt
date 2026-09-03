package io.github.jmatts94.ratkingrecon

import android.content.ContentValues
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.imageview.ShapeableImageView
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat

/**
 * Composites a branded share image for a moment a player already stops to
 * look at - a freshly hatched or spliced rat, or a Lifetime Steps milestone -
 * and hands it to the OS share sheet.
 *
 * No backend and no network call: card_recon_rat.xml / card_recon_steps.xml
 * are inflated off-screen at a fixed size, populated like any other view,
 * then rendered straight to a [Bitmap] with [View.draw] - the same technique
 * behind any "export this view as an image" feature, and the reason those two
 * layouts carry fixed dp dimensions on their root rather than match_parent.
 *
 * Where the export lands is tiered by API level - see [shareUriFor]. Q+
 * inserts into MediaStore, the same mechanism the Photos app's own share
 * button already uses, which is why the chooser's rich preview and every
 * target it can hand off to already know how to read it. A [FileProvider]
 * cache file only backs API 24-28, which predate scoped storage and cannot
 * use MediaStore this way - and never hit the bug the Q+ path exists to
 * dodge: on-device testing found this app's real sharesheet denying its own
 * preview loader (and the actual hand-off, not merely the thumbnail) read
 * access to a FileProvider Uri despite FLAG_GRANT_READ_URI_PERMISSION on
 * both the send and chooser intents - a timing quirk in a fairly new
 * "com.android.intentresolver" module, not anything wrong with the grant
 * itself.
 */
object ReconCard {

    private const val CARD_WIDTH_DP = 480
    private const val CARD_HEIGHT_DP = 600

    /** Always reads high on the dial - see GaugeDialView's own comment on why. */
    private const val GAUGE_NEEDLE_FRACTION = 0.85f

    private val stepsFormat: NumberFormat get() = NumberFormat.getIntegerInstance()

    /**
     * A [LayoutInflater] pinned to light mode regardless of the player's own
     * Dark Steampunk setting.
     *
     * Every colour in card_recon_rat.xml / card_recon_steps.xml resolves
     * through the same tokens the rest of the app uses (@color/text_primary
     * and friends), which is exactly right for in-app UI and exactly wrong
     * here - a Recon Card is a fixed exported image, and values-night's
     * swap of those same tokens for on-screen contrast reads as washed-out,
     * barely legible text once baked into a PNG. Forcing the configuration
     * this narrowly, only for the inflate, is what lets the card layouts
     * keep using the app's ordinary colour tokens at all rather than a
     * parallel "always light" set that would drift from them over time.
     */
    private fun lightInflater(activity: AppCompatActivity): LayoutInflater {
        val config = Configuration(activity.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            Configuration.UI_MODE_NIGHT_NO
        val lightContext = ContextThemeWrapper(
            activity.createConfigurationContext(config), R.style.Theme_RatKingRecon
        )
        return LayoutInflater.from(activity).cloneInContext(lightContext)
    }

    fun shareRat(activity: AppCompatActivity, pet: RatEntity) {
        val view = lightInflater(activity).inflate(R.layout.card_recon_rat, null) as ViewGroup

        view.findViewById<ShapeableImageView>(R.id.reconPortrait).setImageResource(pet.imageRes)

        val name = if (pet.shiny) activity.getString(R.string.card_name_shiny, pet.name) else pet.name
        view.findViewById<TextView>(R.id.reconName).text = name

        // Same artKey lookup EnlargedRatDialog uses for the same reason - see
        // its own comment: a rat's name is not guaranteed to match its species.
        val species = Roster.all.firstOrNull { it.artKey == pet.artKey }
        view.findViewById<TextView>(R.id.reconSubtitle).text = activity.getString(
            R.string.recon_card_subtitle,
            species?.faction ?: activity.getString(R.string.enlarged_rat_faction_unknown),
            species?.rarity ?: ""
        )

        view.findViewById<TextView>(R.id.reconPower).text = pet.effectivePower.toString()
        view.findViewById<TextView>(R.id.reconToughness).text = pet.effectiveToughness.toString()

        val gears = listOf(R.id.reconGear1, R.id.reconGear2, R.id.reconGear3)
        gears.forEachIndexed { index, id ->
            view.findViewById<ImageView>(id).visibility =
                if (pet.gearCount >= index + 1) View.VISIBLE else View.GONE
        }

        view.findViewById<TextView>(R.id.reconRibbon).text = activity.getString(
            if (pet.isSpliced) R.string.recon_ribbon_spliced else R.string.recon_ribbon_hatched
        )

        renderAndShare(activity, view, activity.getString(R.string.recon_share_subject_rat, pet.name))
    }

    fun shareStepsMilestone(activity: AppCompatActivity, milestone: Milestone) {
        val view = lightInflater(activity).inflate(R.layout.card_recon_steps, null) as ViewGroup

        view.findViewById<TextView>(R.id.reconStepsNumber).text = stepsFormat.format(milestone.target)
        view.findViewById<TextView>(R.id.reconStepsKm).text = activity.getString(
            R.string.recon_steps_km, Milestones.kilometresFor(milestone.target)
        )
        view.findViewById<GaugeDialView>(R.id.reconGauge).needleFraction = GAUGE_NEEDLE_FRACTION
        view.findViewById<TextView>(R.id.reconRibbon).text = activity.getString(milestone.nameRes)

        renderAndShare(activity, view, activity.getString(R.string.recon_share_subject_milestone))
    }

    /**
     * Measures, lays out and rasterises [view] at the fixed Recon Card size,
     * then fires the share sheet.
     *
     * The EXACTLY spec is what makes this safe to call on a view that was
     * inflated with a null parent: nothing here depends on measuring against
     * a real parent's constraints, only on the dp size the two card layouts
     * already declare on their own root.
     */
    private fun renderAndShare(activity: AppCompatActivity, view: ViewGroup, subject: String) {
        val density = activity.resources.displayMetrics.density
        val widthPx = (CARD_WIDTH_DP * density).toInt()
        val heightPx = (CARD_HEIGHT_DP * density).toInt()

        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, widthPx, heightPx)

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))

        val uri = shareUriFor(activity, bitmap)
        bitmap.recycle()

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooserIntent = Intent.createChooser(sendIntent, activity.getString(R.string.recon_share_chooser_title))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(chooserIntent)
    }

    /**
     * Writes [bitmap] out and returns a Uri the share sheet can read - see
     * this object's own class comment for why the mechanism splits by API
     * level rather than using [FileProvider] everywhere.
     */
    private fun shareUriFor(activity: AppCompatActivity, bitmap: Bitmap): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = activity.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "recon_card_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Rat King Recon")
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore rejected the Recon Card insert")
            resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            return uri
        }

        // Below Q only: one file at a time, cleared on every share rather
        // than left to accumulate, since nothing else ever reads an old
        // export back.
        val dir = File(activity.cacheDir, "recon_cards").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "recon_card_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
    }
}
