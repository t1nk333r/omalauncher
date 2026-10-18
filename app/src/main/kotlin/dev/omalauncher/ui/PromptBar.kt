package dev.omalauncher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.linux.agentInvocation
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.gestures.detectTapGestures
import dev.omalauncher.openUrl
import dev.omalauncher.theme.Style
import dev.omalauncher.webSearchName
import dev.omalauncher.webSearchUrl

/**
 * How the prompt bar was summoned from outside it; Send asks the agent with what's typed (the panel's ask row),
 * Web searches the web with it (the row above), Listen dictates into it (the landscape rail's mic).
 */
enum class PromptRequest { Type, Send, Web, Listen }

/** Height of the collapsed bar; the home grid leaves this much room for it. */
val PromptBarHeight = 48.dp

/**
 * The one input at the bottom of the home screen: it finds apps, searches the web and asks agents.
 * Focusing it opens the app panel above it; typing narrows that panel, and Enter
 * opens the highlighted app, sends the text to the default agent in a terminal, or
 * searches the web with it (the send button says which; the web with "web" picked
 * in the menu at its start, or without an agent). The mic sends there too: hold to
 * talk and release to send, tap to dictate into the field (it stops at the next pause).
 */
@Composable
internal fun PromptBar(
    runtime: Runtime,
    state: LauncherState,
    search: BarSearch,
    /** What Enter acts on: an index into the search hits, [BarSearch.ASK] for the agent or [BarSearch.WEB]. */
    highlighted: Int,
    onLaunch: (AppHit) -> Unit,
    /** Whether the bar shows; off home it collapses with the panel, see [barOnHome]. */
    shown: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val speech = remember { Speech(context.applicationContext) }
    // Collapsed mid-dictation (the panel swiped shut): stop listening, or the words would land in a bar nobody sees.
    LaunchedEffect(shown) { if (!shown && speech.listening) speech.cancel() }
    DisposableEffect(speech) { onDispose { speech.cancel() } }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = LocalHapticFeedback.current
    val text = state.query
    var focused by remember { mutableStateOf(false) }
    val panelShown by remember(state) { derivedStateOf { state.drawer.value > 0.01f } }
    val agent = runtime.agents.default
    val linuxState by runtime.bootstrap.state.collectAsState()
    // Agents run in Linux: without it, the bar offers to install it instead.
    val linux = linuxState == LinuxState.Installed
    // Where Enter, the bar's button and the held mic send what's typed: the agent, or the web.
    val asks = barAsksAgent(runtime, linuxState)

    fun offerLinux() {
        focusManager.clearFocus()
        keyboard?.hide()
        state.closeNow()
        state.show(SettingsPage.Linux)
    }

    fun send(prompt: String) {
        val p = prompt.trim()
        if (p.isEmpty()) return
        if (!linux) return offerLinux()
        // Looked up now: speech results arrive after the bar may have recomposed.
        val target = runtime.agents.default
        if (target == null) {
            state.query = p
            state.show(SettingsPage.Agents)
            return
        }
        runtime.terminal("${agentInvocation(target.bin, p)}; exec bash", target.name, target)
        state.query = ""
        focusManager.clearFocus()
        keyboard?.hide()
        state.closeNow()
    }

    /** Opens style's web search for [query] in the browser, and leaves home as opening an app does. */
    fun searchWeb(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val url = webSearchUrl(LauncherStyle.style[Style.WEB_SEARCH], q)
        if (!context.openUrl(url)) {
            Toast.makeText(context, "no browser opens ${webSearchName(LauncherStyle.style[Style.WEB_SEARCH])}", Toast.LENGTH_SHORT).show()
            return
        }
        state.query = ""
        focusManager.clearFocus()
        keyboard?.hide()
        state.closeNow()
    }

    /** Enter: the highlighted app, the web or the agent; nothing while apps are being picked in the drawer, so the picks stay. */
    fun submit() {
        if (state.selection != null) return
        val hit = search.hits.getOrNull(highlighted)
        when {
            hit != null -> onLaunch(hit)
            highlighted == BarSearch.WEB -> searchWeb(text)
            else -> send(text)
        }
    }

    /** What's said into the held mic: to the agent, or the web; looked up now, as [send] does. */
    fun sendSpoken(prompt: String) = if (barAsksAgent(runtime, runtime.bootstrap.state.value)) send(prompt) else searchWeb(prompt)

    /** Moves the highlight up the panel (away from the bar) or back down; the ask row sits lowest, the web's above it. */
    fun pick(step: Int) {
        if (text.isBlank()) return
        state.picked = search.moved(highlighted, step)
    }

    /** Whether a finished recognition sends (hold-to-talk) or lands in the field for editing. */
    var sendWhenDone by remember { mutableStateOf(false) }

    fun listen(hold: Boolean) {
        focusManager.clearFocus()
        keyboard?.hide()
        sendWhenDone = hold
        val typed = text
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        speech.start(hold) { heard ->
            val full = listOf(typed, heard).filter { it.isNotBlank() }.joinToString(" ") { it.trim() }
            if (sendWhenDone && heard.isNotEmpty()) sendSpoken(full) else state.query = full
        }
    }

    // A press that had to ask for the mic first can't still be held, so it dictates instead.
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) listen(hold = false)
        }

    // No Linux needed: without an agent, what's said goes to the web.
    fun talk(hold: Boolean) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            listen(hold)
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(state.promptRequest) {
        when (state.promptRequest) {
            PromptRequest.Type -> {
                focus.requestFocus()
                keyboard?.show()
            }
            PromptRequest.Send -> send(text)
            PromptRequest.Web -> searchWeb(text)
            PromptRequest.Listen -> talk(hold = false)
            null -> {}
        }
        state.promptRequest = null
    }
    // Search opens focus the bar; drawer gestures leave the keyboard down. Keyed on what's wanted, not on focusSearch:
    // tapping the bar after a swipe up flips focusSearch before the panel shows, and must not hide the keyboard meanwhile.
    val keyboardWanted = panelShown && state.focusSearch
    LaunchedEffect(panelShown, keyboardWanted) {
        // Opened to dictate, the keyboard stays down.
        if (keyboardWanted && !speech.listening) {
            focus.requestFocus()
            keyboard?.show()
        } else if (!keyboardWanted && focused) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    BackHandler(enabled = speech.listening) { speech.cancel() }

    val listening = speech.listening
    // Followed in an effect and read only when drawing the level bar: the mic updates it many times a second,
    // and reading it here would recompose the whole bar each time.
    val level = remember { Animatable(0f) }
    LaunchedEffect(speech, listening) {
        snapshotFlow { if (listening) speech.level else 0f }.collectLatest { level.animateTo(it) }
    }
    // Measured rather than assumed (a large system font makes the bar taller), and passed on while home shows:
    // with the panel open the bar can grow by lines of text that home shouldn't make room for.
    var frame by remember { mutableIntStateOf(0) }
    val gap = with(LocalDensity.current) { HomeGap.roundToPx() }
    val edge = with(LocalDensity.current) { EdgeGap.roundToPx() }
    LaunchedEffect(frame, panelShown, gap, edge) { if (frame > 0 && !panelShown) state.barHeight = frame + gap + edge }
    // The whole bar and the gutter around it focus the field, not just the text: it's the main way in.
    Column(
        modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures {
                    focus.requestFocus()
                    keyboard?.show()
                }
            }.padding(start = EdgeGap, top = HomeGap, end = EdgeGap, bottom = EdgeGap),
    ) {
        speech.error?.takeIf { !listening }?.let {
            BasicText(
                it,
                Modifier
                    // Takes no room: it shows over the recent apps strip rather than pushing it into the grid.
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints)
                        layout(p.width, 0) { p.place(0, -p.height) }
                    }.background(Palette.background)
                    .clickable { speech.error = null }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                style = Mono.copy(color = Palette.red, fontSize = TextSize.Small),
            )
        }
        Box(Modifier.onSizeChanged { frame = it.height }) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = PromptBarHeight)
                    .tile(listening || focused)
                    .padding(start = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DefaultAgentMenu(runtime, asks, onManage = { if (linux) state.show(SettingsPage.Agents) else offerLinux() })
                val shown = if (listening) speech.text else text
                BasicTextField(
                    value = shown,
                    onValueChange = {
                        if (!listening) state.query = it
                        speech.error = null
                    },
                    readOnly = listening,
                    maxLines = 5,
                    textStyle = Mono.copy(fontSize = TextSize.Item, color = if (listening) Palette.accent else Palette.foreground),
                    cursorBrush = SolidColor(Palette.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier =
                        Modifier
                            .weight(1f)
                            .focusRequester(focus)
                            .onFocusChanged {
                                focused = it.isFocused
                                // Tapped (or Super+A): the panel opens. Mid-swipe it's already showing.
                                if (it.isFocused && !panelShown) state.open()
                            }
                            // A hardware Enter submits; Shift+Enter starts a new line. Tab and the arrows move the highlight.
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val oneLine = '\n' !in text
                                when {
                                    e.key == Key.Enter && !e.isShiftPressed -> submit()
                                    e.key == Key.Tab -> pick(if (e.isShiftPressed) -1 else 1)
                                    e.key == Key.DirectionUp && oneLine -> pick(1)
                                    e.key == Key.DirectionDown && oneLine -> pick(-1)
                                    else -> return@onPreviewKeyEvent false
                                }
                                true
                            },
                    // Padding inside the field, so the bar's full height takes taps.
                    decorationBox = { inner ->
                        Box(Modifier.padding(vertical = 12.dp)) {
                        if (shown.isEmpty()) {
                            val hint =
                                when {
                                    listening -> "listening…"
                                    !asks || agent == null -> "open an app or search the web…"
                                    else -> "open an app or ask ${agent.bin}…"
                                }
                            BasicText(hint, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Item), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        inner()
                        }
                    },
                )
                if (text.isNotBlank() && !listening && state.selection == null) {
                    // Says what Enter does, so opening an app instead of asking is never a surprise.
                    Box(
                        Modifier.clickable(onClick = ::submit).padding(horizontal = 10.dp, vertical = 12.dp),
                    ) {
                        val does =
                            when {
                                highlighted >= 0 -> "▸ open"
                                highlighted == BarSearch.WEB -> "↗ search"
                                else -> "⏎ ask"
                            }
                        BasicText(does, style = Mono.copy(color = Palette.accent))
                    }
                }
                MicButton(
                    listening = listening,
                    // A tap dictates into the field and stops at the next pause; another tap stops it sooner.
                    onTap = { if (speech.listening) speech.stop() else talk(hold = false) },
                    onHold = { talk(hold = true) },
                    onRelease = { if (speech.listening) speech.stop() },
                    onCancel = {
                        speech.cancel()
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                )
            }
            // Input level along the bottom edge while listening.
            if (listening) {
                val accent = Palette.accent
                Box(
                    Modifier.matchParentSize().drawBehind {
                        val h = 3.dp.toPx()
                        drawRect(accent, Offset(0f, size.height - h), Size(size.width * (0.05f + 0.95f * level.value), h))
                    },
                )
            }
        }
    }
}

