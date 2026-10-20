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
 * Phone build: Smart Launcher's app-page categories, which the drawer's pages sort Android apps into while style's
 * drawer.categories is "smart". As Smart Launcher 6 has them: six to start with, and twenty more to add. Its server
 * gives each app a base category, one of the six, and a finer extra one; an app shows under its extra category once
 * that's added, else under its base. Here both are guessed on the phone, nothing sent anywhere: from what an app
 * handles (calls, links, music...), the kind its developer declared, then words in its package and name.
 */
object SmartCategories {
    const val CONTACTS = "contacts"
    const val INTERNET = "internet"
    const val GAMES = "games"
    const val MEDIA = "media"
    const val UTILITIES = "utilities"
    const val SETTINGS = "settings"

    /** The six, in Smart Launcher's order, the order of the drawer's pages; by the names it shows. */
    val NAMES = listOf(CONTACTS, INTERNET, GAMES, MEDIA, UTILITIES, SETTINGS)

    /** The categories Smart Launcher adds on request, in its order, by the names it shows. */
    val EXTRAS =
        listOf(
            "social", "photography", "navigation", "reading", "music", "entertainment", "productivity", "shopping",
            "personalization", "finance", "business", "education", "travel", "lifestyle", "fitness", "sport", "weather",
            "smart home", "medical", "wearable",
        )

    /** Smart Launcher's own name for those it names differently, whose drawable cat_<name> is its icon; the rest are the same. */
    private val SMART_IDS =
        mapOf(
            CONTACTS to "phone", UTILITIES to "utility", "photography" to "camera", "entertainment" to "entertain",
            "personalization" to "theming", "smart home" to "iot", "group" to "folder",
            // The kinds Android apps declare, drawn with the nearest of Smart Launcher's.
            "audio" to "music", "video" to "entertain", "photos" to "camera", "maps" to "navigation", "other" to "utility",
        )

    /** Where an app goes: one of [NAMES], and one of [EXTRAS] or none. */
    data class Category(
        val base: String,
        val extra: String? = null,
    )

    /** What an app does, as Android tells from the intents it handles. */
    enum class Role { DIALER, SMS, EMAIL, CONTACTS, MESSAGING, BROWSER, VPN, MUSIC, VIDEO, GALLERY, CAMERA, MAPS, STORE, HOME, ICON_PACK, KEYBOARD, WALLPAPER, SYSTEM_SETTINGS }

    /** What each role makes an app, the first an app has winning; Settings' own first, as a launcher is one too. */
    private val ROLES =
        listOf(
            Role.SYSTEM_SETTINGS to Category(SETTINGS),
            Role.HOME to Category(SETTINGS, "personalization"),
            Role.ICON_PACK to Category(SETTINGS, "personalization"),
            Role.WALLPAPER to Category(SETTINGS, "personalization"),
            Role.KEYBOARD to Category(SETTINGS, "personalization"),
            Role.STORE to Category(SETTINGS),
            Role.BROWSER to Category(INTERNET),
            Role.VPN to Category(INTERNET),
            Role.SMS to Category(CONTACTS, "social"),
            Role.MESSAGING to Category(CONTACTS, "social"),
            Role.DIALER to Category(CONTACTS),
            Role.CONTACTS to Category(CONTACTS),
            Role.EMAIL to Category(CONTACTS, "productivity"),
            Role.CAMERA to Category(MEDIA, "photography"),
            Role.GALLERY to Category(MEDIA, "photography"),
            Role.MUSIC to Category(MEDIA, "music"),
            Role.VIDEO to Category(MEDIA, "entertainment"),
            Role.MAPS to Category(UTILITIES, "navigation"),
        )

