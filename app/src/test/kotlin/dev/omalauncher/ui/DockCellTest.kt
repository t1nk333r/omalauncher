package dev.omalauncher.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class DockCellTest {
    /** A large dock on a phone: five across set the size, not the dock's height. */
    @Test fun fiveAcrossLimitAPhonesDockIcons() {
        assertEquals(72.dp, dockCellSize(along = 400.dp, across = 100.dp))
    }

    @Test fun aShortDockLimitsThemByItsHeight() {
        assertEquals(52.dp, dockCellSize(along = 800.dp, across = 64.dp))
    }

    @Test fun neverSmallerThanTheRecentStripsCells() {
        assertEquals(48.dp, dockCellSize(along = 200.dp, across = 40.dp))
    }
}
