package dev.omalauncher

import android.content.pm.ApplicationInfo
import dev.omalauncher.SmartCategories.Category
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

    @Test fun smartLaunchersSixAndTwentyMore() {
        assertEquals(listOf("contacts", "internet", "games", "media", "utilities", "settings"), SmartCategories.NAMES)
        assertEquals(20, SmartCategories.EXTRAS.size)
        // Its icons by its own names: phone, utility, camera, theming, iot.
        val icons = SmartCategories.icons("ginlemon.flowerfree")
        assertEquals("res:ginlemon.flowerfree/cat_phone", icons["contacts"])
        assertEquals("res:ginlemon.flowerfree/cat_utility", icons["utilities"])
        assertEquals("res:ginlemon.flowerfree/cat_iot", icons["smart home"])
        assertEquals("res:ginlemon.flowerfree/cat_music", icons["music"])
        assertEquals("res:ginlemon.flowerfree/cat_folder", icons["group"])
    }

    @Test fun whatAnAppHandlesSaysItsCategories() {
        assertEquals(Category("contacts"), of("com.samsung.android.dialer", "Phone", roles = arrayOf(Role.DIALER)))
        assertEquals(Category("contacts", "social"), of("org.thoughtcrime.securesms", "Signal", roles = arrayOf(Role.SMS)))
        assertEquals(Category("internet"), of("net.mullvad.mullvadvpn", "Mullvad VPN", roles = arrayOf(Role.VPN)))
        assertEquals(Category("media", "entertainment"), of("org.jellyfin.mobile", "Jellyfin", roles = arrayOf(Role.VIDEO)))
        assertEquals(Category("media", "photography"), of("com.sec.android.app.camera", "Camera", roles = arrayOf(Role.CAMERA)))
        assertEquals(Category("utilities", "navigation"), of("app.organicmaps", "Organic Maps", roles = arrayOf(Role.MAPS)))
        assertEquals(Category("settings"), of("com.looker.droidify", "Droid-ify", roles = arrayOf(Role.STORE)))
        assertEquals(Category("settings", "personalization"), of("com.mahmoudzadah.app.glassifypro", "Glassify", roles = arrayOf(Role.ICON_PACK)))
    }

    @Test fun aBrowserDeclaredSocialIsStillABrowser() {
        // Chrome and Brave declare "social"; opening any address says more.
        assertEquals(Category("internet"), of("com.android.chrome", "Chrome", ApplicationInfo.CATEGORY_SOCIAL, roles = arrayOf(Role.BROWSER)))
        assertEquals(Category("contacts", "social"), of("com.reddit.frontpage", "Reddit", ApplicationInfo.CATEGORY_SOCIAL))
    }

    @Test fun gamesWinOverEverything() {
        assertEquals(Category("games"), of("com.example.chat", "Chat Quest", ApplicationInfo.CATEGORY_GAME, roles = arrayOf(Role.MESSAGING)))
        assertEquals(Category("games"), of("com.example.old", "Old Game", game = true))
    }

    @Test fun theDeclaredKindThenWordsInThePackageOrName() {
        assertEquals(Category("media", "photography"), of("com.example.pics", "Pics", ApplicationInfo.CATEGORY_IMAGE))
        assertEquals(Category("internet", "reading"), of("com.example.daily", "Daily", ApplicationInfo.CATEGORY_NEWS))
        assertEquals(Category("media", "entertainment"), of("is.xyz.mpv", "mpv"))
        assertEquals(Category("contacts", "social"), of("com.whatsapp", "WhatsApp"))
        // WhatsApp Business is business before it's WhatsApp.
        assertEquals(Category("utilities", "business"), of("com.whatsapp.w4b", "WA Business"))
        assertEquals(Category("settings", "personalization"), of("com.samsung.android.goodlock", "Good Lock"))
        assertEquals(Category("utilities", "finance"), of("sa.alinma.mobile", "alinma"))
        assertEquals(Category("utilities", "reading"), of("com.clearpdf.reader", "ClearPDF"))
        assertEquals(Category("games"), of("com.supercell.games.clash", "Clash"))
        // ChatGPT isn't a chat with people; productivity is all it declares.
        assertEquals(Category("utilities", "productivity"), of("com.openai.chatgpt", "ChatGPT", ApplicationInfo.CATEGORY_PRODUCTIVITY))
    }

    @Test fun nothingToGoOnIsUtilities() {
        assertEquals(Category("utilities"), of("com.sec.android.app.popupcalculator", "Calculator"))
    }
}