    /**
     * Words in an app's package or name, in the order they're tried: the first that matches says where it goes. Short
     * or common words are left out, as they'd match too much ("tv" is in "activity", "chat" in ChatGPT, "read" in Threads).
     */
    private val WORDS =
        listOf(
            Category(UTILITIES, "business") to listOf("w4b", "business", "slack", "teams", "zoom", "linkedin"),
            Category(CONTACTS, "social") to
                listOf(
                    "whatsapp", "telegram", "signal", "securesms", "instagram", "facebook", "twitter", "threads", "snapchat",
                    "discord", "reddit", "redreader", "mastodon", "tusky", "bluesky", "lemmy", "tiktok", "messeng", "viber",
                    "wechat", "skype", "matrix",
                ),
            Category(MEDIA, "photography") to listOf("camera", "photo", "gallery", "immich", "snapseed", "lightroom", "picsart"),
            Category(UTILITIES, "navigation") to listOf("maps", "navigation", "gps", "waze", "osmand", "organicmaps", "hudhud"),
            Category(INTERNET, "reading") to listOf("rss", "feed", "news", "wikipedia"),
            Category(UTILITIES, "reading") to listOf("reader", "book", "kindle", "manga", "comic", "pdf", "quran"),
            Category(MEDIA, "music") to listOf("music", "spotify", "podcast", "radio", "soundcloud", "deezer", "tidal", "shazam"),
            Category(MEDIA, "entertainment") to listOf("video", "youtube", "netflix", "twitch", "jellyfin", "findroid", "plex", "kodi", "movie", "anime", "player", "vlc", "mpv"),
            Category(INTERNET, "shopping") to listOf("shop", "amazon", "ebay", "aliexpress", "temu", "shein", "noon", "namshi", "jarir", "ikea"),
            Category(SETTINGS, "personalization") to listOf("theme", "wallpaper", "iconpack", "icon pack", "kwgt", "klwp", "kustom", "widget", "goodlock", "launcher", "font"),
            Category(UTILITIES, "finance") to listOf("bank", "wallet", "finance", "money", "crypto", "coin", "alinma", "rajhi", "barq", "stcpay", "paypal", "revolut", "binance", "invest", "stock"),
            Category(UTILITIES, "education") to listOf("learn", "duolingo", "school", "course", "dictionary", "translat", "anki", "quizlet", "khan"),
            Category(UTILITIES, "travel") to listOf("travel", "booking", "airbnb", "flight", "airline", "trip", "uber", "careem", "taxi", "hotel", "saudia", "flynas", "almosafer", "wego"),
            Category(UTILITIES, "lifestyle") to listOf("food", "recipe", "delivery", "talabat", "hungerstation", "jahez", "keeta", "dating", "athkar", "azan", "adhan", "prayer", "muslim", "qibla"),
            Category(UTILITIES, "fitness") to listOf("fitness", "workout", "strava", "steps", "shealth", "health"),
            Category(INTERNET, "sport") to listOf("sport", "football", "soccer", "espn", "fifa", "livescore", "fotmob", "sofascore"),
            Category(UTILITIES, "weather") to listOf("weather", "forecast"),
            Category(UTILITIES, "smart home") to listOf("homeassistant", "home assistant", "smartthings", "philips.lighting", "tuya", "smartlife", "alexa", "chromecast", "iot"),
            Category(UTILITIES, "medical") to listOf("medical", "doctor", "clinic", "hospital", "pharmacy", "sehhaty", "tawakkalna", "mawid", "nahdi"),
            Category(SETTINGS, "wearable") to listOf("wearable", "watchmanager", "gadgetbridge", "buds", "watch"),
            Category(UTILITIES, "productivity") to listOf("notes", "office", "docs", "sheets", "calendar", "todo", "task", "drive", "notion", "obsidian", "markor", "joplin", "cloud"),
            // Where only the base is clear.
            Category(SETTINGS) to listOf("settings", "keyboard", "samsungapps", "appstore", "app store"),
            Category(CONTACTS) to listOf("dialer", "contacts", "messag", "sms", "mail"),
            Category(INTERNET) to listOf("browser", "chrome", "firefox", "fennec", "iceraven", "brave", "cromite", "vivaldi", "opera", "duckduckgo", "torproject", "vpn", "proxy", "download"),
            Category(GAMES) to listOf("game"),
        )

    /**
     * Where [pkg] goes, from what it handles ([roles]), its declared [category] (ApplicationInfo.category) or [game]
     * flag, and its package and [label]; utilities, with no extra category, when nothing says.
     */
    fun of(
        pkg: String,
        label: String,
        category: Int,
        game: Boolean,
        roles: Set<Role>,
    ): Category {
        if (game || category == ApplicationInfo.CATEGORY_GAME) return Category(GAMES)
        if (pkg == "com.android.settings") return Category(SETTINGS)
        ROLES.firstOrNull { it.first in roles }?.let { return it.second }
        // What the developer said, where it names a kind outright; productivity is weaker than a word in the name.
        when (category) {
            ApplicationInfo.CATEGORY_SOCIAL -> return Category(CONTACTS, "social")
            ApplicationInfo.CATEGORY_AUDIO -> return Category(MEDIA, "music")
            ApplicationInfo.CATEGORY_VIDEO -> return Category(MEDIA, "entertainment")
            ApplicationInfo.CATEGORY_IMAGE -> return Category(MEDIA, "photography")
            ApplicationInfo.CATEGORY_NEWS -> return Category(INTERNET, "reading")
            ApplicationInfo.CATEGORY_MAPS -> return Category(UTILITIES, "navigation")
        }
        val text = "${pkg.lowercase()} ${label.lowercase()}"
        WORDS.firstOrNull { (_, words) -> words.any { it in text } }?.let { return it.first }
        return when (category) {
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> Category(UTILITIES, "productivity")
            ApplicationInfo.CATEGORY_ACCESSIBILITY -> Category(SETTINGS)
            else -> Category(UTILITIES)
        }
    }

    /** The Smart Launcher on the phone with its category icons, by package; null without one. Blocking. */
    fun iconPackage(context: Context): String? =
        PACKAGES.firstOrNull { pkg ->
            runCatching {
                val res = context.packageManager.getResourcesForApplication(pkg)
                @Suppress("DiscouragedApi")
                res.getIdentifier("cat_phone", "drawable", pkg) != 0
            }.getOrDefault(false)
        }

    /**
     * Each category's icon as owl's `res:` draws it, from the Smart Launcher in [pkg]: the six, the extras, the kinds
     * Android apps declare, and "group" for your groups.
     */
    fun icons(pkg: String): Map<String, String> =
        (NAMES + EXTRAS + ANDROID_KINDS + "group").associateWith { "res:$pkg/cat_" + (SMART_IDS[it] ?: it) }

    /** The kinds the drawer's "android" categories list, as icons() names them. */
    private val ANDROID_KINDS = listOf("games", "audio", "video", "photos", "social", "news", "maps", "productivity", "other")

    /** Smart Launcher's packages: 6's, then older ones'. */
    private val PACKAGES = listOf("ginlemon.flowerfree", "ginlemon.flowerpro", "ginlemon.flower")

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
        add(Role.MAPS, handling(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=probe"))))
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
