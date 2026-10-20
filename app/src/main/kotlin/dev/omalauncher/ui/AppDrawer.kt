package dev.omalauncher.ui

import androidx.compose.ui.input.pointer.pointerInput
import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.layout.width
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.graphics.ColorFilter
import dev.omalauncher.Dock
import dev.omalauncher.DOCK_SLOTS
import dev.omalauncher.AndroidApp
import dev.omalauncher.AndroidShortcut
import androidx.compose.runtime.collectAsState
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import dev.omalauncher.linux.LinuxApp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.composed
import androidx.compose.foundation.layout.offset
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect
import dev.omalauncher.landingSlot
import dev.omalauncher.theme.Style
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.runtime.key
import dev.omalauncher.widgets.WidgetSpec

/** One app in the bar's results. */
internal sealed interface AppHit {
    /** Stable id, also the [dev.omalauncher.AppUsage] key. */
    val key: String
    val label: String

    /** What screen readers say, to tell a work app or a clone from the personal one of the same name. */
    val spoken: String get() = label

    /**
     * Another name search matches: a work app's own name with "work" added, so "work" or "whatsapp work"
     * finds it; a clone's with "clone".
     */
    val alias: String? get() = null

    /** In a paused work profile: shown dimmed, and opening it turns work apps back on. */
    fun paused(runtime: Runtime) = false

    data class Linux(
        val app: LinuxApp,
    ) : AppHit {
        override val key get() = "l:" + app.id
        override val label get() = app.name
    }

    data class Android(
        val app: AndroidApp,
    ) : AppHit {
        override val key get() = "a:" + app.key
        override val label get() = app.label
        override val spoken get() = app.spoken
        override val alias get() = if (app.profile != null) "${app.label} ${if (app.clone) "clone" else "work"}" else null

        override fun paused(runtime: Runtime) = runtime.androidApps.paused(app.profile)
    }

    /** An owl file that's an app: it opens full screen over home. */
    data class Owl(
        val spec: WidgetSpec,
    ) : AppHit {
        override val key get() = "o:" + spec.file.name
        override val label get() = spec.name
    }

    /** A shortcut an app put on the home screen, such as a website installed from a browser. */
    data class Shortcut(
        val shortcut: AndroidShortcut,
    ) : AppHit {
        override val key get() = "s:" + shortcut.key
        override val label get() = shortcut.label
        override val spoken get() = shortcut.spoken
        override val alias get() = if (shortcut.profile != null) "${shortcut.label} ${if (shortcut.clone) "clone" else "work"}" else null

        override fun paused(runtime: Runtime) = runtime.androidApps.paused(shortcut.profile)
    }
}

/** What the bar's query matched: apps best first, and whether Enter opens the first rather than asking the agent. */
internal class BarSearch(
    val hits: List<AppHit>,
    val opensApp: Boolean,
) {
    /** The result Enter acts on: an index into [hits], [ASK] for the agent or [WEB] for the web; see [barHighlight]. */
    fun highlighted(
        picked: Int?,
        canAsk: Boolean,
    ) = barHighlight(picked, hits.size, opensApp, canAsk)

    /** The row [step]s up the panel from [from]; see [barStep]. */
    fun moved(
        from: Int,
        step: Int,
    ) = barStep(from, step, hits.size)

    companion object {
        /** The ask row's place, lowest above the bar. */
        const val ASK = -1

        /** The web search row's, above the ask row and below the apps. */
        const val WEB = -2
    }
}

/**
 * What Enter acts on: the row [picked] with Tab or the arrows while it's still there, else the first of [hits] apps
 * when it's a strong match, else the agent, or the web without an agent to [canAsk].
 */
internal fun barHighlight(
    picked: Int?,
    hits: Int,
    opensApp: Boolean,
    canAsk: Boolean,
): Int =
    when {
        picked == BarSearch.ASK || picked == BarSearch.WEB -> picked
        picked != null && hits > 0 -> picked.coerceIn(0, hits - 1)
        opensApp && hits > 0 -> 0
        canAsk -> BarSearch.ASK
        else -> BarSearch.WEB
    }

/** The row [step]s up the panel from [from] (negative: back down toward the bar), stopping at the ends: ask, web, then the [hits] apps. */
internal fun barStep(
    from: Int,
    step: Int,
    hits: Int,
): Int {
    val order = listOf(BarSearch.ASK, BarSearch.WEB) + (0 until hits)
    val at = order.indexOf(from).coerceAtLeast(0)
    return order[(at + step).coerceIn(0, order.lastIndex)]
}

internal fun allApps(runtime: Runtime): List<AppHit> =
    runtime.widgets.apps().map { AppHit.Owl(it) } + runtime.linuxApps.apps.map { AppHit.Linux(it) } + runtime.androidApps.apps.map { AppHit.Android(it) } +
        runtime.androidApps.shortcuts.map { AppHit.Shortcut(it) }

