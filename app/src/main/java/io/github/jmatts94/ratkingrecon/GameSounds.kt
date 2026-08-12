package io.github.jmatts94.ratkingrecon

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.annotation.RawRes

/**
 * The short cues the game plays to itself.
 *
 * These are in-app only. A hatch that happens while the phone is in a pocket is
 * announced by its notification channel, which carries its own sound; this is
 * for the moments the player is actually looking at, where the notification is
 * deliberately suppressed and nothing was making a noise at all.
 *
 * All three cues are the same sample at different playback rates, which is what
 * [SoundPool.setRate] is for: rate moves speed and pitch together, so one clip
 * yields a bright quick chime, a heavier slower clunk, and the original. That is
 * a deliberate compromise rather than an ideal - the project has exactly one
 * audio asset, and three distinguishable cues out of one beat one cue used three
 * times. Dropping in dedicated recordings later means changing a resource id and
 * setting the rate back to 1, and nothing else.
 */
object GameSounds {

    /**
     * A moment worth hearing.
     *
     * [rate] is the playback multiplier: above 1 is faster and higher, below 1
     * slower and deeper. SoundPool clamps to 0.5..2.0.
     */
    enum class Cue(@param:RawRes val resId: Int, val rate: Float) {
        /** An egg opening, at its own natural pitch. */
        HATCH(R.raw.hatch, 1.0f),

        /** A Rustbot coming apart: quick and bright, so it reads as a result. */
        VICTORY(R.raw.hatch, 1.45f),

        /** Something heavy dropping into the tin: slow and low. */
        RELIC(R.raw.hatch, 0.7f)
    }

    /** Enough voices for two cues to overlap without either being cut off. */
    private const val MAX_STREAMS = 3

    private var pool: SoundPool? = null

    /** Resource id to the SoundPool sample id it loaded as. */
    private val sampleIds = mutableMapOf<Int, Int>()

    /** Samples that have finished decoding and can actually be played. */
    private val ready = mutableSetOf<Int>()

    /**
     * Loads the samples ahead of time.
     *
     * Called from [RatKingApp], because SoundPool decodes off-thread and a
     * sample asked for before it is ready is simply dropped - which would mean
     * the very first hatch of a session, the one most worth hearing, being the
     * one that made no sound.
     */
    @Synchronized
    fun warmUp(context: Context) {
        if (pool != null) return

        val created = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()

        created.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) synchronized(this) { ready += sampleId }
        }

        for (resId in Cue.entries.map { it.resId }.distinct()) {
            sampleIds[resId] = created.load(context.applicationContext, resId, 1)
        }

        pool = created
    }

    /**
     * Plays [cue], if the player has sound switched on.
     *
     * Silent rather than throwing when the sample is not loaded yet: a cue is a
     * flourish, and a missed one is worth far less than a crash at the moment
     * something good happened.
     */
    fun play(context: Context, cue: Cue) {
        if (!GameSettings.soundEnabled(RatRepository.prefs(context))) return

        warmUp(context)

        synchronized(this) {
            val sampleId = sampleIds[cue.resId] ?: return
            if (sampleId !in ready) return

            pool?.play(sampleId, 1f, 1f, 1, 0, cue.rate)
        }
    }
}
