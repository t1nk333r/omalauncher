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
}