/** The apps the drawer, search and the recent strip list: the [hidden] ones only while [showHidden]. */
internal fun List<AppHit>.shown(
    hidden: Set<String>,
    showHidden: Boolean,
) = if (showHidden) this else filterNot { it.key in hidden }

/** The picked apps with [key] picked or unpicked; unpicking the last one stops picking (null). */
internal fun toggled(
    selection: Set<String>,
    key: String,
): Set<String>? = (if (key in selection) selection - key else selection + key).ifEmpty { null }

internal fun searchApps(
    runtime: Runtime,
    query: String,
    showHidden: Boolean = false,
): BarSearch {
    if (query.isBlank()) return BarSearch(emptyList(), false)
    val now = System.currentTimeMillis()
    val apps = allApps(runtime).shown(runtime.hiddenApps.keys, showHidden)
    // Your own name first; the app's own name still finds a renamed app.
    val ranked =
        rankMatches(
            apps,
            query,
            { it.name(runtime) },
            boost = { (runtime.usage.score(it.key, now) * 10).toInt() },
            alias = { if (runtime.customs.name(it.key) != null) it.alias ?: it.label else it.alias },
        )
    return BarSearch(ranked.map { it.first }, ranked.firstOrNull()?.let { isStrongMatch(it.second, query) } == true)
}

/** What the app cells do: open, pin to home, the Android app's info and uninstall, removing a shortcut, hiding, and an owl app's edit, share and remove. */
internal class AppHandlers(
    val launch: (AppHit) -> Unit,
    /** Pins apps to home, several at once when picked together. */
    val pin: (List<AppHit>) -> Unit,
    val info: (AndroidApp) -> Unit,
    val uninstall: (AndroidApp) -> Unit,
    val remove: (AndroidShortcut) -> Unit,
    /** An Android app's shortcuts, for its long-press menu. */
    val shortcuts: (AndroidApp) -> ShortcutMenu,
    val isHidden: (AppHit) -> Boolean,
    /** Hides the apps with these keys from the drawer, search and the recent strip, or shows them again. */
    val hide: (Collection<String>, Boolean) -> Unit,
    /** Starts picking several apps in the drawer, this one first. */
    val select: (AppHit) -> Unit,
    /** Picks the app with this key among several, or unpicks it; the first one starts picking, unpicking the last stops. */
    val pick: (String) -> Unit,
    /** The dock above the bar: what the menus add to, remove from and move within. */
    val dock: Dock,
    /** Opens the app's customize dialog: its name, icon and drawer group. */
    val customize: (AppHit) -> Unit,
    /** Opens the group picker for the apps with these keys, to put them in a drawer group. */
    val group: (Set<String>) -> Unit,
    /** Asks for a new name for the drawer group called this. */
    val renameGroup: (String) -> Unit,
    /** Opens the default agent on an owl app's file. */
    val edit: (WidgetSpec) -> Unit,
    /** Shares an owl app as a link or QR code, as a widget is shared. */
    val share: (WidgetSpec) -> Unit,
    /** Removes an owl app's file, and its icon from home. */
    val removeOwl: (WidgetSpec) -> Unit,
)

/** The shortcuts an Android app offers above its menu's actions; null for anything else. */
private fun AppHit.shortcutMenu(handlers: AppHandlers): ShortcutMenu? = (this as? AppHit.Android)?.let { handlers.shortcuts(it.app) }

/**
 * The long-press menu of an app cell: pin to home, for owl apps editing, sharing and removing, for Android apps their
 * info and uninstall, for shortcuts removing them, then hiding it (or showing it again) and, in the drawer ([selectable]), picking several apps.
 */
private fun AppHit.actions(
    handlers: AppHandlers,
    selectable: Boolean = false,
    /** Opened from the dock itself, standing up beside landscape's grid when [DockCell.vertical]. */
    inDock: DockCell? = null,
): List<Pair<String, () -> Unit>> {
    val own =
        when (this) {
            is AppHit.Linux -> listOf("add to home" to { handlers.pin(listOf(this)) })
            is AppHit.Owl ->
                listOf(
                    "add to home" to { handlers.pin(listOf(this)) },
                    "edit" to { handlers.edit(spec) },
                    "share" to { handlers.share(spec) },
                    "remove" to { handlers.removeOwl(spec) },
                )
            is AppHit.Android ->
                listOfNotNull(
                    "add to home" to { handlers.pin(listOf(this)) },
                    "app info" to { handlers.info(app) },
                    if (app.system) null else "uninstall" to { handlers.uninstall(app) },
                )
            is AppHit.Shortcut ->
                listOf(
                    "add to home" to { handlers.pin(listOf(this)) },
                    "remove" to { handlers.remove(shortcut) },
                )
        }
    val hidden = handlers.isHidden(this)
    return own +
        dockActions(handlers.dock, inDock) +
        listOf("customize" to { handlers.customize(this) }) +
        listOfNotNull(
            (if (hidden) "unhide" else "hide") to { handlers.hide(listOf(key), !hidden) },
            if (selectable) "select several" to { handlers.select(this) } else null,
        )
}

