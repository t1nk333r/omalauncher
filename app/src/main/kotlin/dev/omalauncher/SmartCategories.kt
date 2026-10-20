package dev.omalauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager

/**
 * Phone build: Smart Launcher's six app-page categories, which the drawer's pages sort Android apps into while style's
 * drawer.categories is "smart". Smart Launcher looks apps up online; these are guessed on the phone, from what an app
 * handles (calls, links, music...), the kind its developer declared, then words in its package and name.
 */
object SmartCategories {
    const val COMMUNICATION = "communication"
    const val INTERNET = "internet"
    const val GAMES = "games"
    const val MEDIA = "media"
    const val UTILITIES = "utilities"
    const val SETTINGS = "settings"

    /** In Smart Launcher's order, the order of the drawer's pages. */
    val NAMES = listOf(COMMUNICATION, INTERNET, GAMES, MEDIA, UTILITIES, SETTINGS)

    /** What an app does, as Android tells from the intents it handles. */
    enum class Role { DIALER, SMS, EMAIL, CONTACTS, MESSAGING, BROWSER, VPN, MUSIC, VIDEO, GALLERY, CAMERA, STORE, HOME, ICON_PACK, KEYBOARD, WALLPAPER, SYSTEM_SETTINGS }

    private val COMMUNICATING = setOf(Role.DIALER, Role.SMS, Role.EMAIL, Role.CONTACTS, Role.MESSAGING)
    private val ONLINE = setOf(Role.BROWSER, Role.VPN)
    private val PLAYING = setOf(Role.MUSIC, Role.VIDEO, Role.GALLERY, Role.CAMERA)
    private val SETTING_UP = setOf(Role.STORE, Role.HOME, Role.ICON_PACK, Role.KEYBOARD, Role.WALLPAPER, Role.SYSTEM_SETTINGS)

    /**
     * Words in an app's package or name, in the order they're tried: the first category with one wins. Short or common
     * words are left out, as they'd match too much ("tv" is in "activity", "line" in "online", "chat" in ChatGPT).
     */
    private val WORDS =
        listOf(
            SETTINGS to listOf("settings", "launcher", "iconpack", "icon pack", "wallpaper", "theme", "goodlock", "keyboard"),
            COMMUNICATION to
                listOf(
                    "whatsapp", "telegram", "signal", "securesms", "messag", "messeng", "sms", "mail", "wechat", "discord",
                    "snapchat", "instagram", "facebook", "twitter", "mastodon", "tusky", "matrix", "slack", "skype",
                    "viber", "dialer", "contacts",
                ),
            INTERNET to listOf("browser", "chrome", "firefox", "fennec", "iceraven", "brave", "cromite", "vivaldi", "opera", "duckduckgo", "torproject", "vpn", "proxy", "rss", "feed", "news", "wikipedia"),
            MEDIA to
                listOf(
                    "music", "player", "audio", "radio", "podcast", "video", "movie", "camera", "gallery", "photo",
                    "spotify", "youtube", "newpipe", "jellyfin", "findroid", "plex", "kodi", "vlc", "mpv", "netflix",
                    "twitch", "soundcloud", "recorder",
                ),
            GAMES to listOf("game"),
        )

    /**
     * [pkg]'s category, from what it handles ([roles]), its declared [category] (ApplicationInfo.category) or [game]
     * flag, and its package and [label]; utilities when nothing says otherwise.
     */
    fun of(
        pkg: String,
        label: String,
        category: Int,
        game: Boolean,
        roles: Set<Role>,
    ): String {
        if (game || category == ApplicationInfo.CATEGORY_GAME) return GAMES
        if (pkg == "com.android.settings" || roles.any { it in SETTING_UP }) return SETTINGS
        if (roles.any { it in COMMUNICATING }) return COMMUNICATION
        if (roles.any { it in ONLINE }) return INTERNET
        if (roles.any { it in PLAYING }) return MEDIA
        // What the developer said, where it names one of these; productivity and maps are utilities anyway.
        when (category) {
            ApplicationInfo.CATEGORY_SOCIAL -> return COMMUNICATION
            ApplicationInfo.CATEGORY_NEWS -> return INTERNET
            ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> return MEDIA
            ApplicationInfo.CATEGORY_ACCESSIBILITY -> return SETTINGS
        }
        val text = "${pkg.lowercase()} ${label.lowercase()}"
        return WORDS.firstOrNull { (_, words) -> words.any { it in text } }?.first ?: UTILITIES
    }

    /**
     * What each package on the phone handles, by package name. Asked of Android when the apps are listed: the launcher's
     * <queries> already make every app in the drawer visible, which is all this needs. Blocking.
     */
    fun roles(context: Context): Map<String, Set<Role>> {
        val pm = context.packageManager
        val out = HashMap<String, MutableSet<Role>>()

        fun add(
            role: Role,
            packages: Collection<String>,
        ) = packages.forEach { out.getOrPut(it) { HashSet() } += role }

        fun handling(intent: Intent): Set<String> =
            runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_ALL) }
                .onFailure { Log.w(TAG, "cannot ask who handles $intent", it) }
                .getOrDefault(emptyList())
                .mapTo(HashSet()) { it.activityInfo.packageName }

        fun viewing(type: String) = handling(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/probe"), type))

        fun main(category: String) = handling(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category))
        // File managers and viewers open anything: a made-up type tells them apart from media apps.
        val anything = viewing("application/x-omalauncher-probe")
        add(Role.DIALER, handling(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1"))))
        add(Role.SMS, handling(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:1"))))
        add(Role.EMAIL, handling(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))))
        add(Role.CONTACTS, main(Intent.CATEGORY_APP_CONTACTS))
        add(Role.MESSAGING, main(Intent.CATEGORY_APP_MESSAGING))
        // An address with no site: apps that open one site's links (app links) don't match, browsers do.
        add(Role.BROWSER, handling(Intent(Intent.ACTION_VIEW, Uri.parse("http://")).addCategory(Intent.CATEGORY_BROWSABLE)) + main(Intent.CATEGORY_APP_BROWSER))
        add(Role.VPN, runCatching { pm.queryIntentServices(Intent("android.net.VpnService"), PackageManager.MATCH_ALL) }.getOrDefault(emptyList()).map { it.serviceInfo.packageName })
        add(Role.MUSIC, viewing("audio/mpeg") - anything + main(Intent.CATEGORY_APP_MUSIC))
        add(Role.VIDEO, viewing("video/mp4") - anything)
        add(Role.GALLERY, viewing("image/jpeg") - anything + main(Intent.CATEGORY_APP_GALLERY))
        add(Role.CAMERA, handling(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)))
        add(Role.STORE, handling(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=dev.omalauncher"))) + main(Intent.CATEGORY_APP_MARKET))
        add(Role.HOME, handling(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)))
        add(Role.ICON_PACK, IconPackStore.PACK_INTENTS.flatMap(::handling))
        add(Role.WALLPAPER, handling(Intent(Intent.ACTION_SET_WALLPAPER)))
        add(Role.KEYBOARD, runCatching { context.getSystemService(InputMethodManager::class.java).inputMethodList.map { it.packageName } }.getOrDefault(emptyList()))
        // The phone's Settings app, whoever made it.
        add(Role.SYSTEM_SETTINGS, handling(Intent(Settings.ACTION_SETTINGS)))
        return out.mapValues { it.value.toSet() }
    }

    private const val TAG = "smart-categories"
}
