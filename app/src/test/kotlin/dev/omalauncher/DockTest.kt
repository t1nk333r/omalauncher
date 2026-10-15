package dev.omalauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class DockTest {
    @Test fun addingFillsTheFreeSlotsInOrder() {
        assertEquals(listOf("a", "b"), docked(emptyList(), listOf("a", "b")))
        assertEquals(listOf("a", "b", "c"), docked(listOf("a"), listOf("b", "c")))
    }

    @Test fun theDockHoldsFiveAndSkipsAppsAlreadyIn() {
        assertEquals(5, DOCK_SLOTS)
        assertEquals(listOf("a", "b", "c", "d", "e"), docked(listOf("a", "b", "c"), listOf("b", "d", "e", "f")))
        assertEquals(listOf("a", "b"), docked(listOf("a"), listOf("b", "b")))
        // Full: nothing more gets in.
        assertEquals(listOf("a", "b", "c", "d", "e"), docked(listOf("a", "b", "c", "d", "e"), listOf("f")))
    }

    @Test fun movingStaysWithinTheEnds() {
        val dock = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), moved(dock, "a", 1))
        assertEquals(listOf("a", "c", "b"), moved(dock, "c", -1))
        assertEquals(dock, moved(dock, "a", -1))
        assertEquals(dock, moved(dock, "c", 1))
        assertEquals(dock, moved(dock, "z", 1))
    }

    @Test fun droppingPutsAnAppAtTheSlot() {
        assertEquals(listOf("a", "x", "b"), inserted(listOf("a", "b"), "x", 1))
        // Past the end goes last; an app already in moves to the slot.
        assertEquals(listOf("a", "b", "x"), inserted(listOf("a", "b"), "x", 4))
        assertEquals(listOf("b", "c", "a"), inserted(listOf("a", "b", "c"), "a", 2))
    }

    @Test fun aFullDockTakesNoNewApp() {
        val full = listOf("a", "b", "c", "d", "e")
        assertEquals(full, inserted(full, "x", 0))
        // But its own apps still move.
        assertEquals(listOf("e", "a", "b", "c", "d"), inserted(full, "e", 0))
    }

    @Test fun homeTilesMapToDockKeys() {
        assertEquals("a:com.example/.Main@0", dockKeyOf("app:com.example/.Main@0"))
        assertEquals("l:foot", dockKeyOf("linux:foot"))
        assertEquals("s:chrome/abc", dockKeyOf("shortcut:chrome/abc"))
        // Folders and widgets don't go in the dock.
        assertEquals(null, dockKeyOf("folder:1"))
        assertEquals(null, dockKeyOf("10-today.owl"))
    }

    /** The frame shows where a carried app will land, which is where dropping puts it. */
    @Test fun aNewAppLandsAfterTheAppsThere() {
        assertEquals(2, landingSlot(listOf("a", "b"), "x", over = 4))
        assertEquals(1, landingSlot(listOf("a", "b"), "x", over = 1))
        // One already in moves within the apps there.
        assertEquals(1, landingSlot(listOf("a", "b"), "a", over = 4))
        // Full, a new one can't land.
        assertEquals(null, landingSlot(listOf("a", "b", "c", "d", "e"), "x", over = 2))
        assertEquals(inserted(listOf("a", "b"), "x", 4), inserted(listOf("a", "b"), "x", landingSlot(listOf("a", "b"), "x", 4)!!))
    }
}