/** An app's menu opened from the dock; [vertical] when the dock stands beside landscape's grid. */
internal class DockCell(
    val vertical: Boolean,
)

/**
 * While the dock shows: an app in it leaves it, and from the dock itself moves along it; any other joins it while
 * there's room. Nothing while the strip shows the apps used lately, or nothing, where a dock would be out of sight.
 */
private fun AppHit.dockActions(
    dock: Dock,
    from: DockCell?,
): List<Pair<String, () -> Unit>> {
    if (!LauncherStyle.dockShown) return emptyList()
    val at = dock.keys.indexOf(key)
    return when {
        at >= 0 -> dockMoves(dock, key, from) + ("remove from dock" to { dock.remove(key) })
        dock.full -> emptyList()
        else -> listOf("add to dock" to { dock.add(listOf(key)) })
    }
}

/** Moving the app at [key] along the dock, offered only from the dock, where its neighbours are in sight. */
private fun dockMoves(
    dock: Dock,
    key: String,
    from: DockCell?,
): List<Pair<String, () -> Unit>> {
    if (from == null) return emptyList()
    val at = dock.keys.indexOf(key)
    return listOfNotNull(
        if (at > 0) (if (from.vertical) "move up" else "move left") to { dock.move(key, -1) } else null,
        if (at < dock.keys.lastIndex) (if (from.vertical) "move down" else "move right") to { dock.move(key, 1) } else null,
    )
}

/** In a work profile: listed on the work tab, not the personal one's groups or most-used row. */
internal val AppHit.isWork
    get() =
        when (this) {
            is AppHit.Android -> app.work
            is AppHit.Shortcut -> shortcut.work
            is AppHit.Linux, is AppHit.Owl -> false
        }

/** The name the drawer, home and the dock show for it: yours (customize), or the app's own. */
internal fun AppHit.name(runtime: Runtime): String = runtime.customs.name(key) ?: label

/** The app's icon and its silhouette, each null until loaded or if it has none. */
internal fun AppHit.icons(runtime: Runtime): Pair<ImageBitmap?, ImageBitmap?> {
    // Your own icon (customize), with its silhouette for the monochrome style.
    runtime.customs.icons[key]?.let { mine -> return mine to (runtime.customs.masks[key] ?: mine) }
    return when (this) {
        is AppHit.Linux -> runtime.linuxApps.icons[app.id] to runtime.linuxApps.masks[app.id]
        is AppHit.Owl -> runtime.owlIcon(spec, mono = false) to runtime.owlIcon(spec, mono = true)
        is AppHit.Android -> runtime.androidApps.icons[app.key] to runtime.androidApps.masks[app.key]
        is AppHit.Shortcut -> runtime.androidApps.icons[shortcut.key] to runtime.androidApps.masks[shortcut.key]
    }
}

/** Opens the app and counts it for the recent strip and search, wherever it was tapped. */
internal fun AppHit.open(runtime: Runtime) {
    runtime.usage.record(key)
    when (this) {
        is AppHit.Linux -> runtime.launch(app)
        is AppHit.Android -> runtime.androidApps.open(app)
        is AppHit.Shortcut -> runtime.androidApps.open(shortcut)
        is AppHit.Owl -> runtime.openOwlApp(spec.file.name)
    }
}

/**
 * The icon of the app whose [AppHit.key] is [key], or its silhouette with [mono]; null until loaded or if it
 * has none. Looked up by kind rather than through [allApps]: widgets ask for it while drawing.
 */
internal fun appIconOf(
    runtime: Runtime,
    key: String,
    mono: Boolean,
): ImageBitmap? {
    // Your own icon (customize), with its silhouette for the monochrome style.
    runtime.customs.icons[key]?.let { mine -> return if (mono) runtime.customs.masks[key] ?: mine else mine }
    val id = key.substringAfter(':')
    return when (key.substringBefore(':')) {
        "a", "s" -> (if (mono) runtime.androidApps.masks else runtime.androidApps.icons)[id]
        "l" -> (if (mono) runtime.linuxApps.masks else runtime.linuxApps.icons)[id]
        "o" -> runtime.widgets.apps().firstOrNull { it.file.name == id }?.let { runtime.owlIcon(it, mono) }
        else -> null
    }
}

