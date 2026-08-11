package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The running contract, and the arithmetic behind what the board shows.
 *
 * The step target is an absolute sensor reading rather than a count from zero,
 * which is the part that is easy to get wrong: progress is the distance from
 * where the contract was accepted, not the reading itself.
 */
class ActiveContractTest {

    private val offer = BountyOffer(name = "Copper Wire Raid", steps = 500f, minutes = 10, reward = 30)

    private fun accepted(atSteps: Float = 40_000f, now: Long = 1_000_000L): FakePrefs {
        val prefs = FakePrefs()
        ActiveContract.accept(prefs, offer, currentTotalSteps = atSteps, now = now)
        return prefs
    }

    // --- round trip -----------------------------------------------------------

    @Test
    fun `nothing is active on a fresh save`() {
        val prefs = FakePrefs()
        assertFalse(ActiveContract.isActive(prefs))
        assertNull(ActiveContract.load(prefs))
    }

    @Test
    fun `accepting records everything the board needs to describe it`() {
        val contract = ActiveContract.load(accepted())

        assertNotNull(contract)
        assertEquals("Copper Wire Raid", contract!!.name)
        assertEquals(500f, contract.requiredSteps, 0.01f)
        assertEquals("the target is absolute", 40_500f, contract.targetSteps, 0.01f)
        assertEquals(30, contract.reward)
        assertEquals(1_000_000L + 600_000L, contract.endsAt)
    }

    // --- progress -------------------------------------------------------------

    @Test
    fun `progress is measured from where the contract was accepted`() {
        val contract = ActiveContract.load(accepted(atSteps = 40_000f))!!

        // The reading is 40,320 - the player has walked 320 of the 500.
        assertEquals(320, contract.stepsWalked(40_320f))
        assertEquals(180, contract.stepsRemaining(40_320f))
        assertEquals(64, contract.percentComplete(40_320f))
    }

    @Test
    fun `nothing walked yet reads as zero, not as the sensor reading`() {
        val contract = ActiveContract.load(accepted(atSteps = 40_000f))!!

        assertEquals(0, contract.stepsWalked(40_000f))
        assertEquals(500, contract.stepsRemaining(40_000f))
        assertEquals(0, contract.percentComplete(40_000f))
    }

    @Test
    fun `a finished contract reads as complete rather than overshooting`() {
        val contract = ActiveContract.load(accepted(atSteps = 40_000f))!!

        assertEquals(500, contract.stepsWalked(40_900f))
        assertEquals(0, contract.stepsRemaining(40_900f))
        assertEquals(100, contract.percentComplete(40_900f))
    }

    /**
     * A reboot restarts the step counter, and GameEngine re-baselines against
     * the new reading. Until the counter climbs past where the contract was
     * accepted, the current reading is behind its own start.
     */
    @Test
    fun `a reading behind the start reads as zero, never negative`() {
        val contract = ActiveContract.load(accepted(atSteps = 40_000f))!!

        assertEquals(0, contract.stepsWalked(120f))
        assertEquals(0, contract.percentComplete(120f))
        assertTrue(contract.stepsRemaining(120f) > 0)
    }

    // --- time -----------------------------------------------------------------

    @Test
    fun `time remaining counts down and floors at zero`() {
        val contract = ActiveContract.load(accepted(now = 1_000_000L))!!

        assertEquals(600_000L, contract.millisRemaining(1_000_000L))
        assertEquals(60_000L, contract.millisRemaining(1_540_000L))
        assertEquals(0L, contract.millisRemaining(9_999_999L))
    }

    @Test
    fun `part of a minute still reads as a minute while it is winnable`() {
        val contract = ActiveContract.load(accepted(now = 0L))!!

        assertEquals(10, contract.minutesRemaining(0L))
        // Forty seconds left is still a minute on the display, not zero.
        assertEquals(1, contract.minutesRemaining(600_000L - 40_000L))
        assertEquals(0, contract.minutesRemaining(600_000L))
    }

    @Test
    fun `expiry is reported once the deadline passes`() {
        val contract = ActiveContract.load(accepted(now = 0L))!!

        assertFalse(contract.hasExpired(599_999L))
        assertTrue(contract.hasExpired(600_000L))
        assertTrue(contract.hasExpired(700_000L))
    }

    // --- contracts accepted before the requirement was recorded ---------------

    /**
     * The board used to save only what it needed to resolve a contract. A save
     * carrying one of those must still describe it, rather than claiming a goal
     * of zero and dividing by it.
     */
    @Test
    fun `a legacy contract reports remaining steps instead of a fraction`() {
        val prefs = FakePrefs()
        prefs.values[GameEngine.KEY_BOUNTY_ACTIVE] = true
        prefs.values[GameEngine.KEY_BOUNTY_TARGET] = 40_500f
        prefs.values[GameEngine.KEY_BOUNTY_END] = 600_000L
        prefs.values[GameEngine.KEY_BOUNTY_REWARD] = 30

        val contract = ActiveContract.load(prefs)!!

        assertFalse("no requirement was recorded", contract.knowsRequirement)
        assertEquals("", contract.name)
        assertEquals("remaining is still knowable", 180, contract.stepsRemaining(40_320f))
        assertEquals("and progress is not guessed at", 0, contract.percentComplete(40_320f))
        assertEquals(0, contract.stepsWalked(40_320f))
    }

    @Test
    fun `a recorded contract knows its requirement`() {
        assertTrue(ActiveContract.load(accepted())!!.knowsRequirement)
    }

    // --- the keys the resolver uses -------------------------------------------

    @Test
    fun `accept writes the keys GameEngine resolves against`() {
        val prefs = accepted(atSteps = 40_000f)

        // If these drift apart, a contract is describable but never pays out.
        assertEquals(true, prefs.values[GameEngine.KEY_BOUNTY_ACTIVE])
        assertEquals(40_500f, prefs.values[GameEngine.KEY_BOUNTY_TARGET])
        assertEquals(30, prefs.values[GameEngine.KEY_BOUNTY_REWARD])
    }

    @Test
    fun `a paid out contract stops being active`() {
        val prefs = accepted(atSteps = 40_000f)
        assertNotNull(ActiveContract.load(prefs))

        // GameEngine clears this flag when it pays out or times out.
        prefs.values[GameEngine.KEY_BOUNTY_ACTIVE] = false

        assertNull(ActiveContract.load(prefs))
        assertFalse(ActiveContract.isActive(prefs))
    }
}
