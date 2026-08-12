package io.github.jmatts94.ratkingrecon

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
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
 * Nothing is allocated in [draw]. The gear outline is built once here, and the
 * paints are reused; a card being redrawn twenty-five times a second is not a
 * place to be making objects.
 */
class FrameOverlayDrawable(
    private val style: FrameStyle,
    private val tint: Int
) : Drawable() {

    private companion object {
        /** Corner gears. */
        const val GEAR_TEETH = 8
        const val GEAR_RADIUS_DP = 7f
        const val GEAR_INSET_DP = 9f

        /** Steam. Few and faint - it is a frame, not weather. */
        const val PARTICLES_PER_EDGE = 4
        const val PARTICLE_RADIUS_DP = 2.5f
        const val PARTICLE_DRIFT_DP = 6f
    }

    private var density = 1f

    private val gearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = tint
        style = Paint.Style.FILL
    }

    private val steamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = tint
        style = Paint.Style.FILL
    }

    private val gearPath = Path()
    private var gearBuilt = false

    /** Set by the view before this is attached, so dp can become px. */
    fun setDensity(value: Float) {
        density = value
        gearBuilt = false
    }

    private fun dp(value: Float) = value * density

    /**
     * A cog outline, built once.
     *
     * Alternating radii around a circle: a tooth, a gap, a tooth. Cheap to
     * stamp four times per draw once it exists.
     */
    private fun buildGear() {
        gearPath.reset()

        val outer = dp(GEAR_RADIUS_DP)
        val inner = outer * 0.62f
        val steps = GEAR_TEETH * 2
        val step = (2.0 * Math.PI / steps).toFloat()

        for (i in 0 until steps) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = i * step
            val x = (radius * Math.cos(angle.toDouble())).toFloat()
            val y = (radius * Math.sin(angle.toDouble())).toFloat()
            if (i == 0) gearPath.moveTo(x, y) else gearPath.lineTo(x, y)
        }
        gearPath.close()

        gearBuilt = true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return

        when (style) {
            FrameStyle.STATIC -> Unit
            FrameStyle.GEARS -> drawGears(canvas, b)
            FrameStyle.STEAM -> drawSteam(canvas, b)
        }
    }

    /** Four cogs, one per corner, turning together. */
    private fun drawGears(canvas: Canvas, b: Rect) {
        if (!gearBuilt) buildGear()

        val turn = FrameClock.phase() * 360f
        val inset = dp(GEAR_INSET_DP)

        gearPaint.alpha = 170

        // Opposite corners turn opposite ways, the way meshed gears do.
        drawGearAt(canvas, b.left + inset, b.top + inset, turn)
        drawGearAt(canvas, b.right - inset, b.top + inset, -turn)
        drawGearAt(canvas, b.left + inset, b.bottom - inset, -turn)
        drawGearAt(canvas, b.right - inset, b.bottom - inset, turn)
    }

    private fun drawGearAt(canvas: Canvas, cx: Float, cy: Float, degrees: Float) {
        val saved = canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(degrees)
        canvas.drawPath(gearPath, gearPaint)
        canvas.restoreToCount(saved)
    }

    /**
     * Steam rising up both edges.
     *
     * Each particle's height comes from the shared phase plus a fixed offset,
     * so they are spread through the cycle without any state being kept. Alpha
     * fades in at the bottom and out at the top, which is what stops a particle
     * from visibly popping when it wraps.
     */
    private fun drawSteam(canvas: Canvas, b: Rect) {
        val radius = dp(PARTICLE_RADIUS_DP)
        val drift = dp(PARTICLE_DRIFT_DP)
        val inset = dp(6f)
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

                steamPaint.alpha = (fade * 110f).toInt().coerceIn(0, 255)
                canvas.drawCircle(x, y, radius * (0.7f + 0.3f * fade), steamPaint)
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