/** Height of the recent apps strip; home keeps this much room for it above the bar, even while it's empty. */
val RecentStripHeight = 56.dp

/** The strip's height on home in portrait: the dock's own (home.dock-height) while it holds the dock. */
internal val stripHeight: Dp get() = if (LauncherStyle.dockShown) LauncherStyle.dockHeight else RecentStripHeight

/**
 * A dock app's cell for a dock [along] long and [across] deep: as deep as the dock with a margin, but never wider
 * than its fifth of the length with a gap between, never below the recent strip's 48dp.
 */
internal fun dockCellSize(
    along: Dp,
    across: Dp,
): Dp = minOf(across - 12.dp, along / DOCK_SLOTS - 8.dp).coerceAtLeast(48.dp)

/** Home's strip above the bar: the dock, or the apps used lately, as style's home.dock says. */
@Composable
internal fun AppStrip(
    runtime: Runtime,
    state: LauncherState,
    handlers: AppHandlers,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) = if (LauncherStyle.dock) DockStrip(runtime, state, handlers, modifier, vertical) else RecentStrip(runtime, handlers, modifier, vertical)

/**
 * The dock: up to [DOCK_SLOTS] apps you picked, each in its own slot spread across the strip (down it in landscape),
 * so they stay put as stock docks do. An empty dock says how to fill it.
 */
@Composable
private fun DockStrip(
    runtime: Runtime,
    state: LauncherState,
    handlers: AppHandlers,
    modifier: Modifier,
    vertical: Boolean,
) {
    // Where the dock is in the window, for apps dropped on it (from the dock, or the drawer); none while it's gone.
    DisposableEffect(state) { onDispose { state.dockBounds = Rect.Zero } }
    val byKey = allApps(runtime).associateBy { it.key }
    val linuxState by runtime.bootstrap.state.collectAsState()
    val linuxGone = linuxState == LinuxState.NotInstalled
    // In the dock's order; one uninstalled (or a Linux app not loaded yet) leaves its slot empty.
    val slots = runtime.dock.keys.map { byKey[it] }
    BoxWithConstraints(
        modifier
            .then(if (vertical) Modifier.fillMaxHeight().width(RecentStripHeight) else Modifier.padding(horizontal = EdgeGap).fillMaxWidth().height(LauncherStyle.dockHeight))
            .onGloballyPositioned { state.dockBounds = it.boundsInWindow() }
            // Boxed or bare as home.dock-box says, whatever home's own apps are.
            .then(if (LauncherStyle.dockBoxed) Modifier.tile() else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (slots.isEmpty()) {
            val hovered = state.dockHover != null
            BasicText(
                when {
                    vertical -> "dock"
                    hovered -> "drop to add to the dock"
                    else -> "long-press an app › add to dock, or drag one here"
                },
                style = Mono.copy(color = if (hovered) Palette.accent else Palette.muted, fontSize = TextSize.Small),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            return@BoxWithConstraints
        }
        val from = remember(vertical) { DockCell(vertical) }
        // As big as the dock is tall, but five must fit across (down, in landscape).
        val cell = if (vertical) dockCellSize(maxHeight, maxWidth) else dockCellSize(maxWidth, maxHeight)
        val cells: @Composable (Modifier) -> Unit = { slot ->
            for (i in 0 until DOCK_SLOTS) {
                // A home app carried over this slot would land here.
                val hovered = state.dockHover == i
                Box(slot.then(if (hovered) Modifier.frame(2.dp, Palette.accent) else Modifier), contentAlignment = Alignment.Center) {
                    val key = runtime.dock.keys.getOrNull(i)
                    val hit = slots.getOrNull(i)
                    when {
                        hit != null -> {
                            val (icon, mask) = hit.icons(runtime)
                            val dragged = state.dockDrag?.key == hit.key
                            Box(Modifier.dragsApp(runtime, state, hit, fromDrawer = false)) {
                                StripCell(hit.name(runtime), icon, mask, onClick = { handlers.launch(hit) }, actions = { hit.actions(handlers, inDock = from) }, shortcuts = { hit.shortcutMenu(handlers) }, spoken = hit.spoken, paused = hit.paused(runtime) || dragged, bare = !LauncherStyle.dockBoxed, cell = cell, dragged = dragged)
                            }
                        }
                        // Only once its list has loaded is an app missing from it gone; until then its slot waits empty.
                        key != null && dockKeyKnown(runtime, key, linuxGone) -> MissingDockCell { runtime.dock.remove(key) }
                    }
                }
            }
        }
        if (vertical) {
            Column(Modifier.fillMaxSize()) { cells(Modifier.weight(1f).fillMaxWidth()) }
        } else {
            Row(Modifier.fillMaxSize()) { cells(Modifier.weight(1f).fillMaxHeight()) }
        }
    }
}

/**
 * The apps opened lately, on home right above the bar, scrolling sideways; [vertical] stands it up
 * beside the grid in landscape, scrolling down. A home tile like the others: frame, fill and gaps,
 * or bare when home icons are. Can be hidden in themes.
 */
@Composable
internal fun RecentStrip(
    runtime: Runtime,
    handlers: AppHandlers,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    val apps = recentApps(runtime, 20)
    // Nothing opened yet: no strip, but its room stays, so home doesn't shift when the first app shows up.
    if (apps.isEmpty()) {
        Spacer(if (vertical) modifier.width(RecentStripHeight) else modifier.height(RecentStripHeight))
        return
    }
    val bare = LauncherStyle.bareAppIcons
    val listState = rememberLazyListState()
    // Back on home, the strip starts at the app opened last, wherever it was scrolled to before.
    LaunchedEffect(runtime.usage.settled) { listState.scrollToItem(0) }
    Box(
        modifier
            .then(if (vertical) Modifier.fillMaxHeight().width(RecentStripHeight) else Modifier.padding(horizontal = EdgeGap).fillMaxWidth().height(RecentStripHeight))
            .then(if (bare) Modifier else Modifier.tile()),
        contentAlignment = if (vertical) Alignment.TopCenter else Alignment.CenterStart,
    ) {
        val cells: LazyListScope.() -> Unit = {
            items(apps, key = { it.key }) { hit ->
                val (icon, mask) = hit.icons(runtime)
                StripCell(hit.name(runtime), icon, mask, onClick = { handlers.launch(hit) }, actions = { hit.actions(handlers) }, shortcuts = { hit.shortcutMenu(handlers) }, spoken = hit.spoken, paused = hit.paused(runtime))
            }
        }
        if (vertical) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, content = cells)
        } else {
            LazyRow(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, content = cells)
        }
    }
}

