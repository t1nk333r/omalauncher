package dev.omalauncher.ui

import android.os.Build
import android.os.Bundle
import android.util.SizeF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.omalauncher.widgets.AppWidgetItem
import dev.omalauncher.widgets.AppWidgets
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

/**
 * An Android widget on home, drawn by its app at the size of its cells. No frame: widgets bring their own
 * background, so only an accent outline shows while it's [focused] (being arranged). One whose app is gone
 * leaves a note in its place, to remove it by.
 *
 * Touches reach the widget unless home takes them first: a long-press lifts it, a sideways swipe turns the page,
 * and an up or down swipe does what it does on home ([swiper]: search, the drawer, the shade), as on stock launchers,
 * unless the widget can scroll that way where the finger is. The widget gets a cancel.
 */
@Composable
internal fun AppWidgetTile(
    item: AppWidgetItem,
    widgets: AppWidgets,
    focused: Boolean,
    modifier: Modifier,
    /** Home's swipe, or null while home's swipes are off (editing, typing in a tile). */
    swiper: HomeSwiper? = null,
) {
    val info = remember(item.id) { widgets.info(item.id) }
    val view = remember(item.id) { arrayOfNulls<View>(1) }
    val swipes by rememberUpdatedState(swiper)
    BoxWithConstraints(
        modifier.handsSwipesToHome(
            { swipes },
            scrolls = { x, y, direction -> view[0]?.scrollsAt(x, y, direction) == true },
            // As a scrolling view claims its own swipe: Compose then hands it the moves before home's drag can take them.
            keep = { view[0]?.parent?.requestDisallowInterceptTouchEvent(true) },
        ),
    ) {
        if (info == null) {
            Box(Modifier.fillMaxSize().tile(focused).padding(8.dp), contentAlignment = Alignment.Center) {
                BasicText("widget gone · its app was removed", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
        } else {
            // The size in dp the app lays the widget out for. Sent only when it changes: each one reaches the app as a broadcast.
            val size = SizeF(maxWidth.value, maxHeight.value)
            val sent = remember(item.id) { arrayOfNulls<SizeF>(1) }
            key(item.id) {
                AndroidView(
                    factory = { widgets.host.createView(it, item.id, info).also { v -> view[0] = v } },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        if (sent[0] != size) {
                            sent[0] = size
                            if (Build.VERSION.SDK_INT >= 31) {
                                view.updateAppWidgetSize(Bundle(), listOf(size))
                            } else {
                                val (w, h) = size.width.toInt() to size.height.toInt()
                                @Suppress("DEPRECATION")
                                view.updateAppWidgetSize(null, w, h, w, h)
                            }
                        }
                    },
                )
            }
            if (focused) Box(Modifier.fillMaxSize().frame(2.dp, Palette.accent))
        }
    }
}

/**
 * Takes an up or down swipe for home's [swiper] before the widget under it sees the moves, unless [scrolls] says the
 * widget can scroll that way at the press (x, y in px, direction as [View.canScrollVertically]); that swipe, or one
 * home does nothing with, is the widget's, and [keep] claims it for it. A sideways swipe, or a long-press home takes,
 * is left alone.
 */
private fun Modifier.handsSwipesToHome(
    swiper: () -> HomeSwiper?,
    scrolls: (Float, Float, Int) -> Boolean,
    keep: () -> Unit,
) = pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val home = swiper() ?: return@awaitEachGesture
        val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        var homing = false
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
            if (change == null) {
                if (homing) home.stop(0f)
                break
            }
            if (!homing) {
                // Taken above (home lifting the tile after a long-press), or let go before it became a swipe.
                if (change.isConsumed || !change.pressed) break
                tracker.addPosition(change.uptimeMillis, change.position)
                val d = change.position - down.position
                if (d.getDistance() <= slop) continue
                // Sideways is the pager's.
                if (abs(d.x) > abs(d.y)) break
                // One the widget scrolls, or home does nothing with, is the widget's.
                if (!home.wants(d.y) || scrolls(down.position.x, down.position.y, if (d.y > 0) -1 else 1)) {
                    keep()
                    break
                }
                homing = true
                change.consume()
                home.start()
                home.drag(d.y)
                continue
            }
            change.consume()
            tracker.addPosition(change.uptimeMillis, change.position)
            if (change.pressed) {
                home.drag(change.position.y - change.previousPosition.y)
            } else {
                home.stop(tracker.calculateVelocity().y)
                break
            }
        }
    }
}

/** Whether this view, or one under ([x], [y]) in its own px, can scroll in [direction], as [View.canScrollVertically]. */
private fun View.scrollsAt(
    x: Float,
    y: Float,
    direction: Int,
): Boolean {
    if (canScrollVertically(direction)) return true
    if (this !is ViewGroup) return false
    for (i in childCount - 1 downTo 0) {
        val child = getChildAt(i)
        if (child.visibility != View.VISIBLE) continue
        val cx = x + scrollX - child.left
        val cy = y + scrollY - child.top
        if (cx >= 0 && cy >= 0 && cx < child.width && cy < child.height && child.scrollsAt(cx, cy, direction)) return true
    }
    return false
}
