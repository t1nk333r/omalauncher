package dev.omalauncher.ui

import dev.omalauncher.linux.LinuxApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrawerPickingTest {
    private fun app(id: String) = AppHit.Linux(LinuxApp(id, id, terminal = false, id = id, wmClass = null))

    private val foot = app("foot")
    private val btop = app("btop")
    private val nvim = app("nvim")
    private val apps = listOf(foot, btop, nvim)

    @Test fun hiddenAppsAreLeftOutUnlessShown() {
        val hidden = setOf(btop.key)
        assertEquals(listOf(foot, nvim), apps.shown(hidden, showHidden = false))
        assertEquals(apps, apps.shown(hidden, showHidden = true))
        assertEquals(apps, apps.shown(emptySet(), showHidden = false))
    }

    @Test fun tappingPicksAndUnpicks() {
        val one = setOf(foot.key)
        assertEquals(setOf(foot.key, btop.key), toggled(one, btop.key))
        assertEquals(setOf(btop.key), toggled(setOf(foot.key, btop.key), foot.key))
    }

    @Test fun unpickingTheLastAppStopsPicking() {
        assertNull(toggled(setOf(foot.key), foot.key))
    }
}
