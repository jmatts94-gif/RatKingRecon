package io.github.jmatts94.ratkingrecon

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * The one clock every animated frame reads.
 *
 * A single monotonic phase rather than an animator per card. Recycling bounds
 * how many cards exist, but an animator each would still mean one object and
 * one callback per card on screen, all computing the same number.
 *
 * Every card turning in step is a consequence of sharing the clock, and a
 * welcome one: a wall of gears at random offsets reads as noise.
 */
object FrameClock {

    /** One full turn of a gear, in milliseconds. Slow on purpose. */
    private const val PERIOD_MS = 6_000f

    /** Where in the cycle everything is, 0 to 1. */
    fun phase(): Float = (SystemClock.uptimeMillis() % PERIOD_MS.toLong()) / PERIOD_MS
}

/**
 * Draws an animated frame over a Binder card.
 *
 * Reads [FrameClock] at draw time rather than holding a phase of its own, so
 * the ticker only has to invalidate - it never has to walk the cards setting
 * values on them.
 *
 * Nothing is allocated in [draw]. The cog outline and the border path are built
 * once per card, and the dash effects that march the gear track are pre-built
 * one per phase step, because a DashPathEffect cannot have its phase changed
 * after construction and building one per frame would allocate on every tick.
 *
 * [accent] is the frame's own accent rather than its border colour: drawing the
 * moving parts in the border's colour is what made the cogs read as texture and
 * left the steam almost invisible.
 */
