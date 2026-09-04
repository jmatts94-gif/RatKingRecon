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
 * Five distinct CC0 recordings (Kenney.nl - Music Jingles and Impact Sounds,
 * both public domain, no attribution required), replacing the one asset this
 * used to stretch across three cues by playback rate alone. That compromise
 * is gone; [Cue.rate] stays at 1.0 for all of them and exists only so a future
 * cue reusing a sample at a different pitch does not need a new field for it.
 */
object GameSounds {

    /**
     * A moment worth hearing.
     *
     * [rate] is the playback multiplier: above 1 is faster and higher, below 1
     * slower and deeper. SoundPool clamps to 0.5..2.0.
     */
    enum class Cue(@param:RawRes val resId: Int, val rate: Float = 1.0f) {
        /** An egg opening. */
        HATCH(R.raw.sfx_hatch),

        /**
         * A Rustbot beaten, or the Arena's own escalating streak.
         *
         * Not one of the five Kenney.nl recordings the class comment
         * describes - the original steel-drum jingle read as a different
         * genre from the rest of the game's own brass-and-clockwork sound.
         * A synthesised three-note bell chime instead: plain FM bell
         * synthesis (a decaying sine carrier phase-modulated by a
         * faster-decaying one at a slightly inharmonic ratio), the same
         * physical idea an actual small bell rings by.
         */
        VICTORY(R.raw.sfx_victory),

        /** Something worth keeping dropping into the tin. */
        RELIC(R.raw.sfx_relic),

        /** Fight 15 cleared - the biggest fanfare in the game. */
        ARENA_CLEARED(R.raw.sfx_arena_cleared),

        /** A fight lost: a flat, physical thud, not a musical sting. */
        DEFEAT(R.raw.sfx_defeat)
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
