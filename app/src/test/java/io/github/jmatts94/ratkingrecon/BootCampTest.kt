package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boot Camp's two promises: a fresh save gets exactly one decent starter, and
 * an existing save gets nothing. Plus the practice fight staying a soft one.
 */
class BootCampTest {

    @Test
    fun `a fresh save gets one starter of at least 3 by 3`() {
        repeat(200) {
            val prefs = FakePrefs()
            val dao = FakeRatDao()

            val starter = BootCamp.grantStarterIfDue(dao, prefs)

            assertNotNull(starter)
            assertTrue("power was ${starter!!.power}", starter.power >= BootCamp.STARTER_MIN_STAT)
            assertTrue("toughness was ${starter.toughness}", starter.toughness >= BootCamp.STARTER_MIN_STAT)
            assertEquals(1, dao.count())
            assertFalse(BootCamp.needsStarter(prefs))
        }
    }

    @Test
    fun `the starter is only ever given once`() {
        val prefs = FakePrefs()
        val dao = FakeRatDao()

        BootCamp.grantStarterIfDue(dao, prefs)
        val second = BootCamp.grantStarterIfDue(dao, prefs)

        assertNull(second)
        assertEquals(1, dao.count())
    }

    @Test
    fun `a save that already has rats is marked done without a starter`() {
        val prefs = FakePrefs()
        val dao = FakeRatDao()
        GameEngine.mintRat(dao, prefs)

        val starter = BootCamp.grantStarterIfDue(dao, prefs)

        assertNull(starter)
        assertEquals(1, dao.count())
        assertFalse(BootCamp.needsStarter(prefs))
    }

    @Test
    fun `the practice fight is soft and flagged until cleared`() {
        val prefs = FakePrefs()
        val dao = FakeRatDao()
        val starter = BootCamp.grantStarterIfDue(dao, prefs)!!

        assertTrue(BootCamp.raisePracticeFight(prefs, starter, "Training Bot"))

        val encounter = Encounter.load(prefs)!!
        assertEquals(1, encounter.botPower)
        assertEquals(starter.maxHp / 2, encounter.botMaxHp)
        assertEquals(BootCamp.PRACTICE_REWARD, encounter.reward)
        assertTrue(BootCamp.isPracticePending(prefs))

        BootCamp.clearPractice(prefs)
        assertFalse(BootCamp.isPracticePending(prefs))
    }

    @Test
    fun `a fight already pending is not replaced by the practice one`() {
        val prefs = FakePrefs()
        val dao = FakeRatDao()
        val starter = BootCamp.grantStarterIfDue(dao, prefs)!!
        Encounter.save(prefs, Encounter(starter.id, "Rustbot Welder", 3, 30, 12))

        assertFalse(BootCamp.raisePracticeFight(prefs, starter, "Training Bot"))
        assertEquals("Rustbot Welder", Encounter.load(prefs)!!.botName)
        assertFalse(BootCamp.isPracticePending(prefs))
    }
}
