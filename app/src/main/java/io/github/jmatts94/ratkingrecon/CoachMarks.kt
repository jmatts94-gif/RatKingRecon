package io.github.jmatts94.ratkingrecon

import android.content.SharedPreferences
import androidx.annotation.IdRes
import androidx.annotation.StringRes

/** One stop on the home-screen walkthrough: what to point at, and what to say. */
data class CoachMark(
    @param:IdRes val targetId: Int,
    @param:StringRes val captionRes: Int,
    /**
     * The revision this stop was written or rewritten in.
     *
     * Rewritten counts as well as added: a caption that now says something
     * different is owed to somebody who read the old one, and the header pill
     * changing from today's steps to lifetime is exactly that. Leaving it at the
     * revision it was first written in would be saying the wording was tidied
     * rather than that the thing it describes changed.
     */
    val revisedIn: Int = 1
)

/**
 * The home-screen walkthrough, and the flag that retires it.
 *
 * Separate from [Onboarding] and additional to it. The splash explains the game;
 * this points at four things on the workshop screen and gets out of the way. It
 * reads [Onboarding.KEY_COMPLETE] as a precondition but never writes it - the
 * splash owns that flag, and the two run one after the other rather than
 * together.
 *
 * [KEY_COMPLETE] sits in the same "SaveData" preferences as everything else, so
 * Reset Save brings the walkthrough back for the fresh start that follows and
 * the flag travels with an exported save - the same bargain [Onboarding] makes.
 */
object CoachMarks {

    /**
     * The current revision of the walkthrough. Bump it when a stop is added or
     * a caption rewritten, and set the [CoachMark.revisedIn] of whatever changed
     * to match - that pair is the whole mechanism.
     */
    const val REVISION = 3

    /**
     * What a save carrying only the old boolean has already been shown.
     *
     * The walkthrough was a flag before it was a number, and everybody who
     * finished it under that flag saw the four stops it had at the time. Reading
     * their true as revision 1 is what turns them into players owed the
     * difference rather than players owed nothing or players owed all of it.
     */
    private const val LEGACY_REVISION = 1

    /** The original boolean. Still read, never written. */
    const val KEY_COMPLETE = "home_coach_complete"

    /** The revision last seen through. Written in its place. */
    const val KEY_REVISION = "home_coach_revision"

    fun seenRevision(prefs: SharedPreferences): Int = when {
        prefs.contains(KEY_REVISION) -> prefs.getInt(KEY_REVISION, 0)
        prefs.getBoolean(KEY_COMPLETE, false) -> LEGACY_REVISION
        else -> 0
    }

    /**
     * The stops this player has not been shown, in order.
     *
     * Everything for somebody new, nothing for somebody up to date, and the
     * difference for everybody in between - which is the point of the revision.
     * A returning player gets a short walkthrough of what changed rather than
     * the whole tour again, and the counter says "1 / 3" because it counts this
     * list rather than the full one.
     */
    fun stepsFor(prefs: SharedPreferences): List<CoachMark> {
        val seen = seenRevision(prefs)
        return steps.filter { it.revisedIn > seen }
    }

    fun isComplete(prefs: SharedPreferences): Boolean =
        seenRevision(prefs) >= REVISION

    fun markComplete(prefs: SharedPreferences) {
        prefs.edit().putInt(KEY_REVISION, REVISION).apply()
    }

    /**
     * Whether the workshop should be pointed at on this visit.
     *
     * The onboarding half of this is what makes "first real visit" work without
     * either screen knowing about the other. MainActivity starts the splash and
     * carries on building underneath it, so on a true first launch it reaches
     * onResume once while the splash is still unfinished - and the flag is
     * false, so nothing happens. The splash sets it on the way out, MainActivity
     * resumes a second time, and that resume is the first visit to a home screen
     * the player can actually see.
     */
    fun shouldShow(prefs: SharedPreferences): Boolean =
        Onboarding.isComplete(prefs) && stepsFor(prefs).isNotEmpty()

    /**
     * The stops, in order.
     *
     * Data rather than code, like [Onboarding.pages] - adding or reordering one
     * needs no change to the overlay, and the "2 of 6" counter follows the list
     * rather than a number written down twice.
     *
     * The order is the order the eye takes them in: the header pills left to
     * right, then the tiles beside the egg top to bottom. The three tiles are
     * one object on the screen and are walked through as one, which is also why
     * the lantern comes last rather than staying where it was - it was the only
     * tile when this list was written. The Arena tile follows it for the same
     * reason - the two now share a row, so the eye reaches the Arena tile
     * immediately after the lantern rather than at some unrelated point.
     *
     * The Battle Arena button closes the list rather than sitting with the
     * other buttons in between: everything above it is something a new player
     * uses right away, and this is the one thing on the screen meant for
     * later - fifteen fights deep is not a first session's business.
     */
    val steps = listOf(
        CoachMark(R.id.playerLevelText, R.string.coach_level),
        // Rewritten in 2: this pill counted today and now counts every step ever.
        CoachMark(R.id.stepCountText, R.string.coach_steps, revisedIn = 2),
        CoachMark(R.id.scrapText, R.string.coach_scrap),
        CoachMark(R.id.expeditionTile, R.string.coach_expedition, revisedIn = 2),
        CoachMark(R.id.stepsTile, R.string.coach_steps_today, revisedIn = 2),
        CoachMark(R.id.streakTile, R.string.coach_streak),
        CoachMark(R.id.arenaTile, R.string.coach_arena_tile, revisedIn = 3),
        CoachMark(R.id.battleArenaButton, R.string.coach_battle_arena, revisedIn = 3)
    )
}