/** Whether the bar sends what's typed to the agent rather than the web: one is installed in Linux, and web isn't picked in its menu. */
internal fun barAsksAgent(
    runtime: Runtime,
    linux: LinuxState,
) = !runtime.barToWeb && linux == LinuxState.Installed && runtime.agents.default != null

/** How long a press on the mic waits before it counts as hold-to-talk rather than a tap or a passing swipe. */
private const val HOLD_MILLIS = 250L

/**
 * Tap to dictate, or hold to talk and release to send. Nothing starts until the press
 * is clearly one or the other: a swipe that only passes over the button (Back from
 * the screen edge, a page swipe) moves away first and is left alone.
 * Sliding well off the button while holding cancels, like voice messages in chat apps.
 */
@Composable
private fun MicButton(
    listening: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
) {
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val release by rememberUpdatedState(onRelease)
    val cancel by rememberUpdatedState(onCancel)
    Box(
        Modifier
            .size(PromptBarHeight)
            .background(if (listening) Palette.red else Color.Transparent)
            .pointerInput(Unit) {
                val slop = 64.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // true: released in time (a tap). false: moved, or taken by another gesture. null: still held.
                    val early =
                        withTimeoutOrNull<Boolean>(HOLD_MILLIS) {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                                if (change.isConsumed) return@withTimeoutOrNull false
                                if (!change.pressed) return@withTimeoutOrNull true
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                            }
                            @Suppress("UNREACHABLE_CODE")
                            false
                        }
                    when (early) {
                        true -> tap()
                        false -> return@awaitEachGesture
                        null -> {
                            hold()
                            var cancelled = false
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) {
                                    if (!cancelled) release()
                                    break
                                }
                                val p = change.position
                                val outside = p.x < -slop || p.y < -slop || p.x > size.width + slop || p.y > size.height + slop
                                if (outside && !cancelled) {
                                    cancelled = true
                                    cancel()
                                }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) { MicIcon(if (listening) Palette.background else Palette.foreground) }
}

/** A microphone drawn on a 24-unit grid, like the home screen's other icons. */
@Composable
internal fun MicIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(2 * u, cap = StrokeCap.Round)
        drawRoundRect(
            color,
            topLeft = androidx.compose.ui.geometry.Offset(9f * u, 2f * u),
            size = androidx.compose.ui.geometry.Size(6f * u, 12f * u),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * u),
            style = stroke,
        )
        drawArc(
            color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(5f * u, 7f * u),
            size = androidx.compose.ui.geometry.Size(14f * u, 11f * u),
            style = stroke,
        )
        drawPath(
            Path().apply {
                moveTo(12f * u, 18f * u)
                lineTo(12f * u, 22f * u)
                moveTo(8f * u, 22f * u)
                lineTo(16f * u, 22f * u)
            },
            color,
            style = stroke,
        )
    }
}

