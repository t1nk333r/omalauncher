package dev.omalauncher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import dev.omalauncher.Runtime

/** App panel position (above the bar): 0 = closed, 1 = open. Follows the finger while dragging. */
class LauncherState {
    val drawer = Animatable(0f)

    /** Opens a screen of the settings activity, set by the launcher activity; see [show]. */
    var onSettings: (SettingsPage, Int) -> Unit = { _, _ -> }

    /** The Android widgets to pick from, from the home menu. */
    var appWidgetsOpen by mutableStateOf(false)

    /** Opens settings, or one of its screens on its own; the store puts widgets on the page showing now. */
    fun show(page: SettingsPage = SettingsPage.Settings) = onSettings(page, currentPage)

    /** The widget whose share panel is open: a QR code and link, or the store. */
    var sharing by mutableStateOf<dev.omalauncher.widgets.WidgetSpec?>(null)

    /** A widget link that was opened (scanned from a QR code), waiting for the user to read it and add it. */
    var shared by mutableStateOf<String?>(null)

    /** The menu from long-pressing empty home space. */
    var homeMenuOpen by mutableStateOf(false)

    /** The open drawer or a full-screen panel hides the home screen; animated widgets stop ticking under it. */
    val homeCovered: Boolean
        get() = drawer.value > 0.99f || appWidgetsOpen || shared != null || sharing != null

    /** Closes every overlay screen (the home menu, the Android widget picker, widget sharing). */
    fun closeScreens() {
        appWidgetsOpen = false
        homeMenuOpen = false
        shared = null
        sharing = null
    }

    /** Key of the home tile in edit mode (handles and action bar shown), if any. */
    var editing by mutableStateOf<String?>(null)

    /** How far the home pages are scrolled, 0 (first) to 1 (last); the background slides along. */
    var wallpaperOffset by mutableFloatStateOf(0.5f)

    /**
     * The bar's height in px with its gaps above and below, as last laid out over home (0 until then). A large
     * system font makes it taller than [PromptBarHeight]; home ends its grid above what it really takes.
     */
    var barHeight by mutableIntStateOf(0)

    /**
     * Only faces (`face "…" { }`), or the empty one waiting left of page 1, show on home: they run under the
     * system bars, so the bands behind those make way. The strip and the bar slide away by [barShift].
     */
    var faceShowing by mutableStateOf(false)

    /**
     * Where the strip and the bar stand across, in screen widths ([barShift]): they belong to the tile pages and
     * slide with them, out past a face. Read while drawing, so a swipe moves them without recomposing.
     */
    var barShift by mutableStateOf<() -> Float>({ 0f })

    /** Only faces show, and all of them say `statusBar: false` now. */
    var statusBarAway by mutableStateOf(false)

    /** [statusBarAway], and nothing over it: no drawer, menu or panel. Then the status bar hides. */
    val fullScreen: Boolean
        get() = statusBarAway && drawer.value < 0.01f && !homeMenuOpen && shared == null && sharing == null

    /** Home page currently showing (1-based); the drawer pins apps there. */
    var currentPage by mutableStateOf(1)

    /** The drawer's tab; it stays picked the next time the drawer opens. */
    var tab by mutableStateOf(DrawerTab.Personal)

    /** Keys of the apps picked in the drawer to add to home or hide together; null when not picking. Closing forgets it. */
    var selection by mutableStateOf<Set<String>?>(null)

    /** The dock slot an app carried on home is over, for the dock to show where it would go; null otherwise. */
    var dockHover by mutableStateOf<Int?>(null)

    /** A dock app being dragged out; null while none is. Set as the drag starts and ends, not as it moves. */
    internal var dockDrag by mutableStateOf<AppHit?>(null)

    /** [dockDrag] came from the drawer: it steps aside for home and the dock while the app is carried. */
    var drawerDrag by mutableStateOf(false)

    /** Where the dock is in the window, as last laid out; empty while it isn't on screen. */
    var dockBounds by mutableStateOf(androidx.compose.ui.geometry.Rect.Zero)

