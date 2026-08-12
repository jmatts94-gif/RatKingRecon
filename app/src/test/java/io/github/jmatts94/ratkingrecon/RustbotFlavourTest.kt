package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The opening line of a fight.
 *
 * Cosmetic, but not arbitrary: the line has to be the same every time a given
 * encounter is drawn, or turning the phone mid-fight rewrites how the fight
 * began. That is the property worth holding onto here.
 */
class RustbotFlavourTest {

    private fun encounter(
        ratId: Long = 1,
        name: String = "Rustbot Sentry",
        reward: Int = 20,
        boss: String? = null
    ) = Encounter(ratId, name, 3, 30, reward, boss)

    @Test
    fun `the same encounter always opens the same way`() {
        val e = encounter()
        val first = RustbotFlavour.openingFor(e)

        repeat(20) {
            assertEquals("re-drawing a fight must not rewrite it", first, RustbotFlavour.openingFor(e))
        }
    }

    @Test
    fun `different encounters do not all open the same way`() {
        val seen = (1L..40L).map {
            RustbotFlavour.openingFor(encounter(ratId = it, reward = it.toInt()))
        }.distinct()

        assertTrue("the standard pool is not being varied at all", seen.size > 1)
    }

    @Test
    fun `every boss has a line of its own`() {
        val standard = (1L..40L).map {
            RustbotFlavour.openingFor(encounter(ratId = it, reward = it.toInt()))
        }.toSet()

        for (spec in Bosses.all) {
            val lines = (1L..40L).map {
                RustbotFlavour.openingFor(
                    encounter(ratId = it, name = "Boss", reward = it.toInt(), boss = spec.id)
                )
            }.toSet()

            assertTrue("${spec.id} has no flavour of its own", lines.isNotEmpty())
            assertTrue(
                "${spec.id} is borrowing the ordinary Rustbot's lines",
                lines.intersect(standard).isEmpty()
            )
            assertEquals("${spec.id} should draw on a pool, not one line", 3, lines.size)
        }
    }

    @Test
    fun `no two opponents share a pool`() {
        val pools = mutableListOf<Set<Int>>()

        pools += (1L..40L).map {
            RustbotFlavour.openingFor(encounter(ratId = it, reward = it.toInt()))
        }.toSet()

        for (spec in Bosses.all) {
            pools += (1L..40L).map {
                RustbotFlavour.openingFor(
                    encounter(ratId = it, name = "Boss", reward = it.toInt(), boss = spec.id)
                )
            }.toSet()
        }

        val all = pools.flatten()
        assertEquals("a line is being used by more than one opponent", all.size, all.distinct().size)
    }

    /**
     * A save naming a boss this build does not have is strange, but it must not
     * cost the player the fight.
     */
    @Test
    fun `an unknown boss falls back to the ordinary pool`() {
        val standard = (1L..40L).map {
            RustbotFlavour.openingFor(encounter(ratId = it, reward = it.toInt()))
        }.toSet()

        val line = RustbotFlavour.openingFor(encounter(boss = "no_such_boss"))
        assertTrue("an unknown boss should read as an ordinary Rustbot", line in standard)
    }

    @Test
    fun `the ordinary pool varies with the rat as well as the reward`() {
        val a = RustbotFlavour.openingFor(encounter(ratId = 1, reward = 20))
        val b = RustbotFlavour.openingFor(encounter(ratId = 2, reward = 20))
        val c = RustbotFlavour.openingFor(encounter(ratId = 3, reward = 20))

        assertTrue(
            "three consecutive rats should not all open identically",
            setOf(a, b, c).size > 1
        )
        assertNotEquals(0, a)
    }
}