class FrameOverlayDrawable(
    private val style: FrameStyle,
    private val accent: Int,
    private val accentAlt: Int = accent,
    /**
     * SCARRED's second wave of damage - the new gear-tooth and sword-nick
     * marks (see GEAR_MOTIF_POSITIONS/SWORD_MOTIF_POSITIONS) breathe between
     * this pair rather than [accent]/[accentAlt], so what the frame already
     * had and what was added since read as two distinct tones. Null for
     * every other style, where there is nothing that reads them.
     */
    private val secondaryAccent: Int? = null,
    private val secondaryAccentAlt: Int? = secondaryAccent,
    /**
     * Whether a soft halo breathes behind whatever [style] already draws -
     * see [drawGlow]. False by default, so every frame that shipped before
     * this existed is unchanged; opted into per-frame via [CardFrame.glow]
     * rather than switched on for a whole style, since a style can be shared
     * by a frame that wants the extra presence and one that does not - see
     * [Frames.IRON_GRIP] and [Frames.CLOCKWORK].
     */
    private val glow: Boolean = false,
    /**
     * The border path's own corner radius and inset from the view's bounds,
     * in dp - see [buildTrack]. Default to [TRACK_CORNER_DP]/[TRACK_INSET_DP],
     * tuned for item_rat_card.xml's small MaterialCardView grid tile
     * (app:cardCornerRadius 14dp, a 3dp stroke). A caller drawing this over a
     * card shaped differently - see [EnlargedRatDialog], whose card carries
     * a 34dp corner and a 5dp stroke - must pass its own matching values, or
     * every style built from [borderPath] traces a corner too tight for the
     * card underneath it and visibly pokes out past the card's own edge.
     */
    private val trackCornerDp: Float = TRACK_CORNER_DP,
    private val trackInsetDp: Float = TRACK_INSET_DP
) : Drawable() {

    companion object {
        /**
         * Builds the overlay for [frame], reading every colour it needs off
         * [frame] itself so a caller never has to know which of its fields
         * matter for which [FrameStyle] - see [RatCardAdapter] and
         * [EnlargedRatDialog], the grid card and the enlarged card, which
         * both show whatever frame the player has equipped and would
         * otherwise each carry their own copy of this lookup, one of them
         * eventually drifting from the other.
         *
         * [trackCornerDp]/[trackInsetDp] default to null, which leaves
         * [FrameOverlayDrawable]'s own grid-tuned defaults in place - only a
         * caller whose card is shaped differently needs to pass its own.
         */
        fun forFrame(
            context: Context,
            frame: CardFrame,
            trackCornerDp: Float? = null,
            trackInsetDp: Float? = null
        ): FrameOverlayDrawable = FrameOverlayDrawable(
            frame.style,
            ContextCompat.getColor(context, frame.accentColorRes),
            ContextCompat.getColor(context, frame.accentAltColorRes),
            // SCARRED's own second wave of marks - see [secondaryAccent] -
            // reads in the same aether blue AETHER_COIL pulses between,
            // regardless of this particular frame's own warm accent pair.
            // RADIANT's third palette stop works the same way, off
            // boiler_glow's hot orange instead - see [radiantColors].
            secondaryAccent = when (frame.style) {
                FrameStyle.SCARRED -> ContextCompat.getColor(context, R.color.aether_deep)
                FrameStyle.RADIANT -> ContextCompat.getColor(context, R.color.boiler_glow)
                else -> null
            },
            secondaryAccentAlt = when (frame.style) {
                FrameStyle.SCARRED -> ContextCompat.getColor(context, R.color.aether_glow)
                FrameStyle.RADIANT -> ContextCompat.getColor(context, R.color.boiler_glow)
                else -> null
            },
            glow = frame.glow,
            trackCornerDp = trackCornerDp ?: TRACK_CORNER_DP,
            trackInsetDp = trackInsetDp ?: TRACK_INSET_DP
        ).apply { setDensity(context.resources.displayMetrics.density) }

        // --- the gear track running the perimeter ---
        private const val TRACK_WIDTH_DP = 3f
        private const val TRACK_TOOTH_DP = 3.5f
        private const val TRACK_GAP_DP = 3.5f
        private const val TRACK_INSET_DP = 3.5f
        private const val TRACK_CORNER_DP = 11f
        private const val TRACK_ALPHA = 210

        /**
         * How many phases of the marching track are pre-built.
         *
         * The track advances one tooth-and-gap per cycle, so this is how many
         * positions that travel is cut into. Twenty-four is past the point the
         * step is visible at this speed, and twenty-four small immutable objects
         * built once per card is cheaper than one per frame forever.
         */
        private const val DASH_STEPS = 24

        // --- steam ---
        private const val PARTICLES_PER_EDGE = 6
        private const val PARTICLE_RADIUS_DP = 3.2f
        private const val PARTICLE_DRIFT_DP = 7f
        private const val PARTICLE_ALPHA = 220
        private const val GLOW_ALPHA = 75
        private const val GLOW_SCALE = 2.2f

        // --- the breathing border ---
        private const val PULSE_WIDTH_DP = 3.5f
        private const val PULSE_HALO_WIDTH_DP = 7f
        private const val PULSE_HALO_ALPHA = 60
        private const val PULSE_MIN_ALPHA = 140
        private const val PULSE_MAX_ALPHA = 255

        /** Breaths per turn of the shared clock, so a pulse lasts three seconds. */
        private const val PULSE_CYCLES = 2f

        // --- the scarred border's glowing cracks ---
        private const val CRACK_WIDTH_DP = 2f
        private const val CRACK_HALO_WIDTH_DP = 5f
        private const val CRACK_HALO_ALPHA = 90
        private const val CRACK_MIN_ALPHA = 90
        private const val CRACK_MAX_ALPHA = 255
        private const val CRACK_JAG_DP = 5f

        /** Breaths per turn of the clock - slower than PULSE, an old wound rather than a heartbeat. */
        private const val CRACK_CYCLES = 1f

        /** Each crack sits this far around the perimeter from the last, 0..1 of the total path length. */
        private val CRACK_POSITIONS = floatArrayOf(0.04f, 0.22f, 0.40f, 0.58f, 0.76f, 0.92f)

        // --- the new gear-tooth and sword-nick marks, in aether blue ---
        //
        // A second wave of damage on top of the original cracks above, sized
        // and jagged the same amount but built from different silhouettes -
        // a squared-off notch for a gear tooth, a single clean diagonal for a
        // sword nick - so the two motifs read as distinct marks rather than
        // more of the same crack repeated. Interleaved between CRACK_POSITIONS
        // rather than sharing a fraction with any of them.
        private val GEAR_MOTIF_POSITIONS = floatArrayOf(0.13f, 0.67f)
        private val SWORD_MOTIF_POSITIONS = floatArrayOf(0.31f, 0.85f)

        // --- the optional glow, behind whatever the style already draws ---
        private const val GLOW_HALO_WIDTH_DP = 9f
        private const val GLOW_MIN_ALPHA = 40
        private const val GLOW_MAX_ALPHA = 150

        /** One slow breath per turn of the shared clock - a presence, not a pulse to compete with the gears. */
        private const val GLOW_CYCLES = 1f

        // --- the lightning strike, cutting across the card rather than
        // tracing its border ---
        private const val LIGHTNING_INSET_DP = 10f

        /** How far the bolt zigzags off the straight diagonal, each vertex. */
        private const val LIGHTNING_JAG_DP = 18f
        private const val LIGHTNING_STEPS = 5

        /** Its own clock, independent of FrameClock - a strike does not share a gear's rhythm. */
        private const val LIGHTNING_PERIOD_MS = 2_600f
        private const val LIGHTNING_FLASH1_START = 0f
        private const val LIGHTNING_FLASH2_START = 0.16f

        /** Both flashes share this width, as a fraction of the whole cycle - brief either way. */
        private const val LIGHTNING_FLASH_DURATION = 0.10f
        private const val LIGHTNING_GLOW_WIDTH_DP = 11f
        private const val LIGHTNING_CORE_WIDTH_DP = 2.5f
        private const val LIGHTNING_GLOW_ALPHA = 210

        // --- the radiant border, cycling a full palette rather than
        // breathing between two ---
        private const val RADIANT_HALO_WIDTH_DP = 10f
        private const val RADIANT_HALO_ALPHA = 140
        private const val RADIANT_CORE_WIDTH_DP = 4f

        /** Full loops of the palette per turn of the shared clock. */
        private const val RADIANT_CYCLES = 1f

        // --- the paw print track: a fleshy silhouette, not a skeletal one.
        // Four solid rounded toes over a wide palm pad, single flat fill,
        // no outline - see drawPawShapes.

        /** How many footprints make up the fading trail, head to tail. */
        private const val PAW_TRAIL_COUNT = 4

        /**
         * How far apart each footstep sits, as a fraction of the whole
         * perimeter. A real step, not a continuously sliding blob: each
         * trailing mark now sits at one of a fixed set of step positions
         * along the border (see [drawPaws]) rather than interpolating
         * smoothly between them, which is what makes the trail read as
         * footprints someone left rather than a mark being dragged along.
         */
        private const val PAW_STEP_SPACING = 0.05f

        /**
         * Its own clock, independent of [FrameClock] - the same reasoning
         * [LIGHTNING_PERIOD_MS] already gives for a strike not sharing a
         * gear's rhythm. One full lap of the border takes this long.
         *
         * An earlier pass tried this as a multiplier on [FrameClock.phase()]
         * instead (a fraction below 1, for "slower than GEARS' own lap").
         * That was the actual bug behind "the paw prints only go up half
         * the tile" - phase() is already a sawtooth capped at 1 every
         * PERIOD_MS, so multiplying it by a fraction just shrinks the range
         * it ever reaches rather than slowing how long a full 0..1 sweep
         * takes. A real independent period, the way LIGHTNING already has
         * one, is what actually walks the whole perimeter.
         */
        private const val PAW_PERIOD_MS = 17_000f

        /**
         * The track's own minimum distance inward from the border line -
         * see [drawPaws]. Never zero: "limit their path to within the
         * confines of the tile, so they do not move past the black
         * outline" means the wander has to stay inward of the line at
         * every point, not swing on either side of it, and this is what
         * keeps even a print's own outline from poking past the border.
         */
        private const val PAW_INSET_MIN_DP = 9f

        /** How much further inward, on top of [PAW_INSET_MIN_DP], the wander swings at its peak. */
        private const val PAW_WANDER_DP = 10f

        /** Wobbles per full lap - enough for the track to read as wandering, not a straight march with a shiver on it. */
        private const val PAW_WANDER_CYCLES = 5f

        /**
         * The extra sideways kick, on top of the wander above, that
         * alternates a left footprint to one side of the line the walk is
         * following and a right one to the other - "alternate left/right
         * paw prints along a walking path."
         */
        private const val PAW_STEP_SIDE_DP = 3f

        /** A small, fixed, per-step rotation added on top of the direction of travel - a real footprint never lands perfectly square. */
        private const val PAW_ROTATION_JITTER_DEG = 7f

        /**
         * Each toe's own length and width - "a solid rounded oval/teardrop
         * (fleshy blob)... roughly 2x as tall as it's wide," drawn as a
         * plain [android.graphics.Canvas.drawOval] rather than a tapered
         * Path the way an earlier "finger" pass did - no claw tip, no
         * taper, just a fat oval.
         */
        private const val PAW_TOE_LENGTH_DP = 10f
        private const val PAW_TOE_WIDTH_DP = 5f

        /**
         * How far a toe's own near edge is embedded back into the palm -
         * see [drawPawShapes]. Deliberately less than half of both
         * [PAW_PALM_DEEP_DP] and [PAW_PALM_WIDE_DP], so every toe's base
         * sits inside the palm's own silhouette regardless of its angle -
         * "no negative space between toe and palm."
         */
        private const val PAW_TOE_EMBED_DP = 2f

        /**
         * The palm pad's own width (sideways, across the direction of
         * travel) and depth (fore-aft) - "a wide rounded palm pad... a
         * single blobby rounded shape... not a thin base or gap." Wider
         * than it is deep, the same reason a real palm reads as a pad
         * rather than a heel.
         */
        private const val PAW_PALM_WIDE_DP = 10f
        private const val PAW_PALM_DEEP_DP = 6f

        /** The scale a freshly-placed print eases up from, and settles at once fully arrived - see [drawPaws]. */
        private const val PAW_SCALE_MIN = 0.7f
        private const val PAW_SCALE_MAX = 1f

        /**
         * How far a print's own alpha dips by the time it reaches the back
         * of the trail, as a fraction of full opacity - "fading in and
         * slightly out," not fading all the way to nothing before it
         * scrolls out of the trail entirely.
         */
        private const val PAW_SETTLE_FADE_FRACTION = 0.35f

        /**
         * The extra scale multiplier alternating steps carry, on top of
         * the fade-in scale above - "alternating scale slightly between
         * left/right prints to mimic natural gait offset."
         */
        private const val PAW_SIDE_SCALE_LEFT = 0.85f
        private const val PAW_SIDE_SCALE_RIGHT = 1.05f

        /**
         * Four toes, symmetric this time rather than a rat's own
         * asymmetric splay an earlier pass drew - "two center toes
         * pointing mostly forward, two outer toes angled outward left/
         * right at roughly 30-45deg." A right footprint mirrors this same
         * list across the direction of travel (see [drawPawPrint]) instead
         * of a second list of its own, since a right paw is exactly a left
         * one flipped, not a differently shaped one.
         */
        private val PAW_TOES = listOf(
            PawToe(angleDeg = -38f, lengthScale = 0.90f, widthScale = 0.95f),
            PawToe(angleDeg = -9f, lengthScale = 1f, widthScale = 1f),
            PawToe(angleDeg = 9f, lengthScale = 1f, widthScale = 1f),
            PawToe(angleDeg = 38f, lengthScale = 0.90f, widthScale = 0.95f)
        )
    }

    /** One entry in [PAW_TOES] - see that field's own comment. */
    private data class PawToe(val angleDeg: Float, val lengthScale: Float, val widthScale: Float)

    private var density = 1f

    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // Rounded rather than the default butt cap: PULSE draws a closed loop
        // where this never shows, but SCARRED's cracks are open zigzags and a
        // hard square end on a crack line reads as a drawing error.
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val steamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.FILL
    }

    private val borderPath = Path()
    private val borderRect = RectF()

    private var dashEffects: Array<DashPathEffect>? = null
    private var crackPaths: List<Path> = emptyList()
    private var blueMotifPaths: List<Path> = emptyList()
    private var lightningPath: Path = Path()
    private var built = false

    /**
     * PAWS' own walk along [borderPath] - reused via [PathMeasure.setPath]
     * rather than a fresh [PathMeasure] built per draw, the same
     * nothing-allocated-in-draw rule the dash effects and crack paths
     * already follow.
     */
    private val pawsMeasure = PathMeasure()
    private var pawsLength = 0f

    /** RADIANT's own palette - accent, accentAlt, and a third stop off secondaryAccent. */
    private val radiantColors: IntArray by lazy { intArrayOf(accent, accentAlt, secondaryAccent ?: accent) }

    /** Set by the view before this is attached, so dp can become px. */
    fun setDensity(value: Float) {
        density = value
        built = false
    }

    private fun dp(value: Float) = value * density

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        built = false
    }

    /**
     * Builds the border path and the marching dash phases.
     *
     * Both depend on density, and the border on bounds, so this is redone when
     * either changes and never during an ordinary draw.
     */
    private fun build() {
        buildTrack()
        if (style == FrameStyle.SCARRED) buildCracks()
        if (style == FrameStyle.LIGHTNING) buildLightning()
        if (style == FrameStyle.PAWS) buildPaws()
        built = true
    }

    /**
     * The perimeter track, as a rounded rectangle stroked with a dashed effect.
     *
     * One path and one stroke rather than a tooth per notch: the dash does the
     * repetition, and animating its phase is what makes the teeth travel. The
     * corners follow the card's own radius, so the track reads as part of the
     * frame rather than a rectangle laid over a rounded card.
     */
    private fun buildTrack() {
        val b = bounds
        if (b.isEmpty) return

        val inset = dp(trackInsetDp)
        val width = dp(TRACK_WIDTH_DP)
        trackPaint.strokeWidth = width

        borderRect.set(
            b.left + inset,
            b.top + inset,
            b.right - inset,
            b.bottom - inset
        )

        borderPath.reset()
        borderPath.addRoundRect(
            borderRect,
            dp(trackCornerDp),
            dp(trackCornerDp),
            Path.Direction.CW
        )

        val tooth = dp(TRACK_TOOTH_DP)
        val gap = dp(TRACK_GAP_DP)
        val span = tooth + gap
        val intervals = floatArrayOf(tooth, gap)

        dashEffects = Array(DASH_STEPS) { step ->
            DashPathEffect(intervals, span * step / DASH_STEPS)
        }
    }

    /**
     * Six short jagged lines, anchored at fixed points around [borderPath]'s
     * own perimeter via [PathMeasure] rather than at coordinates worked out by
     * hand - the border's rounded corners already vary with [TRACK_CORNER_DP]
     * and density, and walking the path itself is what keeps a crack sitting
     * on the edge instead of drifting off it.
     *
     * Fixed positions rather than random ones, the same reason the gear track
     * above is built from [DASH_STEPS] rather than rolled fresh: a crack that
     * moved to a new spot every time this Drawable rebuilt would read as
     * damage relocating itself, not as a mark the card already carries.
     */
    private fun buildCracks() {
        val measure = PathMeasure(borderPath, false)
        val total = measure.length
        if (total <= 0f) return

        val jag = dp(CRACK_JAG_DP)

        crackPaths = CRACK_POSITIONS.map { fraction -> crackAt(measure, total, fraction, jag) }
        blueMotifPaths = GEAR_MOTIF_POSITIONS.map { fraction -> gearToothAt(measure, total, fraction, jag) } +
            SWORD_MOTIF_POSITIONS.map { fraction -> swordNickAt(measure, total, fraction, jag) }
    }

    /** The border's position and inward-pointing normal at [fraction] of its length. */
    private class BorderPoint(val x: Float, val y: Float, val tx: Float, val ty: Float, val nx: Float, val ny: Float)

    private fun pointAt(measure: PathMeasure, total: Float, fraction: Float): BorderPoint {
        val pos = FloatArray(2)
        val tan = FloatArray(2)
        measure.getPosTan(total * fraction, pos, tan)
        // The inward normal - rotate the tangent 90°. borderPath winds
        // clockwise, so this points into the card rather than out past it.
        return BorderPoint(pos[0], pos[1], tan[0], tan[1], -tan[1], tan[0])
    }

    /** The original jagged zigzag - four points, unchanged shape. */
    private fun crackAt(measure: PathMeasure, total: Float, fraction: Float, jag: Float): Path {
        val p = pointAt(measure, total, fraction)
        return Path().apply {
            moveTo(p.x - p.tx * jag, p.y - p.ty * jag)
            lineTo(p.x + p.nx * jag, p.y + p.ny * jag)
            lineTo(p.x + p.tx * jag * 0.6f, p.y + p.ty * jag * 0.6f)
            lineTo(p.x + p.nx * jag * 1.8f, p.y + p.ny * jag * 1.8f)
        }
    }

    /**
     * A single squared-off notch - a gear tooth's silhouette rather than the
     * crack's own zigzag, so the two new-colour marks are told apart by shape
     * as well as position.
     */
    private fun gearToothAt(measure: PathMeasure, total: Float, fraction: Float, jag: Float): Path {
        val p = pointAt(measure, total, fraction)
        return Path().apply {
            moveTo(p.x - p.tx * jag, p.y - p.ty * jag)
            lineTo(p.x - p.tx * jag + p.nx * jag * 1.4f, p.y - p.ty * jag + p.ny * jag * 1.4f)
            lineTo(p.x + p.tx * jag + p.nx * jag * 1.4f, p.y + p.ty * jag + p.ny * jag * 1.4f)
            lineTo(p.x + p.tx * jag, p.y + p.ty * jag)
        }
    }

    /** A single clean diagonal - a blade's nick, plainer than either of the above. */
    private fun swordNickAt(measure: PathMeasure, total: Float, fraction: Float, jag: Float): Path {
        val p = pointAt(measure, total, fraction)
        return Path().apply {
            moveTo(p.x - p.tx * jag * 1.2f, p.y - p.ty * jag * 1.2f)
            lineTo(p.x + p.nx * jag * 1.6f, p.y + p.ny * jag * 1.6f)
        }
    }

    /**
     * A jagged bolt from the top-right corner to the bottom-left, built once
     * against [bounds] the same way [borderPath] is - a zigzag rather than a
     * ruled diagonal, offset alternately left and right of the straight line
     * between the two corners so it reads as a strike rather than a scratch.
     */
    private fun buildLightning() {
        val b = bounds
        if (b.isEmpty) return

        val inset = dp(LIGHTNING_INSET_DP)
        val startX = b.right - inset
        val startY = b.top + inset
        val endX = b.left + inset
        val endY = b.bottom - inset
        val jag = dp(LIGHTNING_JAG_DP)

        // The direction perpendicular to the straight diagonal, normalised -
        // each vertex steps along the diagonal and out along this, alternating
        // sides, rather than off the diagonal's own direction.
        val dx = endX - startX
        val dy = endY - startY
        val length = kotlin.math.hypot(dx, dy).let { if (it == 0f) 1f else it }
        val perpX = -dy / length
        val perpY = dx / length

        lightningPath = Path().apply {
            moveTo(startX, startY)
            for (i in 1 until LIGHTNING_STEPS) {
                val t = i / LIGHTNING_STEPS.toFloat()
                val baseX = startX + dx * t
                val baseY = startY + dy * t
                val sign = if (i % 2 == 0) 1f else -1f
                lineTo(baseX + perpX * jag * sign, baseY + perpY * jag * sign)
            }
            lineTo(endX, endY)
        }
    }

    /** Points [pawsMeasure] at the current [borderPath] and caches its length. */
    private fun buildPaws() {
        pawsMeasure.setPath(borderPath, false)
        pawsLength = pawsMeasure.length
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        if (!built) build()

        if (glow) drawGlow(canvas)

        when (style) {
            FrameStyle.STATIC -> Unit
            FrameStyle.GEARS -> drawClockwork(canvas)
            FrameStyle.STEAM -> drawSteam(canvas, b)
            FrameStyle.PULSE -> drawPulse(canvas)
            FrameStyle.SCARRED -> drawScarred(canvas)
            FrameStyle.LIGHTNING -> drawLightning(canvas)
            FrameStyle.RADIANT -> drawRadiant(canvas)
            FrameStyle.PAWS -> drawPaws(canvas)
        }
    }

    /**
     * A soft halo breathing behind whatever [style] draws on top of it - see
     * [CardFrame.glow]. Deliberately translucent even at its brightest
     * ([GLOW_MAX_ALPHA] well short of 255): this sits behind a crisp track or
     * border that has to stay readable, not compete with it.
     */
    private fun drawGlow(canvas: Canvas) {
        val wave = (sin((FrameClock.phase() * GLOW_CYCLES * 2f * Math.PI).toFloat()) + 1f) / 2f

        pulsePaint.color = accent
        pulsePaint.strokeWidth = dp(GLOW_HALO_WIDTH_DP)
        pulsePaint.alpha = (GLOW_MIN_ALPHA + (GLOW_MAX_ALPHA - GLOW_MIN_ALPHA) * wave).toInt().coerceIn(0, 255)
        canvas.drawPath(borderPath, pulsePaint)
    }

    /**
     * The strike itself: dark and still most of the cycle, then two brief
     * flashes in quick succession - see [flashIntensity] - before going
     * quiet again. Runs on its own clock rather than [FrameClock]'s shared
     * phase, because a strike's rhythm has nothing to do with a gear's.
     *
     * The same halo-then-core trick every other animated frame uses, just
     * gated by [intensity] rather than always drawn: a flat single-width
     * line reads as a wire sitting on the card, not as lightning crossing it.
     */
    private fun drawLightning(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() % LIGHTNING_PERIOD_MS.toLong()) / LIGHTNING_PERIOD_MS
        val intensity = max(
            flashIntensity(t, LIGHTNING_FLASH1_START),
            flashIntensity(t, LIGHTNING_FLASH2_START)
        )
        if (intensity <= 0f) return

        pulsePaint.color = accent
        pulsePaint.strokeWidth = dp(LIGHTNING_GLOW_WIDTH_DP)
        pulsePaint.alpha = (LIGHTNING_GLOW_ALPHA * intensity).toInt().coerceIn(0, 255)
        canvas.drawPath(lightningPath, pulsePaint)

        pulsePaint.color = accentAlt
        pulsePaint.strokeWidth = dp(LIGHTNING_CORE_WIDTH_DP)
        pulsePaint.alpha = (255 * intensity).toInt().coerceIn(0, 255)
        canvas.drawPath(lightningPath, pulsePaint)
    }

    /**
     * 0 outside [start]..[start]+[LIGHTNING_FLASH_DURATION] of the cycle,
     * and inside it a fast rise to full brightness followed by a faster
     * decay back to nothing - a strike, not a fade in either direction.
     */
    private fun flashIntensity(t: Float, start: Float): Float {
        val local = (t - start) / LIGHTNING_FLASH_DURATION
        if (local < 0f || local > 1f) return 0f
        return if (local < 0.2f) local / 0.2f else 1f - (local - 0.2f) / 0.8f
    }

    /**
     * A border cycling through [radiantColors] rather than breathing between
     * two - see [FrameStyle.RADIANT]. The core never drops below full alpha,
     * unlike [drawPulse]'s own breath: the colour keeps moving, but the
     * border itself never dims, which is the difference this style is built
     * to read as against a frame merely pulsing.
     */
    private fun drawRadiant(canvas: Canvas) {
        val colors = radiantColors
        val cyclePos = (FrameClock.phase() * RADIANT_CYCLES * colors.size) % colors.size
        val index = cyclePos.toInt().coerceIn(0, colors.size - 1)
        val next = (index + 1) % colors.size
        val color = ColorUtils.blendARGB(colors[index], colors[next], cyclePos - index)

        pulsePaint.color = color
        pulsePaint.strokeWidth = dp(RADIANT_HALO_WIDTH_DP)
        pulsePaint.alpha = RADIANT_HALO_ALPHA
        canvas.drawPath(borderPath, pulsePaint)

        pulsePaint.strokeWidth = dp(RADIANT_CORE_WIDTH_DP)
        pulsePaint.alpha = 255
        canvas.drawPath(borderPath, pulsePaint)
    }

    /**
     * A track of rat footprints padding round [borderPath] - see
     * [FrameStyle.PAWS]. Quantised to fixed step positions
     * ([PAW_STEP_SPACING] apart) rather than interpolated smoothly between
     * them, which is what makes this read as footprints left behind rather
     * than a mark being dragged along - a real print does not slide once
     * it lands. Each step's own [BorderPoint.nx]/[ny] (the inward-normal
     * axis [pointAt] already gives every other style) carries two separate
     * offsets: a slow wander that is a function of position along the
     * path, never past [PAW_INSET_MIN_DP] inward of the line, and a fixed
     * [PAW_STEP_SIDE_DP] kick that alternates side with the step's own
     * parity - "alternate left/right paw prints." Each step's own alpha
     * and scale ease smoothly through its whole visible life - in as it is
     * freshly placed, gently out again as it ages toward the back of the
     * trail - rather than jumping between fixed per-index levels; see
     * [easeOutCubic]/[easeInCubic].
     */
    private fun drawPaws(canvas: Canvas) {
        val total = pawsLength
        if (total <= 0f) return

        val headFraction = (SystemClock.uptimeMillis() % PAW_PERIOD_MS.toLong()) / PAW_PERIOD_MS
        val stepFloat = headFraction / PAW_STEP_SPACING
        val headStep = floor(stepFloat).toInt()

        for (i in 0 until PAW_TRAIL_COUNT) {
            val stepIndex = headStep - i
            var fraction = stepIndex * PAW_STEP_SPACING
            fraction -= floor(fraction)

            val p = pointAt(pawsMeasure, total, fraction)
            val wave = (sin((fraction * PAW_WANDER_CYCLES * 2f * Math.PI).toFloat()) + 1f) / 2f
            val wander = dp(PAW_INSET_MIN_DP) + wave * dp(PAW_WANDER_DP)

            // Even/odd step, folded into 0/1 regardless of sign - a
            // negative stepIndex (the walk has not yet completed its first
            // lap since the process started) must alternate exactly the
            // same way a positive one does, or the gait reads as broken
            // right at the start of every lap.
            val isRight = Math.floorMod(stepIndex, 2) == 1
            val sideSign = if (isRight) 1f else -1f
            val lateral = wander + sideSign * dp(PAW_STEP_SIDE_DP)
            val cx = p.x + p.nx * lateral
            val cy = p.y + p.ny * lateral

            // How long this exact step has been alive, in step-lengths - 0
            // the instant it is placed, climbing to PAW_TRAIL_COUNT-ish by
            // the time it is the oldest one still shown. Continuous rather
            // than per-index, which is what lets every step ease smoothly
            // through its own fade/scale envelope instead of snapping
            // between PAW_TRAIL_COUNT fixed alpha rungs - "do not use
            // discrete/step-based opacity jumps... use a proper easing
            // curve."
            val ageInSteps = stepFloat - stepIndex

            // Fading (and scaling) IN: eased across the step's first full
            // step-length of life, "fade in while scaling up slightly...
            // not just pop into visibility."
            val fadeIn = easeOutCubic(ageInSteps.coerceIn(0f, 1f))

            // Fading (slightly) OUT: once fully arrived, a gentle eased dip
            // the rest of the way through the trail's own visible span -
            // never fully to zero before the step scrolls out of the trail
            // entirely, which is the "slightly" in "fading in and slightly
            // out."
            val settleT = ((ageInSteps - 1f) / (PAW_TRAIL_COUNT - 1f).coerceAtLeast(1f)).coerceIn(0f, 1f)
            val settleFade = 1f - PAW_SETTLE_FADE_FRACTION * easeInCubic(settleT)

            val alpha = (255 * fadeIn * settleFade).toInt().coerceIn(0, 255)
            steamPaint.alpha = alpha

            val gaitScale = if (isRight) PAW_SIDE_SCALE_RIGHT else PAW_SIDE_SCALE_LEFT
            val scale = (PAW_SCALE_MIN + (PAW_SCALE_MAX - PAW_SCALE_MIN) * fadeIn) * gaitScale

            // Facing the direction of travel, the same as every style that
            // reads p.tx/p.ty already does, plus a small fixed jitter per
            // step - a deterministic pseudo-random offset off stepIndex
            // (sin of a large irrational-ish multiplier, the cheapest
            // decorrelated-looking sequence available with no state and no
            // allocation) rather than a truly random one, since a jitter
            // that changed on every recomposition would read as the print
            // trembling in place instead of having simply landed crooked.
            val baseAngle = Math.toDegrees(atan2(p.ty.toDouble(), p.tx.toDouble())).toFloat()
            val jitter = sin(stepIndex * 12.9898f) * PAW_ROTATION_JITTER_DEG
            drawPawPrint(canvas, cx, cy, baseAngle + jitter, mirror = isRight, scale = scale)
        }
    }

    /**
     * A fast-then-slow ease, standing in for the CSS cubic-bezier(0.22,
     * 0.61, 0.36, 1) this style was asked to move like: a plain
     * ease-out-cubic reads the same way (quick start, gentle finish)
     * without needing an iterative bezier solve for what is, on screen, an
     * imperceptible difference in curve shape.
     */
    private fun easeOutCubic(t: Float): Float {
        val inv = 1f - t
        return 1f - inv * inv * inv
    }

    /** The mirror of [easeOutCubic] - slow start, fast finish - for the gentler back half of [drawPaws]'s own fade. */
    private fun easeInCubic(t: Float): Float = t * t * t

    /**
     * One footprint: a wide palm oval, plus [PAW_TOES], each toe its own
     * plain oval placed and rotated off its own entry in that list -
     * "solid, chunky silhouette... single flat fill colour, no outlines."
     * [mirror] flips the whole splay across the direction-of-travel axis
     * via a y-scale of -1 rather than a second, hand-mirrored copy of
     * [PAW_TOES] - a right paw is exactly a left one flipped, and
     * canvas.scale(1f, -1f) says that directly instead of restating it as
     * data. [scale] carries both the fade-in grow and the left/right gait
     * alternation from [drawPaws] - see that function's own comment.
     */
    private fun drawPawPrint(canvas: Canvas, cx: Float, cy: Float, angleDeg: Float, mirror: Boolean, scale: Float) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(angleDeg)
        if (mirror) canvas.scale(1f, -1f)

        drawPawShapes(canvas, scale)

        canvas.restore()
    }

    /**
     * The palm and all four toes - see [drawPawPrint]. Assumes the canvas
     * is already at the print's own origin, facing forward (local +x).
     * Every toe's near edge is embedded [PAW_TOE_EMBED_DP] back into the
     * palm rather than starting at its edge, so the two read as one
     * connected blob - "no negative space between toe and palm."
     */
    private fun drawPawShapes(canvas: Canvas, scale: Float) {
        val palmWide = dp(PAW_PALM_WIDE_DP) * scale
        val palmDeep = dp(PAW_PALM_DEEP_DP) * scale
        canvas.drawOval(-palmDeep / 2f, -palmWide / 2f, palmDeep / 2f, palmWide / 2f, steamPaint)

        val toeLen = dp(PAW_TOE_LENGTH_DP) * scale
        val toeWidth = dp(PAW_TOE_WIDTH_DP) * scale
        val embed = dp(PAW_TOE_EMBED_DP) * scale
        for (toe in PAW_TOES) {
            canvas.save()
            canvas.rotate(toe.angleDeg)
            val length = toeLen * toe.lengthScale
            val width = toeWidth * toe.widthScale
            canvas.drawOval(-embed, -width / 2f, length - embed, width / 2f, steamPaint)
            canvas.restore()
        }
    }

    /**
     * A toothed track travelling round the edge.
     *
     * Cogs used to sit at the four corners on top of this. They were dropped
     * because they covered the card's own markers - the Battle Rat badge sits
     * at the end of the name, exactly under the top-right one - and a frame
     * that hides what the card is telling you is worse than a plainer frame.
     */
    private fun drawClockwork(canvas: Canvas) {
        val effects = dashEffects ?: return

        // Counted backwards so the teeth travel clockwise.
        val phase = FrameClock.phase()
        val index = ((1f - phase) * DASH_STEPS).toInt().coerceIn(0, DASH_STEPS - 1)

        trackPaint.pathEffect = effects[index]
        trackPaint.alpha = TRACK_ALPHA
        canvas.drawPath(borderPath, trackPaint)
    }

    /**
     * A border breathing between two colours.
     *
     * Brass and Ember were both a single warm border and read as almost the
     * same frame at a glance. This one moves instead of being another shade:
     * the colour travels from hot orange to deep red and back, with the glow
     * dimming as it cools, which separates the two without either of them
     * leaving the palette.
     *
     * A wide faint halo under a solid core, the same two-pass trick the steam
     * uses, because a flat stroke reads as a border rather than as a glow.
     */
    private fun drawPulse(canvas: Canvas) {
        // Sine so the turn at each end is gradual: a linear ramp reads as a
        // flicker at the top and bottom of the breath.
        val wave = (sin((FrameClock.phase() * PULSE_CYCLES * 2f * Math.PI).toFloat()) + 1f) / 2f
        val color = ColorUtils.blendARGB(accentAlt, accent, wave)

        pulsePaint.color = color
        pulsePaint.strokeWidth = dp(PULSE_HALO_WIDTH_DP)
        pulsePaint.alpha = (PULSE_HALO_ALPHA * wave).toInt().coerceIn(0, 255)
        canvas.drawPath(borderPath, pulsePaint)

        pulsePaint.color = color
        pulsePaint.strokeWidth = dp(PULSE_WIDTH_DP)
        pulsePaint.alpha =
            (PULSE_MIN_ALPHA + (PULSE_MAX_ALPHA - PULSE_MIN_ALPHA) * wave).toInt().coerceIn(0, 255)
        canvas.drawPath(borderPath, pulsePaint)
    }

    /**
     * A handful of cracks along the border, breathing light rather than
     * marching or drifting - damage on a frame this hard-earned should read
     * as old and still faintly hot, not as machinery still running.
     *
     * The same two-pass halo-then-core trick [drawPulse] uses, and the same
     * sine easing, just slower ([CRACK_CYCLES] against [PULSE_CYCLES]) and
     * never fully dark - even at the dim end of the breath a scar stays
     * visible, which is the difference between damage and a light switching
     * off.
     */
    private fun drawScarred(canvas: Canvas) {
        val wave = (sin((FrameClock.phase() * CRACK_CYCLES * 2f * Math.PI).toFloat()) + 1f) / 2f
        val color = ColorUtils.blendARGB(accentAlt, accent, wave)

        for (crack in crackPaths) {
            pulsePaint.color = color
            pulsePaint.strokeWidth = dp(CRACK_HALO_WIDTH_DP)
            pulsePaint.alpha = (CRACK_HALO_ALPHA * wave).toInt().coerceIn(0, 255)
            canvas.drawPath(crack, pulsePaint)

            pulsePaint.strokeWidth = dp(CRACK_WIDTH_DP)
            pulsePaint.alpha =
                (CRACK_MIN_ALPHA + (CRACK_MAX_ALPHA - CRACK_MIN_ALPHA) * wave).toInt().coerceIn(0, 255)
            canvas.drawPath(crack, pulsePaint)
        }

        // The second wave, breathing in lockstep with the cracks above but in
        // its own colour - see secondaryAccent. Skipped entirely when none was
        // supplied, which every style but SCARRED leaves null.
        val blueAccent = secondaryAccent ?: return
        val blueAccentAlt = secondaryAccentAlt ?: blueAccent
        val blueColor = ColorUtils.blendARGB(blueAccentAlt, blueAccent, wave)

        for (motif in blueMotifPaths) {
            pulsePaint.color = blueColor
            pulsePaint.strokeWidth = dp(CRACK_HALO_WIDTH_DP)
            pulsePaint.alpha = (CRACK_HALO_ALPHA * wave).toInt().coerceIn(0, 255)
            canvas.drawPath(motif, pulsePaint)

            pulsePaint.strokeWidth = dp(CRACK_WIDTH_DP)
            pulsePaint.alpha =
                (CRACK_MIN_ALPHA + (CRACK_MAX_ALPHA - CRACK_MIN_ALPHA) * wave).toInt().coerceIn(0, 255)
            canvas.drawPath(motif, pulsePaint)
        }
    }

    /**
     * Steam rising up both edges.
     *
     * Each particle's height comes from the shared phase plus a fixed offset,
     * so they are spread through the cycle without any state being kept. Alpha
     * fades in at the bottom and out at the top, which is what stops a particle
     * from visibly popping when it wraps.
     *
     * Two circles per particle: a wide faint one for the glow and a smaller
     * solid one for the wisp itself. A single flat circle read as a dot rather
     * than as something hot.
     */
    private fun drawSteam(canvas: Canvas, b: Rect) {
        val radius = dp(PARTICLE_RADIUS_DP)
        val drift = dp(PARTICLE_DRIFT_DP)
        val inset = dp(7f)
        val phase = FrameClock.phase()
        val height = b.height().toFloat()

        for (edge in 0..1) {
            val baseX = if (edge == 0) b.left + inset else b.right - inset

            for (i in 0 until PARTICLES_PER_EDGE) {
                // Offset per particle, and a half-cycle between the two edges,
                // so the sides do not rise in lockstep.
                val offset = i / PARTICLES_PER_EDGE.toFloat() + edge * 0.5f
                val t = (phase + offset) % 1f

                val y = b.bottom - t * height
                val sway = sin((t * 2f * Math.PI).toFloat()) * drift
                val x = baseX + if (edge == 0) sway else -sway

                // Fade in over the first fifth, out over the last third.
                val fade = when {
                    t < 0.2f -> t / 0.2f
                    t > 0.67f -> (1f - t) / 0.33f
                    else -> 1f
                }

                val size = radius * (0.7f + 0.3f * fade)

                steamPaint.alpha = (fade * GLOW_ALPHA).toInt().coerceIn(0, 255)
                canvas.drawCircle(x, y, size * GLOW_SCALE, steamPaint)

                steamPaint.alpha = (fade * PARTICLE_ALPHA).toInt().coerceIn(0, 255)
                canvas.drawCircle(x, y, size, steamPaint)
            }
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Required by Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Drives every animated frame on a screen from one Choreographer callback.
 *
 * One ticker rather than an animator per card, and it invalidates only the
 * cards currently attached to the window. Since the Binder became a
 * RecyclerView that set is roughly a screenful however large the collection
 * grows, and RecyclerView reports it exactly - a card is attached or it is not,
 * so nothing here has to measure anything against the viewport.
 *
 * It also runs at [FRAME_MS] rather than at display rate. A gear taking six
 * seconds to turn does not need ninety frames a second to look smooth, and the
 * difference is most of the battery this could otherwise spend.
 */
class FrameAnimator {

    private companion object {
        /** ~25fps. Ample for movement this slow. */
        const val FRAME_MS = 40L
    }

    /** The card overlays currently on screen. */
    private val attached = mutableListOf<View>()

    private var running = false
    private var lastFrameAt = 0L

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return

            val now = SystemClock.uptimeMillis()
            if (now - lastFrameAt >= FRAME_MS) {
                lastFrameAt = now
                for (i in attached.indices) attached[i].invalidate()
            }

            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Called when a card scrolls into view. */
    fun attach(overlay: View) {
        if (!attached.contains(overlay)) attached += overlay
        if (running) return
        start()
    }

    /** Called when a card scrolls off, or its holder is recycled. */
    fun detach(overlay: View) {
        attached -= overlay
    }

    /** Drops everything, for a screen going away. */
    fun clear() {
        attached.clear()
    }

    fun start() {
        if (running || attached.isEmpty()) return
        running = true
        lastFrameAt = 0L
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(callback)
    }
}
