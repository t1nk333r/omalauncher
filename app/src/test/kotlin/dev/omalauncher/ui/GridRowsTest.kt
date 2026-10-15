package dev.omalauncher.ui

import dev.omalauncher.widgets.Cell
import org.junit.Assert.assertEquals
import org.junit.Test

class GridRowsTest {
    @Test fun theGridHasTheRowsAskedFor() {
        assertEquals(9, gridRows(wanted = 9, used = 0))
        assertEquals(12, gridRows(wanted = 12, used = 4))
    }

    /** Asking for fewer rows than the tiles reach squeezes nothing on top of another. */
    @Test fun neverFewerThanTheTilesReach() {
        assertEquals(10, gridRows(wanted = 9, used = 10))
        assertEquals(1, gridRows(wanted = 0, used = 0))
    }

    /** What counts as reaching below home.rows, so those tiles move up: an app's own row, a widget's whole height. */
    @Test fun aTileReachesItsBottomRow() {
        assertEquals(10, reachRow(Cell(0, 9, 1, 1), resizable = false))
        // An app shows 1x1 even if an old saved cell was bigger.
        assertEquals(9, reachRow(Cell(0, 8, 2, 3), resizable = false))
        assertEquals(10, reachRow(Cell(0, 7, 6, 3), resizable = true))
    }
}