/**
 * Whether the list a dock key's app comes from has loaded, so its absence means it's gone: Linux apps once listed
 * (or with no Linux at all, [linuxGone]; while it's checked, installed or started they're still coming), shortcuts
 * once Android lets the launcher list them (as the default home app), Android apps once listed. An owl app is gone
 * once its file is, or is no longer an app (broken, or a widget now); one that needs Linux waits while Linux starts.
 * Before the widgets have loaded, only its file tells.
 */
private fun dockKeyKnown(
    runtime: Runtime,
    key: String,
    linuxGone: Boolean,
) = when {
    key.startsWith("l:") -> runtime.linuxAppsListed || linuxGone
    key.startsWith("o:") -> {
        val name = key.removePrefix("o:")
        runtime.widgets.specs.firstOrNull { it.file.name == name }?.let { !runtime.widgets.waitsForLinux(it) }
            ?: !File(runtime.widgets.dir, name).exists()
    }
    key.startsWith("s:") -> runtime.androidApps.loaded && runtime.androidApps.listsShortcuts
    else -> runtime.androidApps.loaded
}

/**
 * Long-press an app in the dock or the drawer and drag: it follows the finger ([LauncherState.dockDrag], drawn by
 * [DockDragIcon]) and, let go, lands on home in the cell under the finger or on the dock in the slot under it (the
 * slot is framed as it passes). Dragged from the dock it leaves the dock for home; from the drawer ([fromDrawer]) the
 * drawer steps aside for home and the dock while it's carried, and closes once it's dropped. A long-press without a
 * drag keeps its menu, and a swipe before the long-press is home's or the list's.
 *
 * Watched on the Initial pass, ahead of the icon's own tap and long-press and of the swipes around it: once the press
 * has lasted a long-press and then moves past the slop, the moves are taken here, so neither sees them.
 */
