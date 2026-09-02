package io.github.jmatts94.ratkingrecon

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi

/**
 * The short buzzes the game gives back for the same moments [GameSounds]
 * plays a cue for.
 *
 * Reuses [GameSettings.soundEnabled] rather than a switch of its own - the
 * Settings toggle has read "Sound and vibration" since before either existed,
 * so a player who turned it off already opted out of both.
 *
 * minSdk is 24, but [VibrationEffect] is API 26 and a predefined effect
 * ([VibrationEffect.createPredefined]) is API 29 - so every cue below has a
 * one-shot fallback for 26-28 and a plain [Vibrator.vibrate] duration for
 * 24-25, where amplitude cannot be controlled at all.
 */
object Haptics {

    /** A moment worth feeling, alongside the [GameSounds.Cue] it pairs with. */
    enum class Cue {
        /** An egg opening: light and quick. */
        HATCH,

        /** A Rustbot beaten: two quick pulses, so it reads as a result rather than a tick. */
        VICTORY,

        /** Something heavy dropping into the tin: one longer, weightier pulse. */
        RELIC,

        /** Fight 15 cleared: the strongest, most deliberate pattern in the game. */
        ARENA_CLEARED,

        /** A fight lost: a single flat, understated pulse - a fact, not a punishment. */
        DEFEAT
    }

    private fun vibrator(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    /**
     * Plays [cue], if the player has sound/vibration switched on and the
     * device actually has a vibrator (some tablets and Chromebooks do not).
     */
    fun play(context: Context, cue: Cue) {
        if (!GameSettings.soundEnabled(RatRepository.prefs(context))) return

        val vibrator = vibrator(context)
        if (!vibrator.hasVibrator()) return

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> vibrator.vibrate(predefined(cue))
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> vibrator.vibrate(oneShot(cue))
            else -> @Suppress("DEPRECATION") vibrator.vibrate(legacyPattern(cue), -1)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun predefined(cue: Cue): VibrationEffect = when (cue) {
        Cue.HATCH -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        Cue.VICTORY -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        Cue.RELIC -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        Cue.ARENA_CLEARED -> VibrationEffect.createWaveform(
            longArrayOf(0, 40, 60, 40, 60, 80),
            intArrayOf(0, 255, 0, 255, 0, 255),
            -1
        )
        Cue.DEFEAT -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun oneShot(cue: Cue): VibrationEffect = when (cue) {
        Cue.HATCH -> VibrationEffect.createOneShot(30, 180)
        Cue.VICTORY -> VibrationEffect.createWaveform(
            longArrayOf(0, 35, 60, 35),
            intArrayOf(0, 220, 0, 220),
            -1
        )
        Cue.RELIC -> VibrationEffect.createOneShot(80, 255)
        Cue.ARENA_CLEARED -> VibrationEffect.createWaveform(
            longArrayOf(0, 40, 60, 40, 60, 80),
            intArrayOf(0, 200, 0, 220, 0, 255),
            -1
        )
        Cue.DEFEAT -> VibrationEffect.createOneShot(90, 140)
    }

    /** 24-25: duration only, no amplitude control and no predefined effects. */
    private fun legacyPattern(cue: Cue): LongArray = when (cue) {
        Cue.HATCH -> longArrayOf(0, 30)
        Cue.VICTORY -> longArrayOf(0, 35, 60, 35)
        Cue.RELIC -> longArrayOf(0, 80)
        Cue.ARENA_CLEARED -> longArrayOf(0, 40, 60, 40, 60, 80)
        Cue.DEFEAT -> longArrayOf(0, 90)
    }
}
