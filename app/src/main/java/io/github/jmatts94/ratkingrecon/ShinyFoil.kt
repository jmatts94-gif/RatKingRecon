package io.github.jmatts94.ratkingrecon

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The "slight foil effect" a shiny rat's card gets that an ordinary rat's
 * does not - see RatCardAdapter.onBindViewHolder and EnlargedRatDialog.kt.
 * Replaces an earlier static two-gradient drawable with one that actually
 * moves, the way tilting a real foil card in the light does.
 *
 * Two crossed diagonal bands - warm shiny_gold, cool aether_glow, the same
 * pair the static version used - each a narrow transparent-colour-transparent
 * streak riding a [LinearGradient] wider than the card itself, translated
 * along its own axis by [sin] of the shared [FrameClock]'s phase. Sine rather
 * than the phase directly is what makes this a back-and-forth rock instead of
 * a one-way sweep looping round: [FrameClock.phase] is a sawtooth that would
 * otherwise snap the band from one edge straight back to the other every
 * cycle, which reads as a glitch rather than a card being turned in the hand.
 *
 * Reads [FrameClock] at draw time and does no per-frame allocation, the same
 * two rules [FrameOverlayDrawable] follows - see that class's own doc comment
 * - and is driven by the very same [FrameAnimator] a card's equipped Binder
 * frame already uses, so a shiny rat wearing an animated frame ticks in step
 * with it rather than two independent clocks drifting against each other.
 */
class ShinyFoilDrawable(
    private val warm: Int,
    private val cool: Int
) : Drawable() {

    companion object {
        /** How wide each band's bright core is, as a fraction of its own gradient span. */
        private const val BAND_WIDTH_FRACTION = 0.22f

        /** Peak alpha at the centre of each band - "slight," matched to the static version this replaced. */
        private const val WARM_ALPHA = 0x4D
        private const val COOL_ALPHA = 0x40

        /** Full rocks per turn of the shared clock - slow, a card being tilted rather than shaken. */
        private const val SWEEP_CYCLES = 1f

        /** The gradient's own span against the card's diagonal - longer, so a band can travel fully off one edge before turning back rather than clipping mid-travel. */
        private const val SPAN_MULTIPLIER = 1.4f

        fun create(context: Context): ShinyFoilDrawable = ShinyFoilDrawable(
            ContextCompat.getColor(context, R.color.shiny_gold),
            ContextCompat.getColor(context, R.color.aether_glow)
        )
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val matrix = Matrix()

    private var warmShader: LinearGradient? = null
    private var coolShader: LinearGradient? = null

    /** Half the gradient's own span - how far a band's centre can travel off the card's own centre. */
    private var travel = 0f

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds.isEmpty) return

        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val span = hypot(bounds.width().toFloat(), bounds.height().toFloat()) * SPAN_MULTIPLIER
        travel = span / 2f

        val positions = floatArrayOf(0.5f - BAND_WIDTH_FRACTION / 2f, 0.5f, 0.5f + BAND_WIDTH_FRACTION / 2f)

        // Corner to corner, one diagonal each, so the two bands cross as they
        // rock rather than sliding along the same line in lockstep.
        warmShader = LinearGradient(
            cx - travel, cy - travel, cx + travel, cy + travel,
            intArrayOf(transparent(warm), withAlpha(warm, WARM_ALPHA), transparent(warm)),
            positions, Shader.TileMode.CLAMP
        )
        coolShader = LinearGradient(
            cx - travel, cy + travel, cx + travel, cy - travel,
            intArrayOf(transparent(cool), withAlpha(cool, COOL_ALPHA), transparent(cool)),
            positions, Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty || travel <= 0f) return

        val rock = sin((FrameClock.phase() * SWEEP_CYCLES * 2f * Math.PI).toFloat()) * travel

        // Translating along (1, 1) - the warm band's own axis - shifts its
        // bright centre back and forth across the card without distorting
        // the gradient; (1, -1) does the same for the cool band's own,
        // opposite diagonal.
        drawBand(canvas, b, warmShader, rock, rock)
        drawBand(canvas, b, coolShader, rock, -rock)
    }

    private fun drawBand(canvas: Canvas, bounds: Rect, shader: LinearGradient?, dx: Float, dy: Float) {
        val s = shader ?: return
        matrix.setTranslate(dx, dy)
        s.setLocalMatrix(matrix)
        paint.shader = s
        canvas.drawRect(bounds, paint)
    }

    private fun transparent(color: Int): Int = color and 0x00FFFFFF
    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Required by Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