private fun Modifier.dragsApp(
    runtime: Runtime,
    state: LauncherState,
    hit: AppHit,
    fromDrawer: Boolean,
    enabled: Boolean = true,
): Modifier =
    if (!enabled) {
        this
    } else {
        composed {
            // Where the icon is, read as the finger moves rather than written on every layout (a scrolled list moves
            // every cell every frame).
            val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
            // Kept composed while carried, even scrolled out of the list, or the drag would end with it.
            val pinnable = LocalPinnableContainer.current
            onGloballyPositioned { coords[0] = it }.pointerInput(hit.key, fromDrawer) {
                val slop = viewConfiguration.touchSlop
                val hold = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var dragging = false
                    var pin: PinnableContainer.PinnedHandle? = null
                    // However the drag ends (this icon leaving the screen mid-drag too), the dragged icon goes.
                    try {
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                            if (!dragging) {
                                if (!change.pressed) break
                                if ((change.position - down.position).getDistance() <= slop) continue
                                // Moved before the long-press: a swipe, home's or the list's, or the tap's to settle.
                                if (change.uptimeMillis - down.uptimeMillis < hold) break
                                dragging = true
                                pin = pinnable?.pin()
                                state.dockDrag = hit
                                if (fromDrawer) {
                                    state.drawerDrag = true
                                    // The keyboard goes, so home and the dock are where they'll be.
                                    state.focusSearch = false
                                }
                            }
                            val at = (coords[0]?.takeIf { it.isAttached }?.positionInWindow() ?: Offset.Zero) + change.position
                            if (!change.pressed) {
                                // A lift arrives here unconsumed; a cancel (the system taking the gesture) arrives consumed,
                                // and only puts the icon back.
                                if (!change.isConsumed) {
                                    dropApp(runtime, state, hit, at, leaveDock = !fromDrawer)
                                    if (fromDrawer) state.closeNow()
                                }
                                change.consume()
                                break
                            }
                            change.consume()
                            state.dockDragAt = at
                            state.dockHover = dockSlotAt(state.dockBounds, at)?.let { landingSlot(runtime.dock.keys, hit.key, it) }
                        }
                    } finally {
                        pin?.release()
                        if (state.dockDrag === hit) {
                            state.dockDrag = null
                            state.drawerDrag = false
                            state.dockHover = null
                        }
                    }
                }
            }
        }
    }

/**
 * Phone build: what carries an app out of the panel in the drawer's place onto home or the dock, by its key, for owl's
 * `dragApp` ([dev.omalauncher.widgets.render.LocalAppDrag]); an app since gone carries nothing.
 */
@Composable
internal fun rememberAppDrag(
    runtime: Runtime,
    state: LauncherState,
): (String) -> Modifier {
    val byKey by remember(runtime) { derivedStateOf { allApps(runtime).associateBy { it.key } } }
    return remember(runtime, state) { { key -> byKey[key]?.let { Modifier.dragsApp(runtime, state, it, fromDrawer = true) } ?: Modifier } }
}

/** The dock slot under [at] in the window: along the dock, across it in portrait or down it in landscape; null off it. */
internal fun dockSlotAt(
    dock: Rect,
    at: Offset,
): Int? {
    if (dock.isEmpty || !dock.contains(at)) return null
    val slot = if (dock.height > dock.width) (at.y - dock.top) / (dock.height / DOCK_SLOTS) else (at.x - dock.left) / (dock.width / DOCK_SLOTS)
    return slot.toInt().coerceIn(0, DOCK_SLOTS - 1)
}

/**
 * Where an app let go at [at] lands: a slot of the dock, or home's cell under it ([leaveDock]: out of the dock, as one
 * dragged from it is). Let go anywhere else (the bar, past the edge), nothing changes.
 */
private fun dropApp(
    runtime: Runtime,
    state: LauncherState,
    hit: AppHit,
    at: Offset,
    leaveDock: Boolean,
) {
    dockSlotAt(state.dockBounds, at)?.let { slot ->
        runtime.dock.insert(hit.key, slot)
        return
    }
    val (page, cell) = state.homeCellAt(at) ?: return
    val home = runtime.widgets.shortcuts
    val key =
        when (hit) {
            is AppHit.Linux -> home.keyOf(hit.app)
            is AppHit.Android -> home.keyOf(hit.app)
            is AppHit.Shortcut -> home.keyOf(hit.shortcut)
            is AppHit.Owl -> home.keyOf(hit.spec)
        }
    val there = home.items.firstOrNull { it.key == key }
    when {
        // On home in a folder already: it stays there.
        there?.folder != null -> {
            if (leaveDock) runtime.dock.remove(hit.key)
            return
        }
        // On home on its own already: that tile moves to the drop.
        there != null -> if (there.page != page) home.move(key, page)
        else ->
            when (hit) {
                is AppHit.Linux -> home.addLinux(hit.app, page)
                is AppHit.Android -> home.addAndroid(hit.app, page)
                is AppHit.Shortcut -> home.addAndroidShortcut(hit.shortcut, page)
                is AppHit.Owl -> home.addOwl(hit.spec, page)
            }
    }
    val layout = runtime.widgets.layout
    val taken = runtime.widgets.itemsOn(page).filter { it.key != key }.mapNotNull { item -> layout.cell(item.key)?.let { if (item.resizable) it else it.copy(w = 1, h = 1) } }
    // The cell under the finger if it's free; otherwise the first free one, found as for any new tile.
    if (taken.none { it.overlaps(cell) }) {
        layout.set(key, cell)
        layout.save()
    } else {
        layout.remove(key)
    }
    if (leaveDock) runtime.dock.remove(hit.key)
}

