package dev.omalauncher.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import java.io.File
import kotlin.math.abs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.selected
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import dev.omalauncher.landingSlot
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.ceil
import androidx.compose.foundation.layout.wrapContentHeight
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

/** The drawer's tabs, each listing one profile's apps while the bar is empty. */
enum class DrawerTab(
    val label: String,
) {
    Personal("personal"),
    Work("work"),
}

/** The tabs the empty bar offers: personal, then work while there's a work profile. */
internal fun drawerTabs(work: Boolean) = listOfNotNull(DrawerTab.Personal, DrawerTab.Work.takeIf { work })

/**
 * Where a sideways swipe of [dx] px from [current] lands: a finger moving left brings the next tab,
 * as pages turn. Swipes shorter than [threshold] and past the ends stay put.
 */
internal fun swipedTab(
    tabs: List<DrawerTab>,
    current: DrawerTab,
    dx: Float,
    threshold: Float,
): DrawerTab {
    if (abs(dx) < threshold) return current
    val i = tabs.indexOf(current)
    return tabs.getOrNull(if (dx < 0) i + 1 else i - 1) ?: current
}

/** How far a sideways swipe over the drawer goes before it turns the tab. */
private val SwipeDistance = 56.dp

/** The tabs the empty bar offers right now, and the one showing: the one picked, or personal while that one's gone. */
private fun shownTabs(
    runtime: Runtime,
    state: LauncherState,
): Pair<List<DrawerTab>, DrawerTab> {
    val tabs = drawerTabs(work = runtime.androidApps.workProfiles.isNotEmpty())
    return tabs to (state.tab.takeIf { it in tabs } ?: DrawerTab.Personal)
}

/**
 * Swiping sideways turns to the next or previous tab, as stock drawers' tabs do, while the bar is empty.
 * On the list and on the scrim behind the panel, so it works anywhere, even over a short list.
 */
internal fun Modifier.swipeTabs(
    runtime: Runtime,
    state: LauncherState,
) = pointerInput(runtime, state) {
    val threshold = SwipeDistance.toPx()
    var dragged = 0f
    detectHorizontalDragGestures(
        onDragStart = { dragged = 0f },
        onDragEnd = {
            val (tabs, tab) = shownTabs(runtime, state)
            if (state.query.isBlank()) swipedTab(tabs, tab, dragged, threshold).takeIf { it != tab }?.let { state.tab = it }
        },
    ) { change, amount ->
        change.consume()
        dragged += amount
    }
}

/** What the bar's query matched: apps best first, and whether Enter opens the first rather than asking the agent. */
internal class BarSearch(
    val hits: List<AppHit>,
    val opensApp: Boolean,
) {
    /** The result Enter acts on: an index into [hits], or -1 for the agent. */
    fun highlighted(picked: Int?) = picked?.coerceIn(-1, hits.lastIndex) ?: if (opensApp) 0 else -1
}

private fun allApps(runtime: Runtime): List<AppHit> =
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

/** The picked apps among [apps], in the order they were picked. */
internal fun picked(
    apps: List<AppHit>,
    selection: Set<String>,
): List<AppHit> {
    val byKey = apps.associateBy { it.key }
    return selection.mapNotNull { byKey[it] }
}

/** Whether the picked apps can be hidden (one of them shows) and shown again (one of them is hidden). */
internal fun hideChoices(
    selection: Set<String>,
    hidden: Set<String>,
) = selection.any { it !in hidden } to selection.any { it in hidden }

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
    /** The dock above the bar: what the menus add to, remove from and move within. */
    val dock: Dock,
    /** Opens the app's customize dialog: its name, icon and drawer group. */
    val customize: (AppHit) -> Unit,
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

