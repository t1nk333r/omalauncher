package dev.omalauncher.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloseGapsTest {
    private val cols = 4

    private fun tile(
        key: String,
        x: Int,
        y: Int,
        w: Int = 1,
        h: Int = 1,
        resizable: Boolean = true,
    ) = FitTile(key, 1, Cell(x, y, w, h), resizable)

    @Test fun aPackedPageStaysPut() {
        val tiles = listOf(tile("w", 0, 0, 2, 2), tile("a", 2, 0), tile("b", 3, 0), tile("c", 2, 1))
        assertEquals(emptyMap<String, Cell>(), closeGaps(tiles, cols, rows = 6))
    }

    @Test fun tilesPackUpAndLeftInReadingOrder() {
        // [W W] . [a]       [W W][a][b]
        // [W W] .  .    ->  [W W][c] .
        //  .   [b][c]
        val tiles = listOf(tile("w", 0, 0, 2, 2), tile("a", 3, 0), tile("b", 1, 2), tile("c", 2, 2))
        val moved = closeGaps(tiles, cols, rows = 6)
        assertEquals(mapOf("a" to Cell(2, 0, 1, 1), "b" to Cell(3, 0, 1, 1), "c" to Cell(2, 1, 1, 1)), moved)
    }

    @Test fun aWidgetKeepsItsSize() {
        val moved = closeGaps(listOf(tile("w", 1, 3, 3, 2)), cols, rows = 6)
        assertEquals(mapOf("w" to Cell(0, 0, 3, 2)), moved)
    }

    @Test fun anAppMovesAsOneCell() {
        // An old saved cell bigger than 1x1 still shows (and packs) as one cell.
        val moved = closeGaps(listOf(tile("a", 0, 0), tile("app", 3, 4, 2, 2, resizable = false)), cols, rows = 6)
        assertEquals(mapOf("app" to Cell(1, 0, 1, 1)), moved)
    }

    @Test fun aSmallTileFillsAHoleBesideAWideOne() {
        // The 1x1 below a full-width widget goes up beside the first one; the widget stays.
        val tiles = listOf(tile("a", 0, 0), tile("wide", 0, 1, 4, 1), tile("b", 0, 3))
        val moved = closeGaps(tiles, cols, rows = 6)
        assertEquals(mapOf("b" to Cell(1, 0, 1, 1)), moved)
    }

    @Test fun whenOneWouldNotFitNothingMoves() {
        //  . [a][t]      [a][t] .
        // [w  w][t]  ->   . [t] .   and the 2x1 has nowhere left: rather than lose it, nothing moves.
        val tiles = listOf(tile("t", 2, 0, 1, 2), tile("a", 1, 0), tile("w", 0, 1, 2, 1))
        assertNull(closeGaps(tiles, cols = 3, rows = 2))
    }

    @Test fun nothingEndsUpOverlapping() {
        val tiles =
            listOf(
                tile("w1", 1, 1, 2, 2),
                tile("a", 0, 5),
                tile("w2", 2, 4, 2, 3),
                tile("b", 3, 0),
                tile("c", 0, 3, 3, 1, resizable = false),
            )
        val moved = closeGaps(tiles, cols, rows = 8)
        val cells = tiles.map { moved!![it.key] ?: it.shown }
        for (i in cells.indices) for (j in i + 1 until cells.size) assertTrue("${cells[i]} ${cells[j]}", !cells[i].overlaps(cells[j]))
        assertTrue(cells.all { it.x >= 0 && it.right <= cols && it.y >= 0 })
    }
}
