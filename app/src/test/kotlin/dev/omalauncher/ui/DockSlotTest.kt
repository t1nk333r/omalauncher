package dev.omalauncher.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DockSlotTest {
    @Test fun aSlotAlongALyingDock() {
        val dock = Rect(0f, 1000f, 500f, 1100f)
        assertEquals(0, dockSlotAt(dock, Offset(10f, 1050f)))
        assertEquals(2, dockSlotAt(dock, Offset(250f, 1050f)))
        assertEquals(4, dockSlotAt(dock, Offset(499f, 1050f)))
    }

    @Test fun aSlotDownAStandingDock() {
        // Beside landscape's grid the dock stands: its slots go down it.
        val dock = Rect(900f, 0f, 960f, 500f)
        assertEquals(3, dockSlotAt(dock, Offset(930f, 350f)))
    }

    @Test fun offTheDockOrWithNoneIsNoSlot() {
        assertNull(dockSlotAt(Rect(0f, 1000f, 500f, 1100f), Offset(250f, 900f)))
        assertNull(dockSlotAt(Rect.Zero, Offset(0f, 0f)))
    }
}
