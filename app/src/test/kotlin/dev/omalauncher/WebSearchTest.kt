package dev.omalauncher

import dev.omalauncher.ui.BarSearch.Companion.ASK
import dev.omalauncher.ui.BarSearch.Companion.WEB
import dev.omalauncher.ui.barHighlight
import dev.omalauncher.ui.barStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchTest {
    @Test fun theWordsGoInTheAddressEncoded() {
        assertEquals("https://www.google.com/search?q=planes+over+riyadh", webSearchUrl(null, "  planes over\n riyadh "))
        assertEquals("https://duckduckgo.com/?q=c%2B%2B+%26+rust", webSearchUrl("https://duckduckgo.com/?q=%s", "c++ & rust"))
    }

    @Test fun onlyAWebAddressWithAPlaceForTheWordsIsASearch() {
        assertTrue(isWebSearch("https://search.example.org/search?q=%s"))
        assertFalse(isWebSearch("https://search.example.org/search?q="))
        assertFalse(isWebSearch("intent://%s"))
    }

    @Test fun searchesAreCalledByNameOrSite() {
        assertEquals("google", webSearchName(null))
        assertEquals("brave", webSearchName("https://search.brave.com/search?q=%s"))
        assertEquals("searx.example.org", webSearchName("https://www.searx.example.org/search?q=%s"))
    }

    @Test fun enterAsksTheAgentOrSearchesTheWebWithoutOne() {
        assertEquals(0, barHighlight(null, hits = 3, opensApp = true, canAsk = true))
        assertEquals(ASK, barHighlight(null, hits = 3, opensApp = false, canAsk = true))
        assertEquals(WEB, barHighlight(null, hits = 3, opensApp = false, canAsk = false))
        assertEquals(WEB, barHighlight(null, hits = 0, opensApp = true, canAsk = false))
        // A row picked with the arrows stays picked; an app picked past the end of fewer hits is the last.
        assertEquals(WEB, barHighlight(WEB, hits = 3, opensApp = true, canAsk = true))
        assertEquals(2, barHighlight(7, hits = 3, opensApp = false, canAsk = true))
        assertEquals(ASK, barHighlight(7, hits = 0, opensApp = false, canAsk = true))
    }

    @Test fun theArrowsGoAskThenWebThenTheApps() {
        assertEquals(WEB, barStep(ASK, 1, hits = 2))
        assertEquals(0, barStep(WEB, 1, hits = 2))
        assertEquals(1, barStep(0, 1, hits = 2))
        assertEquals(1, barStep(1, 1, hits = 2))
        assertEquals(WEB, barStep(0, -1, hits = 2))
        assertEquals(ASK, barStep(WEB, -1, hits = 2))
        assertEquals(ASK, barStep(ASK, -1, hits = 2))
        assertEquals(WEB, barStep(ASK, 5, hits = 0))
    }
}
