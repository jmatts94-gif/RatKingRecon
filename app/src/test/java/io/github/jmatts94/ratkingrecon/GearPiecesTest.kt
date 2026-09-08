package io.github.jmatts94.ratkingrecon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gear catalogue - the same shape [FramesTest] already checks for
 * [Frames], since [GearPieces] is built the same way.
 */
class GearPiecesTest {

    @Test
    fun `every piece has a distinct id`() {
        assertEquals(GearPieces.all.size, GearPieces.all.map { it.id }.distinct().size)
    }

    @Test
    fun `byId finds every piece and nothing else`() {
        for (piece in GearPieces.all) {
            assertEquals(piece, GearPieces.byId(piece.id))
        }
        assertNull(GearPieces.byId("no_such_piece"))
        assertNull(GearPieces.byId(null))
    }

    @Test
    fun `four tools and four trinkets, as agreed`() {
        assertEquals(4, GearPieces.all.count { it.slot == GearSlot.TOOL })
        assertEquals(4, GearPieces.all.count { it.slot == GearSlot.TRINKET })
    }

    @Test
    fun `every craft cost names a relic that actually exists`() {
        for (piece in GearPieces.all) {
            for (relicId in piece.craftCost.keys) {
                assertTrue("${piece.id} costs an unknown relic $relicId", Relics.byId(relicId) != null)
            }
        }
    }

    @Test
    fun `no craft cost is free or empty`() {
        for (piece in GearPieces.all) {
            assertTrue("${piece.id} has no craft cost at all", piece.craftCost.isNotEmpty())
            for ((relicId, needed) in piece.craftCost) {
                assertTrue("${piece.id}'s $relicId cost is not positive", needed > 0)
            }
        }
    }

    @Test
    fun `Worn Pedometer is the only piece worn cog crafts`() {
        val wornCogPieces = GearPieces.all.filter { "worn_cog" in it.craftCost }
        assertEquals(listOf(GearPieces.WORN_PEDOMETER), wornCogPieces)
    }
}
