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

    @Test fun everyMappedDrawableIsListedOnceByTheAppItStandsFor() {
        // Glassify's names are codes, its appfilter.xml in no order: listed by the first app each stands for.
        val f = filter("ComponentInfo{org.mozilla.firefox/.App}" to "qmdl44ho", "ComponentInfo{com.whatsapp/.Main}" to "a9x2", "ComponentInfo{org.mozilla.firefox_beta/.App}" to "qmdl44ho")
        assertEquals(listOf("a9x2", "qmdl44ho"), f.drawables())
        assertEquals(listOf("org.mozilla.firefox/org.mozilla.firefox.App", "org.mozilla.firefox_beta/org.mozilla.firefox_beta.App"), f.componentsOf("qmdl44ho"))
        assertEquals(emptyList<String>(), f.componentsOf("nothing"))
    }

    @Test fun searchFindsIconsNamedWithCodesByTheirApps() {
        val about = mapOf("qmdl44ho" to "org.mozilla.firefox/org.mozilla.firefox.App Firefox", "j2yrd61r" to "org.mozilla.firefox.vpn/org.mozilla.firefox.vpn.qt.VPNActivity", "k1" to "org.thoughtcrime.securesms/.Main Signal")
        val icons = about.keys.toList()
        assertEquals(listOf("qmdl44ho", "j2yrd61r"), searchPackIcons(icons, "firefox") { about[it].orEmpty() })
        assertEquals(listOf("j2yrd61r"), searchPackIcons(icons, "Firefox VPN") { about[it].orEmpty() })
        // An installed app's name finds its icon though neither its package nor the drawable spells it.
        assertEquals(listOf("k1"), searchPackIcons(icons, "signal") { about[it].orEmpty() })
        assertEquals(listOf("k1"), searchPackIcons(icons, "k1") { about[it].orEmpty() })
    }

    @Test fun suggestionsPutTheMappedIconFirstThenTheLabelsClosestSpelling() {
        val icons = listOf("firefox_nightly", "firefox", "fire_tv", "focus")
        assertEquals(listOf("focus", "firefox", "firefox_nightly"), suggestPackIcons(icons, "Firefox", mapped = "focus"))
        assertEquals(listOf("firefox"), suggestPackIcons(icons, "Firefox", mapped = "firefox", max = 1))
        // A one-letter name would match nearly everything.
        assertEquals(emptyList<String>(), suggestPackIcons(icons, "X", mapped = null))
        // Codes for names: the apps each stands for spell it, the closest first.
        val about = mapOf("b2" to "org.mozilla.firefox_beta/org.mozilla.firefox_beta.App", "a1" to "org.mozilla.firefox/org.mozilla.firefox.App", "c3" to "com.whatsapp/com.whatsapp.Main")
        assertEquals(listOf("a1", "b2"), suggestPackIcons(about.keys.toList(), "Firefox", mapped = null) { about[it].orEmpty() })
    }
}
