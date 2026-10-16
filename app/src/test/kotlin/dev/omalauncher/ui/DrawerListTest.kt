package dev.omalauncher.ui

import dev.omalauncher.linux.LinuxApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerListTest {
    private fun app(name: String) = AppHit.Linux(LinuxApp(name, name, terminal = false, id = name, wmClass = null))

    private val btop = app("btop")
    private val alacritty = app("Alacritty")
    private val cava = app("cava")
    private val name = { hit: AppHit -> hit.label }

    @Test fun sortsByNameIgnoringCase() {
        assertEquals(listOf(alacritty, btop, cava), sortApps(listOf(cava, btop, alacritty), "name", name) { 0.0 })
    }

    @Test fun sortsByUseThenName() {
        val score = mapOf(cava.key to 3.0, btop.key to 1.0)
        assertEquals(listOf(cava, btop, alacritty), sortApps(listOf(alacritty, btop, cava), "used", name) { score[it.key] ?: 0.0 })
    }

    @Test fun appsThatDontSayWhenTheyWereInstalledSortByNameUnderNewest() {
        // Linux apps don't say: as installed at 0, after any that do, by name.
        assertEquals(listOf(alacritty, btop, cava), sortApps(listOf(cava, alacritty, btop), "new", name) { 0.0 })
    }

    @Test fun newIsInstalledLatelyAndNeverOpened() {
        val now = 10L * NEW_FOR_MS
        assertTrue(isNew(installed = now - 1000, used = 0.0, now = now))
        assertFalse(isNew(installed = now - 1000, used = 0.5, now = now))
        assertFalse(isNew(installed = now - NEW_FOR_MS - 1, used = 0.0, now = now))
        // Unknown install time: never new.
        assertFalse(isNew(installed = 0, used = 0.0, now = now))
    }

    @Test fun aSectionIsItsTitleAndRows() {
        val out = mutableListOf<DrawerEntry>()
        out.section("linux", "linux", listOf(alacritty, btop, cava), columns = 2)
        assertEquals(listOf("h:linux", "linux:0", "linux:1"), out.map { it.key })
        // Untitled, as one list: just the rows; no apps and no actions: nothing.
        val plain = mutableListOf<DrawerEntry>()
        plain.section("all", "apps", listOf(btop), columns = 4, titled = false)
        plain.section("none", "none", emptyList(), columns = 4)
        assertEquals(listOf("all:0"), plain.map { it.key })
    }

    @Test fun theRailJumpsToTheFirstRowWithALetter() {
        val out = mutableListOf<DrawerEntry>()
        out.section("most", "most used", listOf(cava), columns = 3)
        out.section("all", "apps", listOf(alacritty, btop, cava, app("7zip")), columns = 2, titled = false)
        // Rows: most:0 (cava), all:0 (Alacritty, btop), all:1 (cava, 7zip). The most-used row isn't counted.
        val index = letterIndex(out, name, from = 2)
        assertEquals(mapOf('A' to 2, 'B' to 2, 'C' to 3, '#' to 3), index)
        assertEquals(listOf('A', 'B', 'C', '#'), index.keys.toList())
    }
}
