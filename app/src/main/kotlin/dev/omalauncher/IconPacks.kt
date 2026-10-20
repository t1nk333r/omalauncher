package dev.omalauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/**
 * What an icon pack's appfilter.xml says: which of its drawables stands for which app. Packs name an app's launcher
 * activity as `ComponentInfo{package/class}`, the class sometimes short (`.Main`); keys here are always the full
 * `package/class`, as [ComponentName.flattenToString] spells it.
 */
class AppFilter {
    private val entries = HashMap<String, String>()

    /** The activities each drawable stands for, in the file's order: many packs name their drawables with codes. */
    private val mapped = LinkedHashMap<String, MutableList<String>>()

    /** Takes one of the file's tags: `<item component="…" drawable="…"/>` maps an app; the rest (calendar, iconback) is left. */
    fun add(
        tag: String,
        attribute: (String) -> String?,
    ) {
        if (tag != "item") return
        val component = attribute("component")?.let(::componentKey) ?: return
        val drawable = attribute("drawable")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        // A pack listing an activity twice means the first.
        if (entries.putIfAbsent(component, drawable) == null) mapped.getOrPut(drawable) { ArrayList(1) }.add(component)
    }

    /** The drawable for [packageName]'s activity [className], or null when the pack has none for it. */
    fun drawableFor(
        packageName: String,
        className: String,
    ): String? = entries["$packageName/$className"]

    /** Every drawable the pack maps an app to, once each, by the first app it stands for (its package, then activity). */
    fun drawables(): List<String> = mapped.entries.sortedBy { it.value.first() }.map { it.key }

    /** The activities [drawable] stands for, as `package/class`; empty for one the pack maps no app to. */
    fun componentsOf(drawable: String): List<String> = mapped[drawable].orEmpty()

    val isEmpty get() = entries.isEmpty()

    companion object {
        /** `ComponentInfo{pkg/.Main}` or `pkg/pkg.Main` as `pkg/pkg.Main`; null for an entry that isn't an activity. */
        fun componentKey(component: String): String? {
            val inner = component.trim().removePrefix("ComponentInfo{").removeSuffix("}").trim()
            val pkg = inner.substringBefore('/', "").trim()
            val cls = inner.substringAfter('/', "").trim()
            if (pkg.isEmpty() || cls.isEmpty()) return null
            return "$pkg/${if (cls.startsWith('.')) pkg + cls else cls}"
        }
    }
}

/**
 * An icon pack: an app whose icons other launchers draw apps with (Nova's, ADW's, Lawnchair's packs). [AppFilter]
 * maps apps to its drawables; drawable.xml, where it has one, lists every icon it offers to pick from. Read once,
 * then safe to use from several threads: icons are drawn on a few at a time.
 */
class IconPack(
    context: Context,
    val packageName: String,
) {
    private val resources: Resources = context.packageManager.getResourcesForApplication(packageName)

    /** When the pack was installed or last updated, so a new version is read again. */
    val stamp: Long = context.packageManager.getPackageInfo(packageName, 0).lastUpdateTime

    private val filter = AppFilter().also { f -> read("appfilter") { parser -> f.add(parser.name) { parser.getAttributeValue(null, it) } } }

    /** [component]'s drawable in the pack, by name; null when the pack has none for it. */
    fun drawableFor(component: ComponentName): String? = filter.drawableFor(component.packageName, component.className)

    /** [component]'s icon from the pack, or null to draw the app's own. Blocking. */
    fun iconFor(component: ComponentName): Drawable? = drawableFor(component)?.let(::drawable)

    /**
     * Every icon the pack offers, by drawable name: drawable.xml's list, the one packs make for icon pickers, then the
     * rest its apps map to. Some packs' list is only their own few (the system's, folders: Glassify's 551 of its
     * 39,000), the apps' icons being in appfilter.xml alone.
     */
    val icons: List<String> by lazy {
        val listed = LinkedHashSet<String>()
        read("drawable") { parser ->
            if (parser.name == "item") parser.getAttributeValue(null, "drawable")?.trim()?.takeIf { it.isNotEmpty() }?.let(listed::add)
        }
        listed.addAll(filter.drawables())
        listed.toList()
    }

    /** The apps the pack's icon [name] stands for, as `package/class`: what finds it in a search when its name is a code. */
    fun appsOf(name: String): List<String> = filter.componentsOf(name)

    /** The pack's drawable [name], a copy of its own to draw on any thread; null when the pack doesn't have it. Blocking. */
    fun drawable(name: String): Drawable? {
        // Packs name drawables plainly ("gmail"), and a few as a resource ("@drawable/gmail").
        val plain = name.substringAfterLast('/').removePrefix("@").trim()
        if (plain.isEmpty()) return null
        @Suppress("DiscouragedApi")
        val id = sequenceOf("drawable", "mipmap").map { resources.getIdentifier(plain, it, packageName) }.firstOrNull { it != 0 } ?: return null
        // Drawables of one resource share their state; drawing two at once on two threads mustn't.
        return runCatching { resources.getDrawable(id, null)?.mutate() }.getOrNull()
    }

    /**
     * Reads the pack's [name].xml from res/xml, res/raw or its assets, where packs keep it, giving [onTag] each start
     * tag. Nothing for a pack without one; a broken file gives what was read before the break.
     */
    @Suppress("DiscouragedApi")
    private fun read(
        name: String,
        onTag: (XmlPullParser) -> Unit,
    ) {
        runCatching {
            val xml = resources.getIdentifier(name, "xml", packageName)
            if (xml != 0) return resources.getXml(xml).use { walk(it, onTag) }
            val raw = resources.getIdentifier(name, "raw", packageName)
            val stream = if (raw != 0) resources.openRawResource(raw) else runCatching { resources.assets.open("$name.xml") }.getOrNull() ?: return
            stream.use { walk(Xml.newPullParser().apply { setInput(it, null) }, onTag) }
        }.onFailure { Log.w(TAG, "cannot read $packageName's $name.xml", it) }
    }

    private fun walk(
        parser: XmlPullParser,
        onTag: (XmlPullParser) -> Unit,
    ) {
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) onTag(parser)
        }
    }

    private companion object {
        const val TAG = "icon-packs"
    }
}

