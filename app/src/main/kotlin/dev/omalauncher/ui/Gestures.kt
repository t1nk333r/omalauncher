package dev.omalauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import dev.omalauncher.LauncherData
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

/** Whether swiping [up] or [down] opens the app panel: the drawer, or search. */
internal fun reachesPanel(
    up: GestureAction,
    down: GestureAction,
) = listOf(up, down).any { it == GestureAction.Drawer || it == GestureAction.Search }

/** The action saved as [id] if [choices] offers it, else the first, the gesture's default. */
internal fun pick(
    id: String?,
    choices: List<GestureAction>,
) = GestureAction.of(id)?.takeIf { it in choices } ?: choices.first()

/** The actions picked for home's gestures. */
class Gestures(
    context: Context,
) {
    private val prefs = LauncherData.prefs(context)

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

    /** Whether a swipe opens the app panel (the drawer or search); the pill does what swiping down does. */
    val reachPanel get() = reachesPanel(swipeUp, swipeDown)

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
    return when {
        drawer > 0f -> HomeSwipe(GestureAction.Drawer, -1f)
        picked != GestureAction.None -> HomeSwipe(picked, way)
        // With nothing set for swiping down, the drawer still follows a swipe that starts down and turns up.
        dy > 0f && up == GestureAction.Drawer -> HomeSwipe(GestureAction.Drawer, -1f)
        else -> HomeSwipe(GestureAction.None, way)
    }
}

/**
 * One swipe on home, from its first step to letting go. Its first step picks what it does ([homeSwipeFor]): going up
 * the drawer follows the finger, while search and the rest open when it's let go. [homeSwipe] drives it from a drag
 * on home; tiles that hand a vertical swipe over to home drive it too: a terminal not in use, an Android widget that
 * can't scroll that way, a widget's list already at its end.
 */
@Stable
internal class HomeSwiper(
    private val runtime: Runtime,
    private val state: LauncherState,
    private val context: Context,
    private val screenHeight: Float,
    private val openDistance: Float,
) {
    private var swipe: HomeSwipe? = null

    // How far the swipe has gone its way, for what opens when it's let go.
    private var distance = 0f

    /** Whether a swipe starting [dy] (down positive) would do anything; when not, it's left to what's under it. */
    fun wants(dy: Float) = homeSwipeFor(state.drawer.value, dy, runtime.gestures.swipeUp, runtime.gestures.swipeDown).action != GestureAction.None

    fun start() {
        swipe = null
        distance = 0f
    }

    /** A step of [dy] px, down positive. */
    fun drag(dy: Float) {
        if (swipe == null && dy != 0f) {
            val picked = homeSwipeFor(state.drawer.value, dy, runtime.gestures.swipeUp, runtime.gestures.swipeDown)
            swipe = picked
            if (picked.action == GestureAction.Notifications) expandNotifications(context)
            if (picked.browses(state.drawer.value)) state.focusSearch = false
        }
        swipe?.let {
            distance += it.along(dy)
            if (it.dragsDrawer) state.dragBy(-dy / screenHeight)
        }
    }

    /** Let go at [velocity] px/s, down positive. */
    fun stop(velocity: Float) {
        swipe?.let {
            when {
                it.dragsDrawer -> state.settle(velocity)
                it.opensOnRelease(distance, velocity, openDistance) -> state.open(search = it.action == GestureAction.Search)
            }
        }
        swipe = null
    }
}

@Composable
internal fun rememberHomeSwiper(
    runtime: Runtime,
    state: LauncherState,
): HomeSwiper {
    val context = LocalContext.current
    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val openDistance = with(LocalDensity.current) { 56.dp.toPx() }
    return remember(runtime, state, context, screenHeight, openDistance) { HomeSwiper(runtime, state, context, screenHeight, openDistance) }
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
    val gestures = runtime.gestures
    val drawerShown by remember(state) { derivedStateOf { state.drawer.value > 0f } }
    // Whether the drawer shows is only read when it matters: read here, it would recompose all of home (its Box is
    // inline) each time the drawer opens or closes.
    val drags = gestures.swipeUp != GestureAction.None || gestures.swipeDown != GestureAction.None || drawerShown
    val swiper = rememberHomeSwiper(runtime, state)
    // One drag handler, only turned on or off: a new one mid-swipe (as the drawer starts to show) would drop the drag.
    return draggable(
        orientation = Orientation.Vertical,
        enabled = enabled && drags,
        state = rememberDraggableState { dy -> swiper.drag(dy) },
        onDragStarted = { swiper.start() },
        onDragStopped = { velocity -> swiper.stop(velocity) },
    )
}

