package dev.omalauncher.ui

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
}
