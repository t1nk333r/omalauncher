package dev.omalauncher

import android.content.pm.ApplicationInfo
import dev.omalauncher.SmartCategories.Role
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartCategoriesTest {
    private fun of(
        pkg: String,
        label: String,
        category: Int = ApplicationInfo.CATEGORY_UNDEFINED,
        game: Boolean = false,
        vararg roles: Role,
    ) = SmartCategories.of(pkg, label, category, game, roles.toSet())

    @Test fun whatAnAppHandlesSaysItsCategory() {
        assertEquals("communication", of("com.samsung.android.dialer", "Phone", roles = arrayOf(Role.DIALER)))
        assertEquals("communication", of("org.thoughtcrime.securesms", "Signal", roles = arrayOf(Role.SMS)))
        assertEquals("internet", of("me.zhanghai.vpn", "FreeProxy", roles = arrayOf(Role.VPN)))
        assertEquals("media", of("org.jellyfin.mobile", "Jellyfin", roles = arrayOf(Role.VIDEO)))
        assertEquals("settings", of("com.looker.droidify", "Droid-ify", roles = arrayOf(Role.STORE)))
        assertEquals("settings", of("com.mahmoudzadah.app.glassifypro", "Glassify", roles = arrayOf(Role.ICON_PACK)))
    }

    @Test fun aBrowserDeclaredSocialIsStillABrowser() {
        // Chrome and Brave declare "social"; opening any address says more.
        assertEquals("internet", of("com.android.chrome", "Chrome", ApplicationInfo.CATEGORY_SOCIAL, roles = arrayOf(Role.BROWSER)))
        assertEquals("communication", of("com.reddit.frontpage", "Reddit", ApplicationInfo.CATEGORY_SOCIAL))
    }

    @Test fun gamesWinOverEverything() {
        assertEquals("games", of("com.example.chat", "Chat Quest", ApplicationInfo.CATEGORY_GAME, roles = arrayOf(Role.MESSAGING)))
        assertEquals("games", of("com.example.old", "Old Game", game = true))
    }

    @Test fun theDeclaredKindThenWordsInThePackageOrName() {
        assertEquals("media", of("com.example.pics", "Pics", ApplicationInfo.CATEGORY_IMAGE))
        assertEquals("internet", of("com.example.daily", "Daily", ApplicationInfo.CATEGORY_NEWS))
        assertEquals("media", of("is.xyz.mpv", "mpv"))
        assertEquals("communication", of("com.whatsapp", "WhatsApp"))
        assertEquals("settings", of("com.samsung.android.goodlock", "Good Lock"))
        assertEquals("games", of("com.supercell.games.clash", "Clash"))
        // ChatGPT isn't a chat with people.
        assertEquals("utilities", of("com.openai.chatgpt", "ChatGPT", ApplicationInfo.CATEGORY_PRODUCTIVITY))
    }

    @Test fun nothingToGoOnIsUtilities() {
        assertEquals("utilities", of("com.sec.android.app.popupcalculator", "Calculator"))
        assertEquals(listOf("communication", "internet", "games", "media", "utilities", "settings"), SmartCategories.NAMES)
    }
}
