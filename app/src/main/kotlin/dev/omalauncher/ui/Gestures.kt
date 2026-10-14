package dev.omalauncher.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
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
    None("none", "nothing"),
    ;

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** The saved actions for vertical gestures on home. */
class Gestures(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    private var up by mutableStateOf(GestureAction.of(prefs.getString(SWIPE_UP, null)) ?: GestureAction.Drawer)

    /** The app drawer by default, like stock launchers. Saved as it's set. */
    var swipeUp: GestureAction
        get() = up
        set(action) {
            up = action
            prefs.edit().putString(SWIPE_UP, action.id).apply()
        }

    private var down by mutableStateOf(GestureAction.of(prefs.getString(SWIPE_DOWN, null)) ?: GestureAction.Search)

    var swipeDown: GestureAction
        get() = down
        set(action) {
            down = action
            prefs.edit().putString(SWIPE_DOWN, action.id).apply()
        }

    private companion object {
        const val SWIPE_DOWN = "gesture_swipe_down"
        const val SWIPE_UP = "gesture_swipe_up"
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

/**
 * Vertical swipes on home (the pages, the recent apps strip, the bar) run their configured action. With
 * the drawer open, the swipe drags it whatever the setting, so it can still be pulled closed.
 */
@Composable
internal fun Modifier.homeSwipe(
    runtime: Runtime,
    state: LauncherState,
    enabled: Boolean = true,
): Modifier {
    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    var draggingDrawer by remember(state) { mutableStateOf(false) }
    var action by remember(state) { mutableStateOf<GestureAction?>(null) }
    var distance by remember(state) { mutableStateOf(0f) }
    var direction by remember(state) { mutableStateOf(1f) }
    // Keep the same handler when the panel appears mid-drag.
    return draggable(
        orientation = Orientation.Vertical,
        enabled = enabled,
        onDragStarted = {
            draggingDrawer = state.drawer.value > 0f
            action = null
            distance = 0f
        },
        state = rememberDraggableState { dy ->
            if (draggingDrawer) {
                state.dragBy(-dy / screenHeight)
            } else {
                if (action == null && dy != 0f) {
                    direction = if (dy < 0f) -1f else 1f
                    action = if (dy < 0f) runtime.gestures.swipeUp else runtime.gestures.swipeDown
                    if (action == GestureAction.Drawer) state.focusSearch = false
                }
                distance += dy * direction
                if (action == GestureAction.Drawer) state.dragBy(dy * direction / screenHeight)
            }
        },
        onDragStopped = { velocity ->
            when {
                draggingDrawer -> state.settle(velocity)
                action == GestureAction.Drawer -> state.settle(-velocity * direction)
                action == GestureAction.Search && distance >= threshold -> state.open()
            }
        },
    )
}

/** Gesture settings for both vertical directions on home. */
@Composable
fun GesturesScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val gestures = runtime.gestures
    OverlayPanel("gestures", onDismiss, contentWidth = ReadingWidth) {
        GestureRow("swipe up", gestures.swipeUp) { gestures.swipeUp = it }
        GestureRow("swipe down", gestures.swipeDown) { gestures.swipeDown = it }
    }
}

/** A gesture and the action it runs; tapping the action lists the others under it. */
@Composable
private fun GestureRow(
    label: String,
    action: GestureAction,
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
                        for (option in GestureAction.entries) {
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
