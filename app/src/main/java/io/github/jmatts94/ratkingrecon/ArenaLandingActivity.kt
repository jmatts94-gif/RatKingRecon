package io.github.jmatts94.ratkingrecon

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * The Arena's front door - a themed, ambient landing screen distinct from the
 * rest of the app's warm-on-cream look, meant to be the one "end game" moment
 * the game builds towards. Owns no Arena rules of its own; it only leads to
 * [ArenaSelectActivity], which picks the rat a run is actually built around.
 */
class ArenaLandingActivity : AppCompatActivity() {

    private val gearAnimators = mutableListOf<ObjectAnimator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_arena_landing)
        EdgeToEdge.apply(this)

        findViewById<MaterialButton>(R.id.arenaSelectChampionButton).setOnClickListener {
            startActivity(Intent(this, ArenaSelectActivity::class.java))
        }
        findViewById<View>(R.id.arenaLeaveButton).setOnClickListener { finish() }

        gearAnimators += spin(R.id.arenaGearOne, 90_000L)
        gearAnimators += spin(R.id.arenaGearTwo, 130_000L)
    }

    /**
     * A slow, indefinite rotation - long enough per revolution to read as
     * ambient machinery rather than as a spinner asking to be watched.
     *
     * A plain animator on [View.ROTATION] rather than [FrameAnimator]'s own
     * hand-rolled Choreographer loop: that exists to drive real per-frame
     * canvas drawing (dashed paths, particles, colour blends) across
     * potentially many recycled card overlays at once. This is two fixed
     * ImageViews doing nothing but turning, which the platform's own
     * animation framework already handles as a hardware-layer transform -
     * no per-frame redraw of the gear's own pixels, and paused automatically
     * whenever this screen is not the one in front (see onPause).
     */
    private fun spin(viewId: Int, durationMs: Long): ObjectAnimator {
        val view = findViewById<View>(viewId)
        return ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 360f).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    override fun onResume() {
        super.onResume()
        gearAnimators.forEach { if (it.isStarted) it.resume() else it.start() }
    }

    override fun onPause() {
        super.onPause()
        gearAnimators.forEach { it.pause() }
    }

    override fun onDestroy() {
        super.onDestroy()
        gearAnimators.forEach { it.cancel() }
    }
}
