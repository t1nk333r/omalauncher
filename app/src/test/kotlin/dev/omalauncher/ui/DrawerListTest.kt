package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What android.drawer works out for drawer.owl; the drawer's own order, sections and rail are DrawerOwlTest's. */
class DrawerListTest {
    @Test fun newIsInstalledLatelyAndNeverOpened() {
        val now = 10L * NEW_FOR_MS
        assertTrue(isNew(installed = now - 1000, used = 0.0, now = now))
        assertFalse(isNew(installed = now - 1000, used = 0.5, now = now))
        assertFalse(isNew(installed = now - NEW_FOR_MS - 1, used = 0.0, now = now))
        // Unknown install time: never new.
        assertFalse(isNew(installed = 0, used = 0.0, now = now))
    }

    @Test fun theRailFilesAnAppUnderItsFirstLetter() {
        assertEquals('A', railLetter("alacritty"))
        assertEquals('م', railLetter("مسار"))
        assertEquals('#', railLetter("7zip"))
        assertEquals('#', railLetter(""))
    }
}
