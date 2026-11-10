package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class WinterEventTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute)
        }.timeInMillis

    @Test
    fun `the window opens on 1 December and shuts after 11 January`() {
        assertFalse(WinterEvent.inWindow(at(2026, Calendar.NOVEMBER, 30, 23, 59)))
        assertTrue(WinterEvent.inWindow(at(2026, Calendar.DECEMBER, 1, 0, 0)))
        assertTrue(WinterEvent.inWindow(at(2027, Calendar.JANUARY, 11, 23, 59)))
        assertFalse(WinterEvent.inWindow(at(2027, Calendar.JANUARY, 12, 0, 0)))
    }

    @Test
    fun `steps add up and nothing is paid before the goal`() {
        val prefs = FakePrefs()
        assertFalse(WinterEvent.advance(prefs, 10_000, running = true))
        assertFalse(WinterEvent.advance(prefs, 14_999, running = true))
        assertEquals(24_999, WinterEvent.steps(prefs))
        assertEquals(0, GameEngine.scrapOf(prefs))
        assertFalse(ShopEffects.ownsCosmetic(prefs, Frames.FROSTBOUND.id))
    }

    @Test
    fun `reaching the goal pays once and caps the count`() {
        val prefs = FakePrefs()
        prefs.edit().putInt(GameEngine.KEY_SCRAP, 40).apply()
        WinterEvent.advance(prefs, 24_990, running = true)

        assertTrue(WinterEvent.advance(prefs, 500, running = true))
        assertEquals(WinterEvent.GOAL_STEPS, WinterEvent.steps(prefs))
        assertEquals(40 + WinterEvent.SCRAP_REWARD, GameEngine.scrapOf(prefs))
        assertTrue(ShopEffects.ownsCosmetic(prefs, Frames.FROSTBOUND.id))

        assertFalse("must only pay once", WinterEvent.advance(prefs, 500, running = true))
        assertEquals(40 + WinterEvent.SCRAP_REWARD, GameEngine.scrapOf(prefs))
    }

    @Test
    fun `steps outside the event do not count`() {
        val prefs = FakePrefs()
        assertFalse(WinterEvent.advance(prefs, 30_000, running = false))
        assertEquals(0, WinterEvent.steps(prefs))
    }

    @Test
    fun `the Frostbound frame cannot be bought or won in the Arena`() {
        assertFalse(Frames.FROSTBOUND.sellable)
        assertFalse(Frames.FROSTBOUND.arenaPool)
        assertTrue(Frames.all.contains(Frames.FROSTBOUND))
    }
}
