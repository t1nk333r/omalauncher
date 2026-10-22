package dev.omalauncher.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.Runtime
import dev.omalauncher.theme.Style
import dev.omalauncher.wayland.Keys
import dev.omalauncher.widgets.render.WidgetKeybar
import dev.omalauncher.widgets.runtime.WidgetInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One row's height, keycap and the gap around it; the stock extra keys are two rows, like Termux's default. */
internal val KeyRowHeight = 40.dp
internal val KeyGap = 3.dp
internal val FrameWidth = 2.dp

/** The key bar's gap to the keyboard and the screen's sides: style.toml's keybar.gaps-out. */
private val KeybarGap: Dp get() = LauncherStyle.style[Style.KEYBAR_GAPS_OUT].toFloat().dp

/** The key bar's frame: style.toml's keybar.border-width. */
private val KeybarBorder: Dp get() = LauncherStyle.style[Style.KEYBAR_BORDER_WIDTH].toFloat().dp

/** How far up a key is swiped to send its popup instead. */
private val SwipeDistance = 16.dp

/**
 * Phone build: the rows style.toml's terminal.extra-keys asks for, which stand in for keybar.owl while it's set; null
 * while it isn't. See [ExtraKeysLayout].
 */
val extraKeysRows: List<List<ExtraKey>>? get() = LauncherStyle.style[Style.EXTRA_KEYS]?.let { ExtraKeysLayout.of(it) }

/**
 * The stock key bar's full height at the user's keybar.gaps-out and keybar.border-width, its gap to the keyboard
 * included: what home lifts a typing tile by until it has measured the bar. Phone build: or terminal.extra-keys' rows'.
 */
val ExtraKeysHeight: Dp get() = KeyRowHeight * (extraKeysRows?.size ?: 2) + (KeybarBorder + KeyGap) * 2 + KeybarGap

/**
 * Whether the extra-keys row belongs on screen: only over a docked soft
 * keyboard, and not when style.toml sets no rows. With a hardware keyboard,
 * Gboard shows just a floating toolbar that counts as visible but takes no
 * height, and the hardware keyboard has these keys already.
 */
@Composable
fun extraKeysWanted(): Boolean {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    // Derived, so the keyboard's slide-in animation doesn't recompose every frame.
    val docked by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    return docked && extraKeysRows?.isEmpty() != true
}

/**
 * The keys a phone keyboard lacks, docked above it for terminals: keybar.owl (`keybar "…" { }`), else the stock ones.
 * Their nodes never take focus, so the soft keyboard stays up; `pressKey(...)` and `latch(...)` reach [Runtime.keyboard].
 * Phone build: style.toml's terminal.extra-keys instead, while it's set ([LayoutKeys]).
 */
@Composable
fun ExtraKeys(
    runtime: Runtime,
    modifier: Modifier = Modifier,
) {
    val rows = extraKeysRows
    if (rows != null) return LayoutKeys(rows, runtime.keyboard, modifier)
    val widgets = runtime.widgets
    // Before the file runs too, so it's live as soon as it does.
    DisposableEffect(widgets) {
        widgets.keybarShown(true)
        onDispose { widgets.keybarShown(false) }
    }
    val inst = widgets.keybarInstance() ?: return
    WidgetKeybar(inst, modifier.fillMaxWidth())
}

/** The extra keys at the bottom of a screen, where the keyboard would push them up, for owl-check's picture of them. */
@Composable
internal fun KeybarPreview(inst: WidgetInstance) {
    Box(Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) {
        WidgetKeybar(inst, Modifier.fillMaxWidth())
    }
}

/**
 * Phone build: terminal.extra-keys' [rows], a framed panel of keycaps like the tiles above it. Ctrl, alt and shift
 * latch for the next key on a tap and lock on a long press; a key with a popup sends it when swiped up and let go.
 */
