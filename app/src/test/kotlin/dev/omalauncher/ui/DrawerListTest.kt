package dev.omalauncher.ui

import android.content.pm.ApplicationInfo
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

    @Test fun aKindIsNamedShortForItsGroup() {
        assertEquals("social", categoryName(ApplicationInfo.CATEGORY_SOCIAL))
        assertEquals("games", categoryName(ApplicationInfo.CATEGORY_GAME))
        assertEquals(null, categoryName(ApplicationInfo.CATEGORY_UNDEFINED))
    }

    private val apps = listOf("a:wa" to "social", "a:sig" to "social", "a:maps" to "maps", "a:osm" to "maps", "a:yt" to "video", "l:btop" to null)

    @Test fun groupingSimilarPutsAnAppWithTheOthersOfItsKind() {
        assertEquals(mapOf("social" to listOf("a:wa", "a:sig")), similarGroups(listOf("a:wa" to "social"), apps) { null })
        // Alone in its kind, or of none: nothing to group it with.
        assertEquals(emptyMap<String, List<String>>(), similarGroups(listOf("a:yt" to "video", "l:btop" to null), apps) { null })
    }

    @Test fun inAGroupAlreadyTheOthersJoinThatGroup() {
        val groupOf = mapOf("a:maps" to "travel")::get
        assertEquals(mapOf("travel" to listOf("a:osm")), similarGroups(listOf("a:maps" to "maps"), apps, groupOf))
        // Apps in another group stay there.
        assertEquals(mapOf("social" to listOf("a:wa", "a:sig")), similarGroups(apps, apps, mapOf("a:osm" to "x", "a:maps" to "x")::get).filterKeys { it == "social" })
    }

    @Test fun everyKindTwoAppsShareBecomesAGroupOnce() {
        // As the drawer's [group similar] asks for all of them: each app in one group at most.
        assertEquals(mapOf("social" to listOf("a:wa", "a:sig"), "maps" to listOf("a:maps", "a:osm")), similarGroups(apps, apps) { null })
    }
}