    /** Where the finger dragging [dockDrag] is in the window; read only where the dragged icon is placed. */
    var dockDragAt by mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)

    /** The page and 1x1 cell of home under a point in the window, as the grid last laid out; null off the grid. */
    var homeCellAt: (androidx.compose.ui.geometry.Offset) -> Pair<Int, dev.omalauncher.widgets.Cell>? = { null }

    /** The app whose customize dialog (name, icon, drawer group) is open, from its long-press menu. */
    internal var customizing by mutableStateOf<AppHit?>(null)

    /** Apps picked to put in a drawer group, while that dialog is open. */
    var grouping by mutableStateOf<Set<String>?>(null)

    /** The drawer group being renamed, from its heading. */
    var renamingGroup by mutableStateOf<String?>(null)

    /** Whether the drawer lists hidden apps too, dimmed, to show them again. Closing turns it off. */
    var showHidden by mutableStateOf(false)

    private var typed by mutableStateOf("")

    /** What's in the bar: an app to find, or a prompt for the agent. */
    var query: String
        get() = typed
        set(value) {
            if (value != typed) picked = null
            typed = value
        }

    /** The result chosen with Tab or the arrow keys (-1 = ask the agent); null follows the ranking. Reset by typing. */
    var picked by mutableStateOf<Int?>(null)

    /** Asks the prompt bar to start typing (Super+A) or to send (the ask row); consumed by the bar. */
    var promptRequest by mutableStateOf<PromptRequest?>(null)

    /** Home page to scroll to (e.g. from Super+number); consumed by the pager. */
    var requestedPage by mutableStateOf<Int?>(null)

    /** A composition scope: animations need its frame clock. Null until the UI is composed. */
    private var composeScope: CoroutineScope? = null
    private val scope get() = composeScope ?: CoroutineScope(Dispatchers.Main)

    @Composable
    fun bindScope() {
        val s = rememberCoroutineScope()
        SideEffect { composeScope = s }
    }

    /** Animates when composed; before that (e.g. Home pressed during startup) just jumps. */
    private fun animate(
        target: Float,
        stiffness: Float,
    ) = composeScope?.launch { drawer.animateTo(target, spring(stiffness = stiffness)) }
        ?: scope.launch { drawer.snapTo(target) }

    /** Drawer gestures leave the keyboard down; search and explicit opens focus the prompt. */
    var focusSearch by mutableStateOf(true)

    fun open(search: Boolean = true): kotlinx.coroutines.Job {
        focusSearch = search
        return animate(1f, Spring.StiffnessMediumLow)
    }

    fun close() = animate(0f, Spring.StiffnessMedium)

    /**
     * Closes without animating. Used when leaving the launcher: animations pause
     * while it's in the background and could leave an invisible drawer catching touches.
     */
    fun closeNow() =
        scope.launch {
            drawer.snapTo(0f)
            // The swipe-down panel too: opening an app from it, or leaving the launcher, leaves it.
            pull.snapTo(0f)
            pulled = null
        }

    fun toggle() = if (drawer.targetValue > 0.5f) close() else open()

    /** Direction of the most recent drag step; a fallback when no fling velocity is reported. */
    private var lastDragUp = false

    fun dragBy(fraction: Float) = scope.launch {
        if (fraction != 0f) lastDragUp = fraction > 0
        drawer.snapTo((drawer.value + fraction).coerceIn(0f, 1f)) }

    /** The swipe-down panel: 0 = up out of sight, 1 = down. Follows the finger while dragging, like [drawer]. */
    val pull = Animatable(0f)

    /** File name of the panel [pull] shows, fixed when a swipe down starts it; null while none is pulled. */
    var pulled by mutableStateOf<String?>(null)

    /**
     * Whether the drag under way started with the panel open. A slow drag settles by how far it went from there, not
     * by its last step, which a finger lifting can turn the other way.
     */
    private var pullFromOpen = false

    /** A drag on the panel starts: from home, to open it, or on the panel, to close it. */
    fun startPull() {
        pullFromOpen = pull.targetValue > 0.5f
    }

    fun pullBy(fraction: Float) = scope.launch { pull.snapTo((pull.value + fraction).coerceIn(0f, 1f)) }

    /** Settles the panel after a drag; [velocity] is in px/s, positive = downwards. A short pull down opens it, a short push up closes it. */
    fun settlePull(velocity: Float) {
        val open =
            when {
                velocity > 800f -> true
                velocity < -800f -> false
                pullFromOpen -> pull.value > 0.8f
                else -> pull.value > 0.2f
            }
        if (open) composeScope?.launch { pull.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow)) } else closePull()
    }

    /** Brings the panel [name] all the way down, as the pill does for a swipe down that opens one. */
    fun openPull(name: String) {
        pulled = name
        startPull()
        composeScope?.launch { pull.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow)) }
    }

    /** Slides the panel away and lets it go; a drag that catches it on the way keeps it. */
    fun closePull() =
        scope.launch {
            if (composeScope != null) pull.animateTo(0f, spring(stiffness = Spring.StiffnessMedium)) else pull.snapTo(0f)
            pulled = null
        }

    /** Settles after a drag; [velocity] is in px/s, negative = upwards. */
    fun settle(velocity: Float) {
        when {
            velocity < -800f -> open(focusSearch)
            velocity > 800f -> close()
            // Compose sometimes reports 0 velocity for the first gesture after resuming.
            velocity == 0f && drawer.value > 0.05f -> if (lastDragUp) open(focusSearch) else close()
            drawer.value > 0.2f -> open(focusSearch)
            else -> close()
        }
    }
}

/** Opens the default agent in a terminal named [title] on [command] for its binary; with no agent installed, the agents screen. */
fun LauncherState.withAgent(
    runtime: Runtime,
    title: String,
    command: (bin: String) -> String,
) {
    val agent = runtime.agents.default ?: return show(SettingsPage.Agents)
    runtime.terminal(command(agent.bin), title, agent)
}
