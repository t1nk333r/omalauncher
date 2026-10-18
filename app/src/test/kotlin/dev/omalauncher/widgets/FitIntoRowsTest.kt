package dev.omalauncher.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitIntoRowsTest {
    private val cols = 6

    private fun tile(
        key: String,
        page: Int,
        x: Int,
        y: Int,
        w: Int = 1,
        h: Int = 1,
        resizable: Boolean = true,
    ) = FitTile(key, page, Cell(x, y, w, h), resizable)

    @Test fun tilesThatFitStayPut() {
        val tiles = listOf(tile("a", 1, 0, 0, 6, 3), tile("b", 1, 0, 8))
        assertEquals(emptyMap<String, Pair<Int, Cell>>(), fitIntoRows(tiles, listOf(1), cols, rows = 9))
    }

    @Test fun aTileBelowMovesUpItsColumnAtItsSize() {
        val tiles = listOf(tile("top", 1, 0, 0, 6, 4), tile("low", 1, 2, 10, 3, 2))
        val moved = fitIntoRows(tiles, listOf(1), cols, rows = 9)
        // As low as it fits in its own column: rows 7 and 8, still 3x2.
        assertEquals(mapOf("low" to (1 to Cell(2, 7, 3, 2))), moved)
    }

    @Test fun aTallerWidgetIsCutToTheRows() {
        val moved = fitIntoRows(listOf(tile("tall", 1, 0, 0, 6, 12)), listOf(1), cols, rows = 9)
        assertEquals(1 to Cell(0, 0, 6, 9), moved["tall"])
    }

    @Test fun anAppMovesAsOneCell() {
        // An old saved cell bigger than 1x1 still shows (and moves) as one cell.
        val moved = fitIntoRows(listOf(tile("app", 1, 4, 11, 2, 2, resizable = false)), listOf(1), cols, rows = 9)
        assertEquals(1 to Cell(4, 8, 1, 1), moved["app"])
    }

    @Test fun aFullPageSendsItsOverflowToTheNextPageWithRoom() {
        val full = tile("full", 1, 0, 0, 6, 9)
        val low = tile("low", 1, 0, 10, 6, 3)
        val p2 = tile("p2", 2, 0, 0, 6, 8)
        val moved = fitIntoRows(listOf(full, low, p2), listOf(1, 2, 3), cols, rows = 9)
        // Page 2 has one row left, too few for 6x3; page 3 is empty.
        assertEquals(3 to Cell(0, 0, 6, 3), moved["low"])
    }

    @Test fun withNoLaterPageItGoesOnANewOne() {
        val tiles = listOf(tile("full", 1, 0, 0, 6, 9), tile("low", 1, 0, 9, 2, 2), tile("lower", 1, 2, 9, 2, 2))
        val moved = fitIntoRows(tiles, listOf(1), cols, rows = 9)
        assertEquals(2 to Cell(0, 0, 2, 2), moved["low"])
        // The second shares the new page rather than opening another.
        assertEquals(2 to Cell(2, 0, 2, 2), moved["lower"])
    }

    @Test fun nothingEndsUpOverlapping() {
        val tiles = (0 until 12).map { tile("t$it", 1, it % 6, it, 1, 1) }
        val moved = fitIntoRows(tiles, listOf(1), cols, rows = 9)
        val cells = tiles.map { t -> moved[t.key] ?: (t.page to t.cell) }
        for (a in cells.indices) for (b in a + 1 until cells.size) {
            val (pa, ca) = cells[a]
            val (pb, cb) = cells[b]
            assertTrue("${tiles[a].key} and ${tiles[b].key} overlap", pa != pb || !ca.overlaps(cb))
        }
        assertTrue(cells.all { it.second.bottom <= 9 })
    }

    @Test fun onFewerColumnsTilesStayInsideThem() {
        // home.columns down to 4 rescales the cells first (Cell.rescaled), then home.rows fits them on that grid.
        val wide = Cell(0, 0, 6, 9).rescaled(6, 4)
        val low = Cell(3, 10, 3, 2).rescaled(6, 4)
        val moved = fitIntoRows(listOf(FitTile("wide", 1, wide, true), FitTile("low", 1, low, true)), listOf(1), cols = 4, rows = 9)
        assertEquals(Cell(0, 0, 4, 9), wide)
        // The page is full, so it goes to a new one, and no wider than the 4 columns.
        assertEquals(2 to Cell(0, 0, 2, 2), moved["low"])
        assertTrue(moved.values.all { (_, c) -> c.right <= 4 && c.bottom <= 9 })
    }
}
