package dev.omalauncher.ui

import dev.omalauncher.ui.GestureAction.Drawer
import dev.omalauncher.ui.GestureAction.None
import dev.omalauncher.ui.GestureAction.Notifications
import dev.omalauncher.ui.GestureAction.Search
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturesTest {
    private val downward = 12f
    private val upward = -12f

    @Test fun upOpensTheDrawerAndDownSearchByDefault() {
        assertEquals(Drawer, SwipeUpChoices.first())
        assertEquals(Search, SwipeDownChoices.first())
        assertEquals(HomeSwipe(Drawer, -1f), homeSwipeFor(0f, upward, up = Drawer, down = Search))
        assertEquals(HomeSwipe(Search, 1f), homeSwipeFor(0f, downward, up = Drawer, down = Search))
    }

    @Test fun eitherWayCanBeTheDrawerSearchOrNothing() {
        for (choices in listOf(SwipeUpChoices, SwipeDownChoices)) assertTrue(choices.containsAll(listOf(Drawer, Search, None)))
        assertEquals(HomeSwipe(Search, -1f), homeSwipeFor(0f, upward, up = Search, down = Drawer))
        // Set to the drawer, swiping down opens it under the finger, downwards.
        assertEquals(HomeSwipe(Drawer, 1f), homeSwipeFor(0f, downward, up = Search, down = Drawer))
    }

    @Test fun onlySwipingDownPullsTheShade() {
        assertFalse(Notifications in SwipeUpChoices)
        assertEquals(HomeSwipe(Notifications, 1f), homeSwipeFor(0f, downward, up = Drawer, down = Notifications))
        assertEquals(HomeSwipe(Drawer, -1f), homeSwipeFor(0f, upward, up = Drawer, down = Notifications))
    }

    @Test fun anOpenDrawerIsDraggedWhateverTheSettings() {
        assertEquals(HomeSwipe(Drawer, -1f), homeSwipeFor(0.4f, downward, up = Drawer, down = Search))
        assertEquals(HomeSwipe(Drawer, -1f), homeSwipeFor(1f, upward, up = None, down = Notifications))
    }

    @Test fun withNothingSetOneWayTheDrawerStillFollowsASwipeThatTurnsBack() {
        assertEquals(HomeSwipe(Drawer, -1f), homeSwipeFor(0f, downward, up = Drawer, down = None))
        assertEquals(HomeSwipe(Drawer, 1f), homeSwipeFor(0f, upward, up = None, down = Drawer))
        assertEquals(None, homeSwipeFor(0f, downward, up = Search, down = None).action)
        assertEquals(None, homeSwipeFor(0f, upward, up = None, down = None).action)
    }

    @Test fun searchOpensOnceTheSwipeGoesFarOrFastEnoughItsWay() {
        assertTrue(reachesSearch(distance = 60f, velocity = 0f, threshold = 56f))
        assertTrue(reachesSearch(distance = 20f, velocity = 1200f, threshold = 56f))
        assertFalse(reachesSearch(distance = 20f, velocity = 300f, threshold = 56f))
        // Turned back: it ended behind where it started, or was let go of moving back.
        assertFalse(reachesSearch(distance = -5f, velocity = 1200f, threshold = 56f))
        assertFalse(reachesSearch(distance = 30f, velocity = -1200f, threshold = 56f))
    }
}
