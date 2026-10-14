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

    // Release checks: the swipe went 56px its way to count as far enough, 800 px/s to count as a fling.
    private val far = 60f
    private val near = 20f
    private val threshold = 56f
    private val flingDown = 1200f
    private val flingUp = -1200f

    @Test fun upOpensTheDrawerAndDownSearchByDefault() {
        assertEquals(Drawer, pick(null, SwipeUpChoices))
        assertEquals(Search, pick(null, SwipeDownChoices))
        assertEquals(HomeSwipe(Drawer, -1f), homeSwipeFor(0f, upward, up = Drawer, down = Search))
        assertEquals(HomeSwipe(Search, 1f), homeSwipeFor(0f, downward, up = Drawer, down = Search))
    }

    @Test fun aSavedChoiceIsKeptOnlyIfTheGestureOffersIt() {
        assertEquals(Notifications, pick("notifications", SwipeDownChoices))
        assertEquals(None, pick("none", SwipeDownChoices))
        assertEquals(Search, pick("search", SwipeUpChoices))
        // The shade is only for swiping down, and an unknown id is a leftover: both fall back to the default.
        assertEquals(Drawer, pick("notifications", SwipeUpChoices))
        assertEquals(Search, pick("teleport", SwipeDownChoices))
    }

    @Test fun eitherWayCanBeTheDrawerSearchOrNothing() {
        for (choices in listOf(SwipeUpChoices, SwipeDownChoices)) assertTrue(choices.containsAll(listOf(Drawer, Search, None)))
        assertEquals(HomeSwipe(Search, -1f), homeSwipeFor(0f, upward, up = Search, down = Drawer))
        assertEquals(HomeSwipe(Drawer, 1f), homeSwipeFor(0f, downward, up = Search, down = Drawer))
    }

    @Test fun onlySwipingDownPullsTheShade() {
        assertFalse(Notifications in SwipeUpChoices)
        val shade = homeSwipeFor(0f, downward, up = Drawer, down = Notifications)
        assertEquals(HomeSwipe(Notifications, 1f), shade)
        assertFalse(shade.dragsDrawer)
        assertFalse(shade.opensOnRelease(far, flingDown, threshold))
    }

    @Test fun theDrawerFollowsTheFingerOnlyGoingUp() {
        assertTrue(homeSwipeFor(0f, upward, up = Drawer, down = Search).dragsDrawer)
        // An open drawer is dragged whatever the settings, so it can still be pulled closed.
        assertTrue(homeSwipeFor(0.4f, downward, up = Search, down = Search).dragsDrawer)
        assertTrue(homeSwipeFor(1f, upward, up = None, down = Notifications).dragsDrawer)
        // Swiped down to, it doesn't rise against the finger: it opens when let go, like search.
        val down = homeSwipeFor(0f, downward, up = Search, down = Drawer)
        assertFalse(down.dragsDrawer)
        assertTrue(down.opensOnRelease(far, 0f, threshold))
        assertFalse(down.opensOnRelease(near, 0f, threshold))
    }

    @Test fun aSwipeThatOpensTheDrawerKeepsTheKeyboardDown() {
        assertTrue(homeSwipeFor(0f, upward, up = Drawer, down = Search).browses(0f))
        assertTrue(homeSwipeFor(0f, downward, up = Search, down = Drawer).browses(0f))
        // Dragging an open drawer keeps whatever the keyboard was doing, and search wants it up.
        assertFalse(homeSwipeFor(0.5f, upward, up = Drawer, down = Search).browses(0.5f))
        assertFalse(homeSwipeFor(0f, downward, up = Drawer, down = Search).browses(0f))
    }

    @Test fun withNothingForSwipingDownTheDrawerStillFollowsASwipeThatTurnsUp() {
        val turnsUp = homeSwipeFor(0f, downward, up = Drawer, down = None)
        assertEquals(HomeSwipe(Drawer, -1f), turnsUp)
        assertTrue(turnsUp.dragsDrawer)
        // Nothing follows the finger the other way round, so a swipe up set to nothing does nothing.
        assertEquals(None, homeSwipeFor(0f, upward, up = None, down = Drawer).action)
        assertEquals(None, homeSwipeFor(0f, upward, up = None, down = Search).action)
        assertEquals(None, homeSwipeFor(0f, downward, up = Search, down = None).action)
        assertEquals(None, homeSwipeFor(0f, upward, up = None, down = None).action)
    }

    @Test fun searchOpensOnceTheSwipeGoesFarOrFastEnoughItsWay() {
        val down = homeSwipeFor(0f, downward, up = Drawer, down = Search)
        assertTrue(down.opensOnRelease(far, 0f, threshold))
        assertTrue(down.opensOnRelease(near, flingDown, threshold))
        assertFalse(down.opensOnRelease(near, 300f, threshold))
        // Turned back: it ended behind where it started, or was let go of moving back up.
        assertFalse(down.opensOnRelease(-5f, flingDown, threshold))
        assertFalse(down.opensOnRelease(near, flingUp, threshold))
        // Set for swiping up, the fling that counts is upwards.
        val up = homeSwipeFor(0f, upward, up = Search, down = Drawer)
        assertTrue(up.opensOnRelease(near, flingUp, threshold))
        assertFalse(up.opensOnRelease(near, flingDown, threshold))
    }

    @Test fun distanceIsMeasuredTheSwipesWay() {
        assertEquals(12f, HomeSwipe(Search, 1f).along(downward), 0f)
        assertEquals(12f, HomeSwipe(Search, -1f).along(upward), 0f)
        assertEquals(-12f, HomeSwipe(Search, 1f).along(upward), 0f)
    }
}