@Composable
private fun HitCell(
    runtime: Runtime,
    state: LauncherState,
    hit: AppHit,
    handlers: AppHandlers,
    selected: Boolean = false,
    /** Whether apps are being picked: then a tap picks or unpicks this one with [onToggle]. */
    picking: Boolean = false,
    checked: Boolean = false,
    onToggle: (AppHit) -> Unit = {},
    iconSize: Dp = 52.dp,
    showLabel: Boolean = true,
    new: Boolean = false,
) {
    val (icon, mask) = hit.icons(runtime)
    // Long-press and drag onto home or the dock; not while picking, where a long-press picks.
    Box(Modifier.dragsApp(runtime, state, hit, fromDrawer = true, enabled = !picking)) {
        AppCell(
            hit.name(runtime),
            icon,
            mask,
            onClick = { handlers.launch(hit) },
            actions = { hit.actions(handlers, selectable = true) },
            shortcuts = { hit.shortcutMenu(handlers) },
            selected = selected,
            spoken = hit.spoken,
            paused = hit.paused(runtime),
            onToggle = if (picking) ({ onToggle(hit) }) else null,
            checked = checked,
            hidden = hit.key in runtime.hiddenApps,
            iconSize = iconSize,
            showLabel = showLabel,
            new = new,
            dragged = state.dockDrag?.key == hit.key,
        )
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

/** The apps opened lately, last opened first; ones not installed (or not loaded yet) are skipped. */
private fun recentApps(
    runtime: Runtime,
    limit: Int,
): List<AppHit> {
    val byKey = allApps(runtime).shown(runtime.hiddenApps.keys, showHidden = false).associateBy { it.key }
    return runtime.usage.recent.mapNotNull { byKey[it] }.take(limit)
}

/**
 * The bar's results, growing up from it. Empty query: Linux and Android apps to browse.
 * Typing: matches best first, and a row that asks the agent.
 */
@Composable
internal fun AppPanel(
    runtime: Runtime,
    state: LauncherState,
    search: BarSearch,
    highlighted: Int,
    handlers: AppHandlers,
    onAsk: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Cheap when nothing changed: the list is only replaced if the .desktop entries differ.
    LaunchedEffect(Unit) { runtime.refreshApps() }
    val linuxState by runtime.bootstrap.state.collectAsState()
    val query = state.query
    val apps = runtime.androidApps
    // With a work profile, the empty bar lists one profile's apps at a time, as stock drawers' tabs do.
    val (tabs, tab) = shownTabs(runtime, state)
    val toggle = remember(state) { { hit: AppHit -> state.selection = state.selection?.let { toggled(it, hit.key) } } }
    // Read as a yes or no here, so picking one more app only recomposes its row and the bar, not the whole panel.
    val picking by remember(state) { derivedStateOf { state.selection != null } }
    // Hidden apps still installed: one hidden and then removed from the phone isn't counted, or offered.
    val hiddenCount by remember(runtime) { derivedStateOf { allApps(runtime).count { it.key in runtime.hiddenApps } } }
    // Showing the last hidden app again turns showing hidden off, so the next one hidden goes out of sight.
    LaunchedEffect(hiddenCount) { if (hiddenCount == 0) state.showHidden = false }

    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    // Pulling down past the end of the list drags the panel closed, like stock drawers.
    val pullToClose =
        remember(state, screenHeight) {
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

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // Full width: a wide screen fits more icons in each row rather than leaving the sides empty.
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, start = 12.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Typing searches every profile, so the tabs step aside.
                if (tabs.size > 1 && query.isBlank()) ProfileTabs(tabs, tab, onPick = { state.tab = it })
                // Takes only the room the tabs and the settings button leave, so a long label never squeezes the button.
                Box(Modifier.weight(1f).padding(end = 8.dp), contentAlignment = Alignment.CenterEnd) {
                    // Lists the hidden apps too, dimmed, so they can be shown again; only while there are some.
                    if (hiddenCount > 0) {
                        val context = LocalContext.current
                        BracketAction(
                            if (state.showHidden) "hide hidden ($hiddenCount)" else "show hidden ($hiddenCount)",
                            if (state.showHidden) Palette.accent else Palette.muted,
                        ) {
                            when {
                                // Locked (drawer.lock-hidden): the fingerprint, face or screen lock first.
                                !state.showHidden && LauncherStyle.drawerLock ->
                                    unlock(context, "show hidden apps") { if (state.drawer.value > 0.01f) state.showHidden = true }
                                else -> state.showHidden = !state.showHidden
                            }
                            // Hidden apps picked while listed would otherwise stay picked out of sight.
                            if (!state.showHidden) state.selection = state.selection?.let { (it - runtime.hiddenApps.keys).ifEmpty { null } }
                        }
                    }
                }
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(LauncherStyle.shape(22.dp))
                        .background(Palette.surface)
                        .clickable(onClick = onSettings),
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
            // Only as tall as its rows, so taps above a short list reach the scrim behind and close the panel.
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Bottom) {
                // Clipped, so rows scrolled up or stretched by an overscroll stop short of the settings button.
                BoxWithConstraints(Modifier.weight(1f, fill = false).clipToBounds().swipeTabs(runtime, state)) {
                    val iconSize = LauncherStyle.drawerIcon
                    // As many as fit, each a little wider than its icon, unless style sets how many.
                    val columns = LauncherStyle.drawerColumns ?: (maxWidth / (iconSize + 24.dp)).toInt().coerceAtLeast(1)
                    // A tab slides in from the side it was swiped or tapped towards; search results just take the list's place.
                    AnimatedContent(
                        targetState = tab.takeIf { query.isBlank() },
                        transitionSpec = {
                            val from = initialState
                            val to = targetState
                            if (from == null || to == null) {
                                (EnterTransition.None togetherWith ExitTransition.None).using(SizeTransform { _, _ -> snap() })
                            } else {
                                val sign = if (to.ordinal > from.ordinal) 1 else -1
                                slideInHorizontally { it * sign } togetherWith slideOutHorizontally { -it * sign }
                            }
                        },
                        contentAlignment = Alignment.BottomCenter,
                        label = "drawer tab",
                    ) { shown ->
                        val listState = rememberLazyListState()
                        val scope = rememberCoroutineScope()
                        // New results start at the bar, where the best match is.
                        if (shown == null) LaunchedEffect(search) { listState.scrollToItem(0) }
                        val now = remember(shown) { System.currentTimeMillis() }
                        // Search results bottom-up, so the best match sits right above the bar; the full list reads
                        // top-down from its start, like stock drawers, so a drag down there closes the panel right away.
                        val linuxInstalled = linuxState == LinuxState.Installed
                        // Worked out again only when what they read changes (apps, usage, groups, hidden, style).
                        val listed by remember(shown, columns, linuxInstalled, now) {
                            derivedStateOf { shown?.let { drawerEntries(runtime, state, it, linuxInstalled, columns, now) } }
                        }
                        val (entries, railFrom) =
                            listed ?: (search.hits.chunked(columns).mapIndexed { r, cells -> RowEntry("q:$r", cells, if (picking) -1 else highlighted - r * columns) } to -1)
                        val letters = if (railFrom >= 0 && LauncherStyle.drawerLetters) letterIndex(entries, { it.name(runtime) }, railFrom) else emptyMap()
                        val rail = letters.size > 1
                        Box {
                            LazyColumn(
                                state = listState,
                                reverseLayout = shown == null,
                                modifier = Modifier.fillMaxWidth().padding(end = if (rail) RailWidth else 0.dp).nestedScroll(pullToClose),
                                contentPadding = PaddingValues(top = 8.dp, bottom = 4.dp),
                            ) {
                                items(entries, key = { it.key }) { entry ->
                                    when (entry) {
                                        is HeaderEntry -> Section(entry.title, entry.actions)
                                        is RowEntry -> {
                                            val selection = state.selection
                                            Row(Modifier.fillMaxWidth()) {
                                                entry.cells.forEachIndexed { i, hit ->
                                                    // By app: a list shifting under a carried app keeps its gesture.
                                                    key(hit.key) {
                                                    Box(Modifier.weight(1f)) {
                                                        HitCell(
                                                            runtime,
                                                            state,
                                                            hit,
                                                            handlers,
                                                            selected = i == entry.selected,
                                                            picking = selection != null,
                                                            checked = selection?.contains(hit.key) == true,
                                                            onToggle = toggle,
                                                            iconSize = iconSize,
                                                            // Search results always say what matched.
                                                            showLabel = shown == null || LauncherStyle.drawerLabels,
                                                            new = LauncherStyle.drawerNewBadge && isNew(hit.installed, runtime.usage.score(hit.key, now), now),
                                                        )
                                                    }
                                                    }
                                                }
                                                repeat(columns - entry.cells.size) { Spacer(Modifier.weight(1f)) }
                                            }
                                        }
                                        LinuxOfferEntry ->
                                            // Linux apps would be listed here; without Linux, one quiet offer instead.
                                            Row(Modifier.padding(top = 12.dp, bottom = 4.dp, start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                BasicText("linux apps", Modifier.weight(1f), style = Mono.copy(color = Palette.muted))
                                                BracketAction(if (linuxState is LinuxState.Installing) "installing…" else "install linux", Palette.accent) {
                                                    state.closeNow()
                                                    state.show(SettingsPage.Linux)
                                                }
                                            }
                                    }
                                }
                            }
                            // Beside the list, as tall as it is; only the rail itself takes touches.
                            if (rail) {
                                Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                                    LetterRail(letters.keys.toList(), Modifier.fillMaxHeight().width(RailWidth)) { letter ->
                                        letters[letter]?.let { scope.launch { listState.scrollToItem(it) } }
                                    }
                                }
                            }
                        }
                    }
                }
                if (picking) {
                    SelectionBar(runtime, state, handlers)
                } else if (query.isNotBlank()) {
                    AskRow(runtime, linuxState == LinuxState.Installed, query, highlighted == -1, onAsk)
                }
            }
        }
    }
}