/**
 * A widget's list scrolled to its end hands the rest of the swipe to home: pulled down at its top it opens search (or
 * what swiping down does), pushed up at its bottom the drawer. Its fling goes to home too, so the list doesn't fling.
 */
@Composable
internal fun Modifier.homeSwipeAfterScroll(
    swiper: HomeSwiper,
    enabled: Boolean,
): Modifier {
    val on by rememberUpdatedState(enabled)
    val connection =
        remember(swiper) {
            object : NestedScrollConnection {
                var active = false

                // Once home has the swipe, all of it is home's: moving back shrinks the swipe, not the list's scroll.
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (!active || source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                    swiper.drag(available.y)
                    return Offset(0f, available.y)
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                    if (active) return Offset.Zero
                    if (!on || !swiper.wants(available.y)) return Offset.Zero
                    active = true
                    swiper.start()
                    swiper.drag(available.y)
                    return Offset(0f, available.y)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (!active) return Velocity.Zero
                    active = false
                    swiper.stop(available.y)
                    return available
                }
            }
        }
    return nestedScroll(connection)
}

/** Height of the pill above the dock. */
val PillHeight = 40.dp

/** What the pill above the dock says for [action], the one swiping down does; null (no pill) while that's nothing. */
internal fun pillLabel(action: GestureAction) =
    when (action) {
        GestureAction.Search -> "search"
        GestureAction.Notifications -> "notifications"
        GestureAction.Drawer -> "apps"
        GestureAction.None -> null
    }

/**
 * A pill above the dock, like iOS's search pill, that does what swiping down on home does and is named after it, so
 * the gesture has a button too. Swiping on it does what swiping does elsewhere on home.
 */
@Composable
internal fun HomePill(
    runtime: Runtime,
    state: LauncherState,
    modifier: Modifier = Modifier,
) {
    val action = runtime.gestures.swipeDown
    val label = pillLabel(action) ?: return
    val context = LocalContext.current
    // Only the pill takes touches: beside it, long-presses and swipes are home's as anywhere else.
    Box(Modifier.fillMaxWidth().height(PillHeight), contentAlignment = Alignment.Center) {
        Row(
            modifier
                .fillMaxHeight()
                // Home's glass, as round as the theme's corners allow: a pill with round corners, a box with Omarchy's.
                .frame(LauncherStyle.borderWidth, LauncherStyle.tileBorder, Palette.glass, radius = PillHeight / 2, frosted = true)
                .clickable {
                    when (action) {
                        GestureAction.Search -> state.open(search = true)
                        GestureAction.Drawer -> state.open(search = false)
                        GestureAction.Notifications -> expandNotifications(context)
                        GestureAction.None -> Unit
                    }
                }.semantics { contentDescription = label }
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (action == GestureAction.Search) {
                Image(Icons.Search, null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(Palette.muted))
                Spacer(Modifier.width(8.dp))
            }
            BasicText(label, style = Mono.copy(color = Palette.foreground, fontSize = TextSize.Item))
        }
    }
}

/**
 * The unseen strip under home's grid, above the pill and the dock (home.settings-strip): long-pressed, it opens
 * settings, which a grid full to its last row leaves no empty spot for. Swiping on it does what swiping does on home.
 * Gone while a tile is being arranged, so a tap there finishes, as it does anywhere off the tile.
 */
@Composable
internal fun SettingsStrip(
    runtime: Runtime,
    state: LauncherState,
) {
    val height = LauncherStyle.settingsStrip
    if (height <= 0.dp || state.editing != null) return
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .homeSwipe(runtime, state)
            .pointerInput(state) {
                detectTapGestures(onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    state.show()
                })
            }.semantics {
                contentDescription = "settings"
                onLongClick("open settings") {
                    state.show()
                    true
                }
            },
    )
}

/**
 * Pulls down Android's notification shade. There's no public call for it: launchers that aren't part of the system,
 * like Lawnchair, call StatusBarManager's hidden expandNotificationsPanel, which needs EXPAND_STATUS_BAR.
 */
@SuppressLint("WrongConstant")
internal fun expandNotifications(context: Context) {
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
        Modifier.card(onClick = { open = !open }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, Modifier.weight(1f), style = Mono.copy(fontSize = TextSize.Item))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(action.label, style = Mono.copy(color = Palette.accent, fontSize = TextSize.Item))
                Symbol(if (open) Glyph.ChevronUp else Glyph.ChevronDown, Palette.muted, Modifier.padding(start = 4.dp))
            }
            if (open) {
                ChoicePopup(choices, action, BelowAnchorEnd, onDismiss = { open = false }, onPick = onPick, label = { it.label })
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
