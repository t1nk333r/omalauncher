package dev.omalauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.omalauncher.Runtime

/** What a gesture on home does. */
enum class GestureAction(
    /** Saved in preferences: never rename. */
    val id: String,
    val label: String,
) {
    Drawer("drawer", "app drawer"),
    Search("search", "search / agent prompt"),
    Notifications("notifications", "notifications"),
    None("none", "nothing"),
    ;

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** What swiping up on home can do, the default first. */
internal val SwipeUpChoices = listOf(GestureAction.Drawer, GestureAction.Search, GestureAction.None)

/** What swiping down on home can do, the default first. */
internal val SwipeDownChoices = listOf(GestureAction.Search, GestureAction.Notifications, GestureAction.Drawer, GestureAction.None)

/** The action saved as [id] if [choices] offers it, else the first, the gesture's default. */
internal fun pick(
    id: String?,
    choices: List<GestureAction>,
) = GestureAction.of(id)?.takeIf { it in choices } ?: choices.first()

/** The actions picked for home's gestures. */
class Gestures(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    private var up by mutableStateOf(saved(SWIPE_UP, SwipeUpChoices))
    private var down by mutableStateOf(saved(SWIPE_DOWN, SwipeDownChoices))

    /** The app drawer by default, like stock launchers, with the keyboard down. Saved as it's set. */
    var swipeUp: GestureAction
        get() = up
        set(action) {
            up = action
            prefs.edit().putString(SWIPE_UP, action.id).apply()
        }

    /** Search and the agent prompt by default, with the keyboard up. Saved as it's set. */
    var swipeDown: GestureAction
        get() = down
        set(action) {
            down = action
            prefs.edit().putString(SWIPE_DOWN, action.id).apply()
        }

    private fun saved(
        key: String,
        choices: List<GestureAction>,
    ) = pick(prefs.getString(key, null), choices)

    private companion object {
        const val SWIPE_UP = "gesture_swipe_up"
        const val SWIPE_DOWN = "gesture_swipe_down"
    }
}

/**
 * Dragging up opens the drawer under the finger and dragging down closes it, as on stock launchers: the panel
 * follows the drag and a fling or how far it got decides where it settles. Scrollables inside (a widget's
 * list, the recent apps strip) take their own direction first.
 */
@Composable
internal fun Modifier.drawerSwipe(
    state: LauncherState,
    enabled: Boolean = true,
): Modifier {
    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    return draggable(
        orientation = Orientation.Vertical,
        enabled = enabled,
        state = rememberDraggableState { dy -> state.dragBy(-dy / screenHeight) },
        onDragStopped = { velocity -> state.settle(velocity) },
    )
}

/** What a swipe on home does, and the [way] it counts as going: -1 up, 1 down. */
internal data class HomeSwipe(
    val action: GestureAction,
    val way: Float,
) {
    /**
     * Whether the drawer follows the finger: only up, the way it opens. Swiped down to, it opens when let go, as search
     * does, rather than rising against the finger.
     */
    val dragsDrawer get() = action == GestureAction.Drawer && way < 0f

    /** How far a step, or a velocity, of [dy] (down positive) goes this swipe's way. */
    fun along(dy: Float) = dy * way

    /**
     * Whether letting go opens the drawer or search: once the swipe has gone [threshold] its way, or is let go of
     * moving that way as fast as a fling settles the drawer. [distance] is measured its way, [velocity] in px/s down.
     */
    fun opensOnRelease(
        distance: Float,
        velocity: Float,
        threshold: Float,
    ) = !dragsDrawer &&
        (action == GestureAction.Search || action == GestureAction.Drawer) &&
        (distance >= threshold || (distance > 0f && along(velocity) > 800f))

    /** Whether it opens the drawer to browse, so the keyboard stays down: the drawer was closed when it started. */
    fun browses(drawer: Float) = action == GestureAction.Drawer && drawer == 0f
}

/**
 * What a swipe on home does, from how far the drawer is open and the swipe's first step ([dy] > 0 is down). With
 * the drawer showing, the swipe drags it whatever the settings, so it can still be pulled closed.
 */
internal fun homeSwipeFor(
    drawer: Float,
    dy: Float,
    up: GestureAction,
    down: GestureAction,
): HomeSwipe {
    val way = if (dy > 0f) 1f else -1f
    val picked = if (dy > 0f) down else up
    val other = if (dy > 0f) up else down
    return when {
        drawer > 0f -> HomeSwipe(GestureAction.Drawer, -1f)
        picked != GestureAction.None -> HomeSwipe(picked, way)
        // With nothing set this way, the drawer still follows a swipe that starts this way and turns back.
        other == GestureAction.Drawer -> HomeSwipe(GestureAction.Drawer, -way)
        else -> HomeSwipe(GestureAction.None, way)
    }
}

/**
 * Swiping on home (the pages, the recent apps strip, the bar) does what's picked in gesture settings: by default up
 * opens the drawer under the finger with the keyboard down, and down opens search with the keyboard up.
 */
@Composable
internal fun Modifier.homeSwipe(
    runtime: Runtime,
    state: LauncherState,
    enabled: Boolean = true,
): Modifier {
    val context = LocalContext.current
    val gestures = runtime.gestures
    val drawerShown by remember(state) { derivedStateOf { state.drawer.value > 0f } }
    // Whether the drawer shows is only read when it matters: read here, it would recompose all of home (its Box is
    // inline) each time the drawer opens or closes.
    val drags = gestures.swipeUp != GestureAction.None || gestures.swipeDown != GestureAction.None || drawerShown
    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val openDistance = with(LocalDensity.current) { 56.dp.toPx() }
    // Picked by the swipe's first step, so one that starts down and turns up doesn't open the drawer under the shade.
    var swipe by remember { mutableStateOf<HomeSwipe?>(null) }
    // How far the swipe has gone its way, for what opens when it's let go.
    var distance by remember { mutableFloatStateOf(0f) }
    // One drag handler, only turned on or off: a new one mid-swipe (as the drawer starts to show) would drop the drag.
    return draggable(
        orientation = Orientation.Vertical,
        enabled = enabled && drags,
        state =
            rememberDraggableState { dy ->
                if (swipe == null && dy != 0f) {
                    val picked = homeSwipeFor(state.drawer.value, dy, gestures.swipeUp, gestures.swipeDown)
                    swipe = picked
                    if (picked.action == GestureAction.Notifications) expandNotifications(context)
                    if (picked.browses(state.drawer.value)) state.focusSearch = false
                }
                swipe?.let {
                    distance += it.along(dy)
                    if (it.dragsDrawer) state.dragBy(-dy / screenHeight)
                }
            },
        onDragStarted = {
            swipe = null
            distance = 0f
        },
        onDragStopped = { velocity ->
            swipe?.let {
                when {
                    it.dragsDrawer -> state.settle(velocity)
                    it.opensOnRelease(distance, velocity, openDistance) -> state.open(search = it.action == GestureAction.Search)
                }
            }
        },
    )
}

/**
 * Pulls down Android's notification shade. There's no public call for it: launchers that aren't part of the system,
 * like Lawnchair, call StatusBarManager's hidden expandNotificationsPanel, which needs EXPAND_STATUS_BAR.
 */
@SuppressLint("WrongConstant")
private fun expandNotifications(context: Context) {
    runCatching {
        Class
            .forName("android.app.StatusBarManager")
            .getMethod("expandNotificationsPanel")
            .invoke(context.getSystemService("statusbar"))
    }.onFailure { Log.w(TAG, "cannot pull down the notification shade", it) }
}

private const val TAG = "gestures"

/** Gesture settings: what swiping up and down on home do, each picked from a dropdown. */
@Composable
fun GesturesScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val gestures = runtime.gestures
    OverlayPanel("gestures", onDismiss, contentWidth = ReadingWidth) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GestureRow("swipe up", gestures.swipeUp, SwipeUpChoices) { gestures.swipeUp = it }
            GestureRow("swipe down", gestures.swipeDown, SwipeDownChoices) { gestures.swipeDown = it }
        }
    }
}