/**
 * The rows of a drawer tab: on the personal one the apps used most (style's drawer.suggestions), then owl apps,
 * Linux's apps (or the offer to install Linux), shortcuts and Android's apps, under headings or as one list (drawer.sections),
 * each in the drawer's sort order. Also the index past which the letter rail counts rows.
 */
private fun drawerEntries(
    runtime: Runtime,
    state: LauncherState,
    tab: DrawerTab,
    linux: Boolean,
    columns: Int,
    now: Long,
): Pair<List<DrawerEntry>, Int> {
    val apps = runtime.androidApps
    val hidden = runtime.hiddenApps.keys
    val score = { hit: AppHit -> runtime.usage.score(hit.key, now) }
    val groups = runtime.drawerGroups.groups
    val grouped = groups.flatMapTo(HashSet()) { it.keys }
    // An app in a group is listed there, not under its usual heading.
    // Groups are the personal tab's: there an app in one is listed in it, not under its usual heading.
    fun ready(hits: List<AppHit>) =
        sortApps(hits.filter { tab == DrawerTab.Work || it.key !in grouped }.shown(hidden, state.showHidden), LauncherStyle.drawerSort, { it.name(runtime) }, score)
    val sections = LauncherStyle.drawerSections
    val out = mutableListOf<DrawerEntry>()
    if (tab == DrawerTab.Work) {
        // Above the apps, as on the personal tab.
        out.section("w-shortcuts", "shortcuts", ready(apps.shortcuts.filter { it.work }.map { AppHit.Shortcut(it) }), columns, titled = sections)
        val paused = apps.workProfiles.any { apps.paused(it) }
        val workApps = out.size
        // Titled even as one list: the heading holds the pause switch.
        out.section(
            "w-apps",
            if (paused) "work apps paused" else "work apps",
            ready(apps.apps.filter { it.work }.map { AppHit.Android(it) }),
            columns,
            listOf((if (paused) "turn on" else "pause") to { apps.pauseWork(!paused) }),
        )
        return out to workApps
    }
    if (LauncherStyle.drawerSuggestions) {
        val top = allApps(runtime).filter { !it.isWork }.shown(hidden, false).filter { score(it) > 0.0 }.sortedByDescending(score).take(columns)
        out.section("most", "most used", top, columns)
    }
    // Your groups, in the order made, each in the drawer's sort order; an empty one stays until it's deleted.
    if (groups.isNotEmpty()) {
        val byKey = allApps(runtime).associateBy { it.key }
        for (g in groups) {
            val hits = sortApps(g.keys.mapNotNull { byKey[it] }.filter { !it.isWork }.shown(hidden, state.showHidden), LauncherStyle.drawerSort, { it.name(runtime) }, score)
            out.section("g:${g.name}", g.name, hits, columns, listOf("rename" to { state.renamingGroup = g.name }, "ungroup" to { runtime.drawerGroups.delete(g.name) }))
        }
    }
    val from = out.size
    // Owl apps open full screen over home: from the store, or made with an agent.
    val owlApps = runtime.widgets.apps().map { AppHit.Owl(it) }
    if (sections) out.section("apps", "apps", ready(owlApps), columns)
    if (!linux) out += LinuxOfferEntry
    val linuxApps = if (linux) runtime.linuxApps.apps.map { AppHit.Linux(it) } else emptyList()
    // Clones too, as stock drawers list them: second copies of the owner's apps.
    val shortcuts = apps.shortcuts.filter { !it.work }.map { AppHit.Shortcut(it) }
    val android = apps.apps.filter { !it.work }.map { AppHit.Android(it) }
    var rail = from
    if (sections) {
        out.section("linux", "linux", ready(linuxApps), columns)
        // Usually few and just added: between the Linux and the Android apps, not past all the Android ones.
        out.section("shortcuts", "shortcuts", ready(shortcuts), columns)
        // With headings each section runs A to Z on its own: the rail goes through Android's, the long one.
        rail = out.size
        out.section("android", "android", ready(android), columns)
    } else {
        out.section("all", "apps", ready(owlApps + linuxApps + shortcuts + android), columns, titled = false)
    }
    return out to rail
}

