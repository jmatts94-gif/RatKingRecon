package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Fusion Pot's rarity engine, and the roster it draws from.
 *
 * The drift these guard against was invisible for a reason: the tier lists lived
 * inside a click listener, where no test could see them. They are checked here
 * against [Roster] itself, so the only way to break reachability again is to
 * reintroduce a hand-written list.
 */
class FusionTest {

    // --- the regression -------------------------------------------------------

    @Test
    fun `every species in the roster can be spliced`() {
        val reachable = Roster.legendary + Roster.rare + Roster.common
        val missing = Roster.all.map { it.artKey } - reachable.map { it.artKey }.toSet()

        assertTrue(
            "unreachable from the Fusion Pot: $missing",
            missing.isEmpty()
        )
        assertEquals(Roster.all.size, reachable.size)
    }

    @Test
    fun `the tiers partition the roster exactly`() {
        // No species in two tiers, none in none. A typo in a rarity tag would
        // drop a species out of every tier and show up here.
        assertEquals(
            "tier sizes do not sum to the roster",
            Roster.all.size,
            Roster.legendary.size + Roster.rare.size + Roster.common.size
        )

        val ids = (Roster.legendary + Roster.rare + Roster.common).map { it.artKey }
        assertEquals("a species appears in more than one tier", ids.size, ids.distinct().size)
    }

    @Test
    fun `no tier is empty`() {
        assertTrue(Roster.legendary.isNotEmpty())
        assertTrue(Roster.rare.isNotEmpty())
        assertTrue(Roster.common.isNotEmpty())
    }

    /**
     * The exact species the old hand-written lists left out.
     *
     * Named rather than counted, so this still fails loudly if a future edit
     * drops one of them again.
     */
    @Test
    fun `the eighteen species the old lists missed are reachable`() {
        val previouslyUnreachable = listOf(
            "chimney_pic", "sparkplug_pic",
            "hopper_pic", "latch_pic", "miller_pic", "rafter_pic",
            "boiler_pic", "brasscap_pic", "canal_pic", "coppernose_pic", "gauge_pic",
            "greasepaw_pic", "magneto_pic", "moptail_pic", "nutkin_pic", "piston_pic",
            "spindle_pic", "winch_pic"
        )
        val reachable = (Roster.legendary + Roster.rare + Roster.common).map { it.artKey }.toSet()

        previouslyUnreachable.forEach {
            assertTrue("$it is still unreachable from the Fusion Pot", it in reachable)
        }
        assertEquals("the count in the audit was 18", 18, previouslyUnreachable.size)
    }

    // --- case handling --------------------------------------------------------

    @Test
    fun `lowercase rarity tags still land in their tier`() {
        val lowercase = Roster.all.filter { it.rarity == "common" }
        assertTrue("the roster no longer has lowercase tags to check", lowercase.isNotEmpty())

        val commonKeys = Roster.common.map { it.artKey }.toSet()
        lowercase.forEach {
            assertTrue("${it.name} is tagged \"common\" but is not in the Common tier",
                it.artKey in commonKeys)
        }
    }

    @Test
    fun `withRarity ignores case in the query too`() {
        assertEquals(Roster.common.size, Roster.withRarity("COMMON").size)
        assertEquals(Roster.common.size, Roster.withRarity("common").size)
        assertEquals(Roster.legendary.size, Roster.withRarity("legendary").size)
    }

    // --- the roll -------------------------------------------------------------

    @Test
    fun `the roll bands land in the right tier`() {
        val legendary = Roster.legendary.map { it.artKey }.toSet()
        val rare = Roster.rare.map { it.artKey }.toSet()
        val common = Roster.common.map { it.artKey }.toSet()

        (1..Fusion.LEGENDARY_ROLL).forEach {
            assertTrue("roll $it should be Legendary", Fusion.speciesFor(it).artKey in legendary)
        }
        (Fusion.LEGENDARY_ROLL + 1..Fusion.RARE_ROLL).forEach {
            assertTrue("roll $it should be Rare", Fusion.speciesFor(it).artKey in rare)
        }
        (Fusion.RARE_ROLL + 1..100).forEach {
            assertTrue("roll $it should be Common", Fusion.speciesFor(it).artKey in common)
        }
    }

    @Test
    fun `the odds are the ones the tiers were written for`() {
        assertEquals("Legendary should be 5%", 5, Fusion.LEGENDARY_ROLL)
        assertEquals("Rare should be the next 25%", 30, Fusion.RARE_ROLL)
    }

    @Test
    fun `splicing reaches every species given enough rolls`() {
        // 32 species, the rarest tier at 5% spread over 7 - comfortably covered.
        val seen = (1..200_000).map { Fusion.roll().artKey }.toSet()
        val missing = Roster.all.map { it.artKey }.toSet() - seen

        assertTrue("never rolled: $missing", missing.isEmpty())
    }

    @Test
    fun `rolls stay inside the roster`() {
        val known = Roster.all.map { it.artKey }.toSet()
        repeat(5_000) {
            assertTrue(Fusion.roll().artKey in known)
        }
    }
}
