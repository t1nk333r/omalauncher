package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalGestureTest {
    @Test fun sidewaysOnAnInertTileTurnsThePage() {
        assertEquals(TerminalGesture.TurnPage, terminalGesture(tileActive = false, dx = 40f, dy = 5f))
    }

    /** When home does nothing with it, a vertical drag scrolls the tile. */
    @Test fun aVerticalDragScrollsTheTile() {
        assertEquals(TerminalGesture.ScrollTile, terminalGesture(tileActive = false, dx = 4f, dy = -30f))
    }

    @Test fun anActiveTileKeepsASidewaysDrag() {
        assertEquals(TerminalGesture.ScrollTile, terminalGesture(tileActive = true, dx = 40f, dy = 2f))
    }

    /** Home's swipes reach over a tile not in use, as over any widget: swiping down for search works there too. */
    @Test fun aVerticalSwipeOnAnInertTileGoesToHome() {
        assertEquals(TerminalGesture.Home, terminalGesture(tileActive = false, dx = 4f, dy = 30f, homeTakes = true))
        assertEquals(TerminalGesture.Home, terminalGesture(tileActive = false, dx = 4f, dy = -30f, homeTakes = true))
        // Typing in it, the tile keeps its swipes.
        assertEquals(TerminalGesture.ScrollTile, terminalGesture(tileActive = true, dx = 4f, dy = 30f, homeTakes = true))
    }
}