/** How wide the letter rail is beside the drawer's list. */
private val RailWidth = 22.dp

/** The letters beside the drawer's list: touch or slide along it to jump to the apps that start with one. */
@Composable
private fun LetterRail(
    letters: List<Char>,
    modifier: Modifier,
    onPick: (Char) -> Unit,
) {
    var touched by remember { mutableStateOf<Char?>(null) }
    val haptics = LocalHapticFeedback.current
    val pick by rememberUpdatedState(onPick)
    var height by remember { mutableIntStateOf(0) }
    // A line of the rail's text, as Android's font size makes it: closer than that, letters are skipped.
    val legible = with(LocalDensity.current) { TextSize.Small.toPx() * 1.4f }
    Column(
        modifier.onSizeChanged { height = it.height }.pointerInput(letters) {
            fun at(y: Float) {
                val c = letters[(y / size.height * letters.size).toInt().coerceIn(0, letters.lastIndex)]
                if (c == touched) return
                touched = c
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                pick(c)
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                at(down.position.y)
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    at(change.position.y)
                }
                touched = null
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Each letter has its own slot, as touches map them. Too many to read apart: every other one (or fewer) is drawn,
        // and the one touched; the others' slots stay blank but still take touches.
        val step = if (height <= 0) 1 else ceil(letters.size * legible / height).toInt().coerceAtLeast(1)
        for ((i, c) in letters.withIndex()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (i % step == 0 || c == touched) {
                    // Taller than its slot when crowded: it spills over the blank slots beside it rather than being cut.
                    BasicText(
                        c.toString(),
                        Modifier.wrapContentHeight(unbounded = true),
                        style = Mono.copy(color = if (c == touched) Palette.accent else Palette.muted, fontSize = TextSize.Small),
                    )
                }
            }
        }
    }
}