/** The dock app being dragged out, under the finger above everything else; it follows without recomposing. */
@Composable
internal fun DockDragIcon(
    runtime: Runtime,
    state: LauncherState,
    hit: AppHit,
) {
    val (icon, mask) = hit.icons(runtime)
    val half = with(LocalDensity.current) { 32.dp.roundToPx() }
    Box(
        Modifier.offset {
            val at = state.dockDragAt
            IntOffset(at.x.roundToInt() - half, at.y.roundToInt() - half)
        },
    ) {
        StripCell(hit.name(runtime), icon, mask, onClick = {}, actions = { emptyList() }, spoken = hit.spoken, bare = !LauncherStyle.dockBoxed, cell = 64.dp)
    }
}

/**
 * A dock slot whose app isn't on the phone (uninstalled, or a Linux app still loading): a faint mark that a tap or
 * long-press offers to free, so a dock full of gone apps can be emptied again.
 */
@Composable
private fun MissingDockCell(onRemove: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(48.dp)
            .clip(LauncherStyle.shape(12.dp))
            .combinedClickable(onClick = { menuOpen = true }, onLongClick = { menuOpen = true })
            .semantics { contentDescription = "missing app" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(28.dp).alpha(PausedAlpha).frame(1.dp, Palette.border, radius = 14.dp))
        if (menuOpen) AppActions(listOf("remove from dock" to onRemove), { null }, onDismiss = { menuOpen = false })
    }
}

/** An app in the recent strip, drawn like a pinned app's tile: the icon alone, or on a badge when bare. */
@Composable
private fun StripCell(
    label: String,
    icon: ImageBitmap?,
    mask: ImageBitmap?,
    onClick: () -> Unit,
    /** Built when the menu opens: a fresh list of lambdas would keep the cell from skipping recomposition. */
    actions: () -> List<Pair<String, () -> Unit>>,
    /** Also built when the menu opens. */
    shortcuts: () -> ShortcutMenu? = { null },
    /** As in [AppHit.spoken]. */
    spoken: String = label,
    /** As in [AppHit.paused]: drawn dimmed, like stock launchers. */
    paused: Boolean = false,
    /** Drawn bare on the wallpaper (badges behind silhouettes and initials): home's apps' style, or the dock's own. */
    bare: Boolean = LauncherStyle.bareAppIcons,
    /** The cell's size; its icon takes five sixths of it. A big dock passes more. */
    cell: Dp = 48.dp,
    /** Being dragged out of the dock: the long-press menu gives way to the drag. */
    dragged: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(dragged) { if (dragged) menuOpen = false }
    val haptics = LocalHapticFeedback.current
    val mono = LauncherStyle.monochromeIcons
    val size = cell * 5 / 6
    Box(
        Modifier
            .size(cell)
            .alpha(if (paused) PausedAlpha else 1f)
            .clip(LauncherStyle.shape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = if (mono) mask else icon
        when {
            // Bare silhouettes and initials sit on a badge, as pinned apps do, so they read on any wallpaper.
            bare && (mono || bmp == null) ->
                IconBadge(size) {
                    if (bmp != null) {
                        Image(bmp, spoken, Modifier.size(size * 0.75f), colorFilter = ColorFilter.tint(Palette.accent))
                    } else {
                        BasicText(initials(label), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Body))
                    }
                }
            bmp != null -> Image(bmp, spoken, Modifier.size(size * if (mono) 0.85f else 1f), colorFilter = if (mono) ColorFilter.tint(Palette.accent) else null)
            else -> BasicText(initials(label), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Large))
        }
        if (menuOpen) AppActions(actions(), shortcuts, onDismiss = { menuOpen = false })
    }
}

/** [limit] apps the user knows, for the Style menu's previews: the recent ones, topped up from the drawer's. */
internal fun familiarApps(
    runtime: Runtime,
    limit: Int,
): List<AppHit> = (recentApps(runtime, limit) + allApps(runtime)).distinctBy { it.key }.take(limit)

/** The apps opened lately, last opened first; ones not installed (or not loaded yet) are skipped. */
internal fun recentApps(
    runtime: Runtime,
    limit: Int,
): List<AppHit> {
    val byKey = allApps(runtime).shown(runtime.hiddenApps.keys, showHidden = false).associateBy { it.key }
    return runtime.usage.recent.mapNotNull { byKey[it] }.take(limit)
}

@Composable
internal fun SearchField(
    query: String,
    onChange: (String) -> Unit,
    onGo: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val style = Mono.copy(fontSize = TextSize.Item)
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(Palette.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier =
            modifier
                .fillMaxWidth()
                .clip(LauncherStyle.shape(24.dp))
                .background(Palette.surface)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box {
                if (query.isEmpty()) BasicText(placeholder, style = style.copy(color = Palette.muted))
                inner()
            }
        },
    )
}

