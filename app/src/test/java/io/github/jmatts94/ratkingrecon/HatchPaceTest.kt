package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Test

class HatchPaceTest {

    @Test
    fun `early levels cost what they always did`() {
        assertEquals(250, GameEngine.maxExpFor(1))
        assertEquals(2_500, GameEngine.maxExpFor(10))
        assertEquals(5_000, GameEngine.maxExpFor(20))
    }

    @Test
    fun `a hatch stops costing more after level 20`() {
        assertEquals(5_000, GameEngine.maxExpFor(21))
        assertEquals(5_000, GameEngine.maxExpFor(30))
        assertEquals(5_000, GameEngine.maxExpFor(99))
    }

    @Test
    fun `level 50 is still a long walk`() {
        assertEquals(197_500, (1 until 50).sumOf { GameEngine.maxExpFor(it) })
    }

    @Test
    fun `steps to next hatch counts down and never goes negative`() {
        val prefs = FakePrefs()
        prefs.values[GameEngine.KEY_LEVEL] = 30
        prefs.values[GameEngine.KEY_EXP] = 1_200
        assertEquals(3_800, GameEngine.stepsToNextHatch(prefs))

        // A save from the old curve can sit above the new, lower cost.
        prefs.values[GameEngine.KEY_EXP] = 7_000
        assertEquals(0, GameEngine.stepsToNextHatch(prefs))
    }
}