/**
 * While picking apps: how many, and what to do with them all. Adding to home skips the ones already there, which
 * pinning again would move out of their spot or folder.
 */
@Composable
private fun SelectionBar(
    runtime: Runtime,
    state: LauncherState,
    handlers: AppHandlers,
) {
    val selection = state.selection ?: return
    val (canHide, canShow) = hideChoices(selection, runtime.hiddenApps.keys)
    val home = runtime.widgets.shortcuts
    val onAdd = {
        handlers.pin(
            picked(allApps(runtime), selection).filterNot {
                home.pinned(
                    when (it) {
                        is AppHit.Linux -> home.keyOf(it.app)
                        is AppHit.Android -> home.keyOf(it.app)
                        is AppHit.Shortcut -> home.keyOf(it.shortcut)
                        is AppHit.Owl -> home.keyOf(it.spec)
                    },
                )
            },
        )
    }
    val onHide = { hide: Boolean ->
        handlers.hide(selection, hide)
        state.selection = null
    }
    // Fills the dock's free slots in the order picked; the drawer closes to show it.
    val dock = runtime.dock
    val onDock: () -> Unit = {
        dock.add(picked(allApps(runtime), selection).map { it.key })
        state.selection = null
        state.close()
    }
    Column(
        Modifier
            .padding(bottom = 6.dp)
            .fillMaxWidth()
            .frame(2.dp, Palette.accent, Palette.background)
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("${selection.size} picked", Modifier.weight(1f).padding(start = 8.dp), style = Mono.copy(color = Palette.foreground))
            BracketAction("cancel", Palette.muted) { state.selection = null }
        }
        // Adding and hiding on rows of their own, so all of them fit a narrow screen; each still scrolls at a large font.
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            BracketAction("add to home", Palette.accent, onAdd)
            if (LauncherStyle.dockShown && !dock.full) BracketAction("add to dock", Palette.accent, onDock)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            // Groups are the personal tab's: none for work apps.
            if (picked(allApps(runtime), selection).any { !it.isWork }) {
                BracketAction("group", Palette.accent) { state.grouping = picked(allApps(runtime), selection).filter { !it.isWork }.map { it.key }.toSet() }
            }
            if (canHide) BracketAction("hide", Palette.accent) { onHide(true) }
            if (canShow) BracketAction("unhide", Palette.accent) { onHide(false) }
        }
    }
}