/**
 * Where the bar sends what's typed, at its start: the agent's name, or "web" while it [asks] none; tapping it lists the
 * installed agents and the web to send to. Picking an agent makes it the default one too.
 */
@Composable
private fun DefaultAgentMenu(
    runtime: Runtime,
    asks: Boolean,
    onManage: () -> Unit,
) {
    val store = runtime.agents
    val agent = store.default
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clickable { open = !open }
                .padding(start = 10.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(if (asks) agent?.bin.orEmpty() else "web", style = Mono.copy(color = Palette.accent))
            Symbol(if (open) Glyph.ChevronUp else Glyph.ChevronDown, Palette.muted, Modifier.padding(start = 4.dp))
        }
        if (open) {
            // The agents, then the web (null), which sends to style's web search.
            ChoicePopup(
                store.installed + listOf(null),
                if (asks) store.installed.firstOrNull { it.bin == agent?.bin } else null,
                AboveAnchor,
                onDismiss = { open = false },
                onPick = { picked ->
                    if (picked != null) store.choose(picked)
                    runtime.sendBarToWeb(picked == null)
                },
                label = { it?.name ?: "web" },
                minWidth = 200.dp,
                sublabel = { it?.bin ?: "search ${webSearchName(LauncherStyle.style[Style.WEB_SEARCH])}" },
            ) {
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Palette.border))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            open = false
                            onManage()
                        }.padding(horizontal = 10.dp, vertical = 10.dp),
                ) { BasicText("  manage agents…", style = Mono.copy(color = Palette.muted)) }
            }
        }
    }
}

/** Opens a popup just above its anchor, kept on screen: the bar sits at the bottom. */
private object AboveAnchor : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ) = IntOffset(
        anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0),
        (anchorBounds.top - popupContentSize.height).coerceAtLeast(0),
    )
}
