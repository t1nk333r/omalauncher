package dev.omalauncher.widgets.render

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.TextSize
import dev.omalauncher.widgets.lang.Builtins
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Value
import kotlin.math.ceil

/*
 * Phone build: a lazy Column's or Grid's `rail`, the letters beside the app drawer's list. Touch or slide along it to
 * jump to the item each one names.
 */

/** How wide the letter rail is beside its list. */
internal val RailWidth = 22.dp

/** One letter of a `rail`: its [label], and the `for` key of the item it jumps to. */
internal class RailStop(
    val label: String,
    val key: String,
)

/** A `rail` value, a list of `{label, key}` maps, as stops; entries without both are left out. */
internal fun railStops(v: Value?): List<RailStop> =
    if (v == null) {
        emptyList()
    } else {
        Builtins.iterable(v).mapNotNull { e ->
            val f = (e as? Obj)?.fields ?: return@mapNotNull null
            val label = f["label"]?.toString()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val key = f["key"]?.toString() ?: return@mapNotNull null
            RailStop(label, key)
        }
    }

/** The letters beside a list: touch or slide along it to [onPick] the one under the finger. */
@Composable
internal fun LetterRail(
    letters: List<String>,
    modifier: Modifier,
    onPick: (Int) -> Unit,
) {
    var touched by remember { mutableStateOf<Int?>(null) }
    val haptics = LocalHapticFeedback.current
    val pick by rememberUpdatedState(onPick)
    var height by remember { mutableIntStateOf(0) }
    // A line of the rail's text, as Android's font size makes it: closer than that, letters are skipped.
    val legible = with(LocalDensity.current) { TextSize.Small.toPx() * 1.4f }
    Column(
        modifier.onSizeChanged { height = it.height }.pointerInput(letters) {
            fun at(y: Float) {
                val i = (y / size.height * letters.size).toInt().coerceIn(0, letters.lastIndex)
                if (i == touched) return
                touched = i
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                pick(i)
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
                if (i % step == 0 || i == touched) {
                    // Taller than its slot when crowded: it spills over the blank slots beside it rather than being cut.
                    BasicText(
                        c,
                        Modifier.wrapContentHeight(unbounded = true),
                        style = Mono.copy(color = if (i == touched) Palette.accent else Palette.muted, fontSize = TextSize.Small),
                    )
                }
            }
        }
    }
}