/** The one line above the bar that sends what's typed to the agent. */
@Composable
private fun AskRow(
    runtime: Runtime,
    linux: Boolean,
    query: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val agent = runtime.agents.default
    val label =
        when {
            !linux -> "ask an agent · install linux"
            agent == null -> "ask an agent · install one"
            else -> "ask ${agent.bin}: " + query.trim().replace('\n', ' ')
        }
    Row(
        Modifier
            .padding(bottom = 6.dp)
            .fillMaxWidth()
            .then(if (selected) Modifier.frame(2.dp, Palette.accent, Palette.background) else Modifier.clip(LauncherStyle.shape()))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("⏎ ", style = Mono.copy(color = if (selected) Palette.accent else Palette.muted))
        BasicText(
            label,
            style = Mono.copy(color = if (selected) Palette.foreground else Palette.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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

@Composable
private fun Section(
    title: String,
    /** Labels and handlers of bracketed actions at the right end, as the install linux offer has. */
    actions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    if (actions.isEmpty()) {
        SectionLabel(title, Modifier.padding(start = 8.dp))
    } else {
        Row(Modifier.padding(top = 12.dp, bottom = 4.dp, start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(title, Modifier.weight(1f), style = Mono.copy(color = Palette.muted))
            for ((label, onClick) in actions) BracketAction(label, Palette.accent, onClick)
        }
    }
}

/** Stock drawers' personal | work switch over which profile's apps the empty bar lists. */
@Composable
private fun ProfileTabs(
    tabs: List<DrawerTab>,
    chosen: DrawerTab,
    onPick: (DrawerTab) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (tab in tabs) {
            val picked = tab == chosen
            BasicText(
                tab.label,
                Modifier
                    .clip(LauncherStyle.shape(18.dp))
                    .then(if (picked) Modifier.background(Palette.surface) else Modifier)
                    .clickable { onPick(tab) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                style = Mono.copy(color = if (picked) Palette.accent else Palette.muted),
            )
        }
    }
}

/**
 * One drawer entry: a 52dp icon over a label two lines tall. Linux apps without an icon get initials.
 * Long-press opens a popup of [actions] above the icon, like stock launchers.
 */
@Composable
private fun AppCell(
    label: String,
    icon: ImageBitmap?,
    /** The icon's silhouette, shown instead in the monochrome icon style. */
    mask: ImageBitmap?,
    onClick: () -> Unit,
    /** Built when the menu opens, as in [StripCell]. */
    actions: () -> List<Pair<String, () -> Unit>>,
    /** As in [StripCell]. */
    shortcuts: () -> ShortcutMenu? = { null },
    /** What Enter in the bar would open: outlined in the accent color. */
    selected: Boolean = false,
    /** As in [AppHit.spoken]. */
    spoken: String = label,
    /** As in [AppHit.paused]: drawn dimmed, like stock launchers. */
    paused: Boolean = false,
    /** While picking several apps: a tap or long-press picks or unpicks this one instead of opening it or its menu. */
    onToggle: (() -> Unit)? = null,
    /** Picked among several: framed and ticked. */
    checked: Boolean = false,
    /** Hidden, listed only while the drawer shows hidden apps: drawn dimmed. */
    hidden: Boolean = false,
    iconSize: Dp = 52.dp,
    /** The name under the icon; without it a row is just as tall as its icons. */
    showLabel: Boolean = true,
    /** Installed lately and not opened since: a dot in the accent color by its icon. */
    new: Boolean = false,
    /** Being dragged onto home or the dock: the long-press menu gives way to the drag. */
    dragged: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(dragged) { if (dragged) menuOpen = false }
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (paused || hidden) PausedAlpha else 1f)
            .clip(LauncherStyle.shape(16.dp))
            .then(if (selected || checked) Modifier.frame(2.dp, Palette.accent) else Modifier)
            .then(if (onToggle != null) Modifier.semantics { this.selected = checked } else Modifier)
            .combinedClickable(
                onClick = onToggle ?: onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (onToggle != null) onToggle() else menuOpen = true
                },
            ).padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            AppIcon(label, icon, mask, spoken, iconSize)
            if (new) Box(Modifier.align(Alignment.TopStart).size(10.dp).background(Palette.accent, CircleShape))
            if (checked) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(20.dp).background(Palette.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("✓", style = Mono.copy(color = Palette.background, fontSize = TextSize.Small))
                }
            }
        }
        if (showLabel) {
            Spacer(Modifier.height(6.dp))
            BasicText(
                label,
                style = Mono.copy(fontSize = TextSize.Small, textAlign = TextAlign.Center),
                // Always two lines tall, so a row keeps its height whichever apps fill it while typing.
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (menuOpen) {
            AppActions(actions(), shortcuts, onDismiss = { menuOpen = false })
        }
    }
}

/** A drawer entry's icon, [size] across (52dp by default): its silhouette or bitmap, or initials when it has none. */
@Composable
private fun AppIcon(
    label: String,
    icon: ImageBitmap?,
    mask: ImageBitmap?,
    spoken: String,
    size: Dp = 52.dp,
) {
    val mono = LauncherStyle.monochromeIcons
    // Badges are circles when rounded, squares with Omarchy's sharp edges.
    val badge = LauncherStyle.shape(size / 2)
    if (mono && mask != null) {
        Box(Modifier.size(size).clip(badge).background(Palette.surface), contentAlignment = Alignment.Center) {
            Image(mask, spoken, Modifier.size(size * 36 / 52), colorFilter = ColorFilter.tint(Palette.accent))
        }
    } else if (!mono && icon != null) {
        Image(icon, spoken, Modifier.size(size))
    } else {
        Box(Modifier.size(size).clip(badge).background(Palette.surface), contentAlignment = Alignment.Center) {
            BasicText(initials(label), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Title * (size / 52.dp)))
        }
    }
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
