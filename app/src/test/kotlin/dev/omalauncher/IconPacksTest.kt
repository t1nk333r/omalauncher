package dev.omalauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IconPacksTest {
    private fun filter(vararg items: Pair<String?, String?>) =
        AppFilter().apply {
            for ((component, drawable) in items) add("item") { mapOf("component" to component, "drawable" to drawable)[it] }
        }

    @Test fun componentsReadInEitherSpelling() {
        assertEquals("com.a/com.a.Main", AppFilter.componentKey("ComponentInfo{com.a/com.a.Main}"))
        assertEquals("com.a/com.a.Main", AppFilter.componentKey("ComponentInfo{com.a/.Main}"))
        assertEquals("com.a/org.b.Start", AppFilter.componentKey(" com.a/org.b.Start "))
        // Packs list a few things that aren't activities: those map nothing.
        assertNull(AppFilter.componentKey(":LAUNCHER_ACTION_APP_DRAWER"))
        assertNull(AppFilter.componentKey("ComponentInfo{com.a/}"))
    }

    @Test fun anAppGetsTheDrawableItsActivityMapsTo() {
        val f = filter("ComponentInfo{com.a/.Main}" to "a_icon", "ComponentInfo{com.b/com.b.Home}" to "b_icon")
        assertEquals("a_icon", f.drawableFor("com.a", "com.a.Main"))
        assertEquals("b_icon", f.drawableFor("com.b", "com.b.Home"))
        // Another activity of the same app isn't the one the pack drew.
        assertNull(f.drawableFor("com.a", "com.a.Other"))
    }

    @Test fun theFirstOfTwoEntriesWinsAndBrokenOnesAreLeft() {
        val f = filter("ComponentInfo{com.a/.Main}" to "first", "ComponentInfo{com.a/com.a.Main}" to "second", "ComponentInfo{com.c/.Main}" to " ", null to "x")
        assertEquals("first", f.drawableFor("com.a", "com.a.Main"))
        assertNull(f.drawableFor("com.c", "com.c.Main"))
        assertEquals(listOf("first"), f.drawables())
    }

    @Test fun otherTagsMapNothing() {
        val f = AppFilter()
        f.add("iconback") { if (it == "img1") "back" else null }
        f.add("calendar") { mapOf("component" to "ComponentInfo{com.a/.Cal}", "prefix" to "cal_")[it] }
        assertTrue(f.isEmpty)
    }

    @Test fun searchMatchesEveryWordInTheName() {
        val icons = listOf("google_chrome", "chromium", "firefox", "google_maps")
        assertEquals(icons, searchPackIcons(icons, " "))
        assertEquals(listOf("google_chrome", "chromium"), searchPackIcons(icons, "chrom"))
        assertEquals(listOf("google_chrome"), searchPackIcons(icons, "Google Chrome"))
        assertEquals(listOf("google_maps"), searchPackIcons(icons, "maps_google"))
    }

    @Test fun suggestionsPutTheMappedIconFirstThenTheLabelsClosestSpelling() {
        val icons = listOf("firefox_nightly", "firefox", "fire_tv", "focus")
        assertEquals(listOf("focus", "firefox", "firefox_nightly"), suggestPackIcons(icons, "Firefox", mapped = "focus"))
        assertEquals(listOf("firefox"), suggestPackIcons(icons, "Firefox", mapped = "firefox", max = 1))
        // A one-letter name would match nearly everything.
        assertEquals(emptyList<String>(), suggestPackIcons(icons, "X", mapped = null))
    }
}