/** A gesture and the action it runs; tapping the action lists the gesture's [choices] under it. */
@Composable
private fun GestureRow(
    label: String,
    action: GestureAction,
    choices: List<GestureAction>,
    onPick: (GestureAction) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(LauncherStyle.shape(12.dp))
            .background(Palette.surface)
            .clickable { open = !open }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, Modifier.weight(1f), style = Mono.copy(fontSize = TextSize.Item))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(action.label, style = Mono.copy(color = Palette.accent, fontSize = TextSize.Item))
                Symbol(if (open) Glyph.ChevronUp else Glyph.ChevronDown, Palette.muted, Modifier.padding(start = 4.dp))
            }
            if (open) {
                Popup(
                    popupPositionProvider = BelowAnchorEnd,
                    onDismissRequest = { open = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Column(
                        Modifier
                            .width(IntrinsicSize.Max)
                            .widthIn(min = 180.dp)
                            .frame(2.dp, Palette.accent, Palette.background)
                            .padding(4.dp),
                    ) {
                        for (option in choices) {
                            val current = option == action
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onPick(option)
                                        open = false
                                    }.padding(horizontal = 10.dp, vertical = 10.dp),
                            ) {
                                BasicText(if (current) "● " else "  ", style = Mono.copy(color = Palette.accent))
                                BasicText(option.label, style = Mono.copy(color = if (current) Palette.accent else Palette.foreground))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Just under the anchor, lined up with its end, and kept on screen. */
private object BelowAnchorEnd : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ) = IntOffset(
        (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        (anchorBounds.bottom + 8).coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)),
    )
}