/**
 * The icon packs on the phone, and the one style.toml's icons.pack picks, whose icons the app catalog draws apps
 * with. The pick is read when the first icon needs it. Thread-safe: icons are drawn on several threads.
 */
class IconPackStore(
    private val context: Context,
) {
    /** A pack on the phone, as the pickers list it. */
    data class Installed(
        val packageName: String,
        val label: String,
    )

    /** The picked pack's package; null draws each app's own icon. */
    private var chosen: String? = null

    /** [chosen]'s pack once read, or null with [failed] when it couldn't be (uninstalled, unreadable). */
    private var pack: IconPack? = null
    private var failed = false

    /** The packs on the phone, by name. Asked of Android each time: packs come and go like any app. Blocking. */
    fun installed(): List<Installed> {
        val pm = context.packageManager
        val found = LinkedHashSet<String>()
        for (intent in PACK_INTENTS) {
            runCatching { pm.queryIntentActivities(intent, 0) }.getOrNull().orEmpty().mapTo(found) { it.activityInfo.packageName }
        }
        return found
            .filter { it != context.packageName }
            .map { pkg -> Installed(pkg, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg) }
            .sortedBy { it.label.lowercase() }
    }

    /** Picks [packageName]'s pack, null for the apps' own icons; true when that changes the icons, which the caller draws again. */
    @Synchronized
    fun choose(packageName: String?): Boolean {
        if (packageName == chosen) return false
        chosen = packageName
        pack = null
        failed = false
        return true
    }

    /**
     * Whether the picked pack was updated, installed or removed since it was read; then it's read again with the
     * next icon, and the caller draws the icons again. Cheap: one look at the package.
     */
    @Synchronized
    fun changedOnDisk(): Boolean {
        val name = chosen ?: return false
        val stamp = runCatching { context.packageManager.getPackageInfo(name, 0).lastUpdateTime }.getOrNull()
        val changed =
            when {
                pack != null -> stamp != pack?.stamp
                failed -> stamp != null
                else -> false
            }
        if (changed) {
            pack = null
            failed = false
        }
        return changed
    }

    /** Whether the picked pack can be read, reading it now if it isn't yet; true with none picked. Blocking. */
    @Synchronized
    fun ready(): Boolean = chosen == null || current() != null

    /** [component]'s icon in the picked pack, or null for the app's own. Blocking: the first one reads the pack. */
    fun iconFor(component: ComponentName): Drawable? = current()?.iconFor(component)

    /** [packageName]'s pack for picking icons from, the picked one if it's that; null if it can't be read. Blocking. */
    fun open(packageName: String): IconPack? = synchronized(this) { pack?.takeIf { it.packageName == packageName } } ?: read(packageName)

    @Synchronized
    private fun current(): IconPack? {
        val name = chosen ?: return null
        if (pack == null && !failed) {
            pack = read(name)
            failed = pack == null
        }
        return pack
    }

    private fun read(packageName: String): IconPack? =
        runCatching { IconPack(context, packageName) }.onFailure { Log.w(TAG, "cannot read the icon pack $packageName", it) }.getOrNull()

    companion object {
        private const val TAG = "icon-packs"

        /**
         * How packs offer their icons to launchers: another launcher's theme action, or Apex's category. The
         * manifest's <queries> lists the same, or Android 11+ hides the packs from us.
         */
        val PACK_INTENTS =
            listOf(
                "org.adw.launcher.THEMES",
                "org.adw.ActivityStarter.THEMES",
                "com.novalauncher.THEME",
                "com.teslacoilsw.launcher.THEME",
                "app.lawnchair.icons.THEMED_ICON",
                "com.gau.go.launcherex.theme",
            ).map { Intent(it) } + Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME")
    }
}

/**
 * [icons] with every word of [query] in their name or in what [about] says of them: the apps each stands for (their
 * packages and names), as packs that name their drawables with codes are searched. The names' underscores read as spaces.
 */
fun searchPackIcons(
    icons: List<String>,
    query: String,
    about: (String) -> String = { "" },
): List<String> {
    val words = query.lowercase().split(' ', '_', '-').filter { it.isNotEmpty() }
    if (words.isEmpty()) return icons
    return icons.filter { name -> "$name ${about(name)}".lowercase().let { n -> words.all { it in n } } }
}

/**
 * Icons likely meant for an app named [label]: the one its pack maps it to ([mapped]), then those whose name, or what
 * [about] says of them, spells the label, closest first.
 */
fun suggestPackIcons(
    icons: List<String>,
    label: String,
    mapped: String?,
    max: Int = 12,
    about: (String) -> String = { "" },
): List<String> {
    fun squash(s: String) = s.lowercase().filter(Char::isLetterOrDigit)
    val key = squash(label)
    val spelled =
        if (key.length < 2) {
            emptyList()
        } else {
            icons.mapNotNull { name -> squash("$name ${about(name)}").takeIf { key in it }?.let { name to it.length } }.sortedBy { it.second }.map { it.first }
        }
    return (listOfNotNull(mapped) + spelled).distinct().take(max)
}