/** The long-press popup: sits above the cell (below it when there's no room), closes on outside tap. */
@Composable
private fun AppActions(
    actions: List<Pair<String, () -> Unit>>,
    shortcuts: () -> ShortcutMenu?,
    onDismiss: () -> Unit,
) {
    val (menu, offered) = offeredShortcuts(Unit, shortcuts)
    if (offered == null) return
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    // From the screen's sides, as far as home's tiles are.
    val edge = with(LocalDensity.current) { EdgeGap.roundToPx() }
    val position =
        remember(gap, edge) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(edge, (windowSize.width - edge - popupContentSize.width).coerceAtLeast(edge))
                    val above = anchorBounds.top - popupContentSize.height - gap
                    val y = if (above >= 0) above else anchorBounds.bottom + gap
                    return IntOffset(x, y)
                }
            }
        }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        MenuItems(actions, onDismiss, minWidth = 160.dp, itemPadding = 12.dp, fontSize = TextSize.Item) {
            if (menu != null) ShortcutRows(offered, menu, onDismiss, TextSize.Item)
        }
    }
}

/** The gear that opens settings, in the drawer and the home menu; a dot while system updates wait. */
@Composable
internal fun SettingsButton(
    runtime: Runtime,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(LauncherStyle.shape(22.dp))
            .background(Palette.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(Icons.Gear, "settings", Modifier.size(22.dp), colorFilter = ColorFilter.tint(Palette.accent))
        // Updates are waiting: settings › system updates lists them.
        if ((runtime.updates.pending?.count ?: 0) > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(8.dp)
                    .background(Palette.red, CircleShape),
            )
        }
    }
}

// Owl panels (upstream 0.10.0): what android.drawer, appAction and a panel in the drawer's place use.

/** What `android.drawer` and `android.recent` call its kind. */
internal val AppHit.kind: String
    get() =
        when (this) {
            is AppHit.Android -> "android"
            is AppHit.Linux -> "linux"
            is AppHit.Owl -> "owl"
            is AppHit.Shortcut -> "shortcut"
        }

/** `work` or `clone` for an app or shortcut in a profile beside the launcher's own, else null. */
internal val AppHit.profileKind: String?
    get() =
        when (this) {
            is AppHit.Android -> app.profile?.let { if (app.clone) "clone" else "work" }
            is AppHit.Shortcut -> shortcut.profile?.let { if (shortcut.clone) "clone" else "work" }
            else -> null
        }

/**
 * The long-press menu's entries, as the words `appAction(word, key)` takes: pin to home, for owl apps editing,
 * sharing and removing, for Android apps their info and uninstall, and for shortcuts removing them.
 */
internal fun AppHit.actionWords(): List<String> =
    when (this) {
        is AppHit.Linux -> listOf("pin")
        is AppHit.Owl -> listOf("pin", "edit", "share", "remove")
        is AppHit.Android -> listOfNotNull("pin", "info", if (app.system) null else "uninstall")
        is AppHit.Shortcut -> listOf("pin", "remove")
    }

/** Runs the menu entry [word], from the menu or a widget's `appAction(...)`; one the app's menu doesn't offer does nothing. */
internal fun AppHit.act(
    word: String,
    handlers: AppHandlers,
) {
    if (word !in actionWords()) return
    when (word) {
        "pin" -> handlers.pin(listOf(this))
        "info" -> (this as? AppHit.Android)?.let { handlers.info(it.app) }
        "uninstall" -> (this as? AppHit.Android)?.let { handlers.uninstall(it.app) }
        "remove" ->
            when (this) {
                is AppHit.Shortcut -> handlers.remove(shortcut)
                is AppHit.Owl -> handlers.removeOwl(spec)
                else -> {}
            }
        "edit" -> (this as? AppHit.Owl)?.let { handlers.edit(it.spec) }
        "share" -> (this as? AppHit.Owl)?.let { handlers.share(it.spec) }
    }
}

/** Pulling down past the top of a list in the drawer drags it closed, like stock drawers; for its list, and a panel's in its place. */
@Composable
internal fun rememberDrawerPull(state: LauncherState): NestedScrollConnection {
    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    return remember(state, screenHeight) {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Half-closed and scrolling back up: reopen before scrolling the list.
                if (source != NestedScrollSource.UserInput || available.y >= 0f || state.drawer.value >= 1f) return Offset.Zero
                state.dragBy(-available.y / screenHeight)
                return Offset(0f, available.y)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                state.dragBy(-available.y / screenHeight)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (state.drawer.value >= 1f) return Velocity.Zero
                state.settle(available.y)
                return available
            }
        }
    }
}