@Composable
private fun LayoutKeys(
    rows: List<List<ExtraKey>>,
    bridge: KeyboardBridge,
    modifier: Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = KeybarGap, end = KeybarGap, bottom = KeybarGap)
            .frame(KeybarBorder, Palette.border, Palette.background, radius = 12.dp)
            .padding(KeyGap),
    ) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth().height(KeyRowHeight)) {
                for (key in row) {
                    val cap = Modifier.weight(1f).fillMaxHeight().padding(KeyGap)
                    when (key) {
                        is Plain -> PlainKey(key, bridge, cap)
                        is Sticky -> StickyKey(key, bridge, cap)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlainKey(
    key: Plain,
    bridge: KeyboardBridge,
    modifier: Modifier,
) {
    val view = LocalView.current
    var pressed by remember(key) { mutableStateOf(false) }
    var swiped by remember(key) { mutableStateOf(false) }
    var confirmation by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(confirmation) {
        if (confirmation != null) {
            delay(240)
            confirmation = null
        }
    }
    val popup = key.popup
    KeyCap(
        modifier.pointerInput(key) {
            presses { scope, down ->
                pressed = true
                confirmation = null
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                // Dispatch the first key immediately: cancelling the repeat job on a fast tap
                // must not cancel the initial keypress before its coroutine starts.
                if (popup == null) bridge.send(key.strokes)
                val repeatJob =
                    if (popup == null && key.repeats) scope.launch {
                        delay(RepeatDelay)
                        while (true) {
                            bridge.send(key.strokes)
                            delay(RepeatInterval)
                        }
                    } else null
                val outcome = release(down, swipes = { popup != null }) { up ->
                    swiped = up
                    if (up) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                repeatJob?.cancel()
                pressed = false
                swiped = false
                if (popup != null) {
                    when (outcome) {
                        Release.Tap -> bridge.send(key.strokes)
                        Release.Swipe -> {
                            bridge.send(popup.strokes)
                            confirmation = popup.label
                        }
                        Release.Cancelled -> {}
                    }
                }
            }
        },
        fill = if (pressed || confirmation != null) Palette.accent else Palette.surface,
    ) {
        val popupVisible = (swiped || confirmation != null) && popup != null
        val color = if (pressed || confirmation != null) Palette.background else Palette.foreground
        // Actual first chord: copy is Ctrl+Insert even without a latched Ctrl.
        val firstStroke = (if (popupVisible) popup!!.strokes else key.strokes).firstOrNull()
        val glyphs = modifierGlyphs(bridge, firstStroke?.held.orEmpty())
        val label = if (popupVisible) popup!!.label else arrowGlyph(key.arrow) ?: key.label
        val prefix = if (glyphs.isNotEmpty() && !label.startsWith(glyphs)) glyphs else ""
        when {
            !popupVisible && glyphs.isEmpty() && key.arrow != null ->
                Image(Icons.Chevron, key.label, Modifier.size(20.dp).rotate(key.arrow), colorFilter = ColorFilter.tint(color))
            else -> KeyLabel(prefix + label, color)
        }
        if (popup != null && !popupVisible) PopupHint(popup, if (pressed) Palette.background else Palette.accent)
    }
}

/**
 * A keycap that acts on touch down, and keeps acting while held when it [repeats]: the editor's keys.
 * Shows [icon] turned by [rotation], else [label]. The terminal's keys can also swipe up to a second
 * key, so [PlainKey] is their own.
 */
@Composable
internal fun RepeatingKeyCap(
    label: String,
    icon: ImageVector?,
    rotation: Float,
    repeats: Boolean,
    modifier: Modifier,
    onPress: () -> Unit,
) {
    val view = LocalView.current
    val press by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    KeyCap(
        modifier.pointerInput(label, repeats) {
            presses { scope, _ ->
                pressed = true
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                // The first stroke now, not in the launch: a tap lifted before the coroutine starts would cancel it.
                press()
                val job =
                    scope.launch {
                        if (!repeats) return@launch
                        delay(RepeatDelay)
                        while (true) {
                            press()
                            delay(RepeatInterval)
                        }
                    }
                waitForUpOrCancellation()
                job.cancel()
                pressed = false
            }
        },
        fill = if (pressed) Palette.accent else Palette.surface,
    ) {
        val color = if (pressed) Palette.background else Palette.foreground
        if (icon != null) {
            Image(icon, label, Modifier.size(20.dp).rotate(rotation), colorFilter = ColorFilter.tint(color))
        } else {
            BasicText(label, style = Mono.copy(color = color), maxLines = 1)
        }
    }
}

@Composable
private fun StickyKey(
    key: Sticky,
    bridge: KeyboardBridge,
    modifier: Modifier,
) {
    val view = LocalView.current
    val sticky = bridge.modifier(key.mod)
    val latch = sticky.latch
    var swiped by remember(key) { mutableStateOf(false) }
    var confirmation by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(confirmation) {
        if (confirmation != null) {
            delay(240)
            confirmation = null
        }
    }
    val popup = key.popup
    KeyCap(
        modifier.pointerInput(key, sticky) {
            presses { scope, down ->
                confirmation = null
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                var locked = false
                val hold = scope.launch {
                    delay(RepeatDelay)
                    sticky.latch = Latch.Locked
                    locked = true
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                // A popup swipe cancels the locking timer and sends only on release.
                val outcome = release(down, swipes = { popup != null && !locked }) { up ->
                    swiped = up
                    if (up) {
                        hold.cancel()
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                }
                hold.cancel()
                swiped = false
                when {
                    locked || outcome == Release.Cancelled -> {}
                    outcome == Release.Swipe && popup != null -> {
                        bridge.send(popup.strokes)
                        confirmation = popup.label
                    }
                    else -> sticky.latch = if (sticky.latch == Latch.Off) Latch.Once else Latch.Off
                }
            }
        },
        fill = if (latch == Latch.Locked || swiped || confirmation != null) Palette.accent else Palette.surface,
        outline = if (latch == Latch.Once && !swiped && confirmation == null) Palette.accent else null,
    ) {
        val showingPopup = (swiped || confirmation != null) && popup != null
        val color = when {
            showingPopup || latch == Latch.Locked -> Palette.background
            latch == Latch.Once -> Palette.accent
            else -> Palette.foreground
        }
        KeyLabel(if (showingPopup) popup!!.label else key.label, color)
        if (latch != Latch.Off && !showingPopup) {
            BasicText(
                if (latch == Latch.Locked) "LOCK" else "NEXT",
                Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
                style = Mono.copy(color = color, fontSize = 7.sp),
                maxLines = 1,
                softWrap = false,
            )
        }
        if (popup != null && !showingPopup) PopupHint(popup, if (latch == Latch.Locked) Palette.background else Palette.accent)
    }
}

/** The modifiers latched for the next key, so a tap on ctrl shows on the key it will go with. */
private fun modifierGlyphs(bridge: KeyboardBridge, held: List<Int>): String =
    buildString {
        if (bridge.ctrl.latch != Latch.Off || Keys.KEY_LEFTCTRL in held) append("⌃")
        if (bridge.alt.latch != Latch.Off || Keys.KEY_LEFTALT in held) append("⌥")
        if (bridge.shift.latch != Latch.Off || Keys.KEY_LEFTSHIFT in held) append("⇧")
    }

/** Chevron-only arrows become readable glyphs when they need a modifier prefix. */
private fun arrowGlyph(rotation: Float?): String? = when (rotation) {
    0f -> "↑"
    90f -> "→"
    180f -> "↓"
    -90f -> "←"
    else -> null
}

/** A key's label; a long one, like a popup's while swiping, in smaller type so it fits the cap. */
@Composable
private fun KeyLabel(
    text: String,
    color: Color,
) {
    val style = Mono.copy(color = color)
    BasicText(
        text,
        style = if (text.length > 4) style.copy(fontSize = 10.sp) else style,
        maxLines = 1,
        softWrap = false,
    )
}

/** The popup's label in the key's corner, so it's found without trying every key. */
@Composable
private fun BoxScope.PopupHint(
    popup: Popup,
    color: Color,
) {
    BasicText(
        popup.label,
        Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 3.dp),
        style = Mono.copy(color = color, fontSize = 7.sp),
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
internal fun KeyCap(
    modifier: Modifier,
    fill: Color,
    outline: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = LauncherStyle.shape(8.dp)
    Box(
        modifier
            .clip(shape)
            .background(fill, shape)
            .then(if (outline != null) Modifier.border(FrameWidth, outline, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) { content() }
}

internal enum class Release { Tap, Swipe, Cancelled }

/**
 * Follows the touch that went [down] until it lifts. While [swipes], moving it
 * [SwipeDistance] up selects the popup ([onSwipe] true), back down deselects
 * it, and the touch is the key's, not home's to page or scroll with. A touch
 * that can't swipe and slides off the key is cancelled, as anywhere else.
 */
private suspend fun AwaitPointerEventScope.release(
    down: PointerInputChange,
    swipes: () -> Boolean,
    onSwipe: (Boolean) -> Unit,
): Release {
    val distance = SwipeDistance.toPx()
    var selected = false
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return Release.Cancelled
        if (change.isConsumed) return Release.Cancelled
        val decision = extraKeyGestureStep(
            x = change.position.x,
            y = change.position.y,
            downY = down.position.y,
            width = size.width.toFloat(),
            height = size.height.toFloat(),
            paddingX = extendedTouchPadding.width,
            paddingY = extendedTouchPadding.height,
            swipeDistance = distance,
            canSwipe = swipes(),
            pressed = change.pressed,
        )
        // Check cancellation before selecting or dispatching a popup. Even an already
        // selected upward swipe must cancel if the finger moves extremely far sideways.
        if (decision.release == Release.Cancelled) return Release.Cancelled
        if (decision.selected != selected) {
            selected = decision.selected
            onSwipe(selected)
        }
        if (!change.pressed) {
            change.consume()
            return decision.release ?: Release.Cancelled
        }
        if (selected) change.consume()
    }
}

/** Pure decision used by the pointer handler and regression tests, including release-frame movement. */
internal data class ExtraKeyGestureStep(
    val selected: Boolean,
    val release: Release?,
)

internal fun extraKeyGestureStep(
    x: Float,
    y: Float,
    downY: Float,
    width: Float,
    height: Float,
    paddingX: Float,
    paddingY: Float,
    swipeDistance: Float,
    canSwipe: Boolean,
    pressed: Boolean,
): ExtraKeyGestureStep {
    val selected = canSwipe && downY - y >= swipeDistance
    val release = when {
        extraKeyDragCancelled(x, y, width, height, paddingX, paddingY, canSwipe) -> Release.Cancelled
        pressed -> null
        selected -> Release.Swipe
        else -> Release.Tap
    }
    return ExtraKeyGestureStep(selected, release)
}

/** Popup swipes may travel above the cap; sideways and downward drags cancel instead of tapping. */
internal fun extraKeyDragCancelled(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    paddingX: Float,
    paddingY: Float,
    canSwipe: Boolean,
): Boolean =
    if (canSwipe && y < 0) {
        x < -width || x > width * 2 || y > height + paddingY
    } else {
        x < -paddingX || x > width + paddingX || y > height + paddingY ||
            (!canSwipe && y < -paddingY)
    }

/** Runs [press] for each touch on the key, with a scope to start key repeat or long-press timers in. */
internal suspend fun PointerInputScope.presses(press: suspend AwaitPointerEventScope.(CoroutineScope, PointerInputChange) -> Unit) =
    coroutineScope {
        val scope = this
        awaitEachGesture {
            val down = awaitFirstDown()
            press(scope, down)
        }
    }

/** How long a key is held before it repeats, and how often it repeats then: the editor's keys and owl's `repeats`. */
internal const val RepeatDelay = 400L
internal const val RepeatInterval = 50L
