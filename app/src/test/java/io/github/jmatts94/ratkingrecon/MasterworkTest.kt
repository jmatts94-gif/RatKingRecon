package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three things the Masterwork Hatchery sells.
 *
 * Every guarantee is checked over many rolls rather than one, because each is a
 * claim about what can *never* come out - a single sample would pass by luck.
 */
class MasterworkTest {

    private companion object {
        const val ROLLS = 2_000
    }

    @Test
    fun `every rat is shiny`() {
        repeat(ROLLS) {
            assertTrue("a Masterwork rat came out dull", Masterwork.roll().shiny)
        }
    }

    @Test
    fun `stats always land in the top bracket`() {
        repeat(ROLLS) {
            val rat = Masterwork.roll()
            assertTrue("power was ${rat.power}", rat.power in Masterwork.TOP_BRACKET)
            assertTrue("toughness was ${rat.toughness}", rat.toughness in Masterwork.TOP_BRACKET)
        }
    }

    @Test
    fun `the bottom tier never comes out`() {
        val commonKeys = Roster.all
            .filter { it.rarity.equals("common", ignoreCase = true) }
            .map { it.artKey }
            .toSet()

        repeat(ROLLS) {
            val rat = Masterwork.roll()
            assertFalse("rolled a bottom-tier ${rat.name}", rat.artKey in commonKeys)
        }
    }

    /**
     * The roster tags five species lowercase "common" rather than "Common".
     *
     * This is the case that a case-sensitive filter would leak, so it is pinned
     * separately from the tier check above.
     */
    @Test
    fun `species tagged lowercase common are excluded too`() {
        val lowercaseCommon = Roster.all.filter { it.rarity == "common" }
        assertTrue("expected the roster to still contain lowercase tags", lowercaseCommon.isNotEmpty())

        val pooled = Masterwork.pool.map { it.artKey }.toSet()
        for (species in lowercaseCommon) {
            assertFalse("${species.name} is tagged \"common\" and must not be pooled",
                species.artKey in pooled)
        }
    }

    @Test
    fun `the pool is every species above the bottom tier`() {
        val expected = Roster.all.count { !it.rarity.equals("common", ignoreCase = true) }

        assertEquals(expected, Masterwork.pool.size)
        assertTrue("the premium pool must not be empty", Masterwork.pool.isNotEmpty())
    }

    /** Guards against the pool collapsing to a single species after a roster edit. */
    @Test
    fun `rolls spread across the pool`() {
        val seen = (1..ROLLS).map { Masterwork.roll().artKey }.toSet()
        assertTrue("only saw ${seen.size} species", seen.size > 1)
    }
}
