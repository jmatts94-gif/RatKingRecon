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
    fun `every hatchable species in the roster can be spliced`() {
        // Roster.hatchable, not Roster.all - TimeTail's Secret tier is the one
        // deliberate exception, guarded below rather than here.
        val reachable = Roster.legendary + Roster.rare + Roster.common
        val missing = Roster.hatchable.map { it.artKey } - reachable.map { it.artKey }.toSet()

        assertTrue(
            "unreachable from the Fusion Pot: $missing",
            missing.isEmpty()
        )
        assertEquals(Roster.hatchable.size, reachable.size)
    }

    @Test
    fun `the tiers partition the hatchable roster exactly`() {
        // No species in two tiers, none in none. A typo in a rarity tag would
        // drop a species out of every tier and show up here.
        assertEquals(
            "tier sizes do not sum to the hatchable roster",
            Roster.hatchable.size,
            Roster.legendary.size + Roster.rare.size + Roster.common.size
        )

        val ids = (Roster.legendary + Roster.rare + Roster.common).map { it.artKey }
        assertEquals("a species appears in more than one tier", ids.size, ids.distinct().size)
    }

    @Test
    fun `TimeTail is in none of the three splice tiers`() {
        val tiers = Roster.legendary + Roster.rare + Roster.common
        assertTrue(
            "TimeTail must never be a splice result - see Roster.SECRET",
            tiers.none { it.artKey == "timetail_pic" }
        )
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

    /**
     * Art and roster must stay in step, in both directions.
     *
     * A species with no art crashes at render time. Art with no species is dead
     * weight in the APK - sixteen such files were shipped for months before
     * anyone counted them.
     */
    @Test
    fun `RatArt holds the roster and the fallback, and nothing else`() {
        val expected = Roster.all.map { it.artKey }.toSet() + RatArt.FALLBACK_KEY
        assertEquals(expected, RatArt.byKey.keys)
    }

    // --- case handling --------------------------------------------------------

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
    fun `splicing reaches every hatchable species given enough rolls`() {
        // 32 hatchable species, the rarest tier at 5% spread over 7 - comfortably
        // covered. TimeTail is the one species outside this, on purpose.
        val seen = (1..200_000).map { Fusion.roll().artKey }.toSet()
        val missing = Roster.hatchable.map { it.artKey }.toSet() - seen

        assertTrue("never rolled: $missing", missing.isEmpty())
        assertTrue("TimeTail must never roll from the Fusion Pot", "timetail_pic" !in seen)
    }

    @Test
    fun `rolls stay inside the roster`() {
        val known = Roster.all.map { it.artKey }.toSet()
        repeat(5_000) {
            assertTrue(Fusion.roll().artKey in known)
        }
    }

    // --- the Tinkerer boost -----------------------------------------------

    @Test
    fun `a boosted roll stays inside the roster too`() {
        val known = Roster.all.map { it.artKey }.toSet()
        repeat(5_000) {
            assertTrue(Fusion.roll(boosted = true).artKey in known)
        }
    }

    @Test
    fun `a boosted roll lands legendary or rare noticeably more often`() {
        // Boosted keeps the lower of two d100s, and a lower roll is always a
        // tier at least as good (see Fusion.roll's own doc comment) - so this
        // should never do worse than a plain roll, and should do better
        // often, since both tiers sit at the low end of the d100.
        val betterTier = Roster.legendary.map { it.artKey }.toSet() + Roster.rare.map { it.artKey }

        var plainHits = 0
        var boostedHits = 0
        repeat(20_000) {
            if (Fusion.roll().artKey in betterTier) plainHits++
            if (Fusion.roll(boosted = true).artKey in betterTier) boostedHits++
        }

        assertTrue(
            "boosted should land the top two tiers more often ($boostedHits vs $plainHits of 20000)",
            boostedHits > plainHits
        )
    }
}
