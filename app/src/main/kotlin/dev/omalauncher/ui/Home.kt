package dev.omalauncher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalWindowInfo
import android.app.WallpaperManager
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.snapshotFlow
import dev.omalauncher.AppWidgetSetupActivity
import dev.omalauncher.widgets.AppWidgetItem
import dev.omalauncher.widgets.Folder
import dev.omalauncher.widgets.HomeItem
import dev.omalauncher.widgets.Shortcut
import dev.omalauncher.widgets.WidgetItem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import dev.omalauncher.Runtime
import dev.omalauncher.widgets.editWidgetCommand
import dev.omalauncher.widgets.fixDependenciesCommand

/** Home pages of JSON widgets on a snap grid, bordered and gapped like Omarchy windows. */
@Composable
fun HomeScreen(
    runtime: Runtime,
    state: LauncherState,
    modifier: Modifier = Modifier,
) {
    val engine = runtime.widgets
    val linuxState by runtime.bootstrap.state.collectAsState()
    val arrange = remember { Arrange() }
    val swiper = rememberHomeSwiper(runtime, state)
    // An empty page waits after the last one only while a tile is carried, to drop it on; otherwise home is just the
    // pages that hold something. Pages only exist while they do, so a page a tile was dropped on stays.
    // Unfolded, pages show in pairs: the pager counts pairs, and an odd last page gets an empty partner.
    val per = if (showsTwoPages(LocalWindowInfo.current.containerSize)) 2 else 1
    val pageNumbers =
        engine.pages
            .let { if (arrange.carry == null || engine.itemsOn(it.last()).isEmpty()) it else it + (it.last() + 1) }
            .let { if (it.size % per == 0) it else it + (it.last() + 1) }
    val pager = rememberPagerState { pageNumbers.size / per }
    // A tile dropped on another page: show that page once the page list has caught up.
    var showPage by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(showPage, pageNumbers) {
        val i = showPage?.let(pageNumbers::indexOf) ?: return@LaunchedEffect
        if (i >= 0) pager.scrollToPage(i / per)
        showPage = null
    }
    var menuFor by remember { mutableStateOf<HomeItem?>(null) }

    val terminals = runtime.terminals
    // Whether home's swipes are on: not while editing, in a folder or typing in a tile.
    val swipesOn = terminals.active == null && state.editing == null && arrange.folder == null
    BackHandler(enabled = terminals.active != null) { terminals.deactivate() }
    BackHandler(enabled = state.homeMenuOpen) { state.homeMenuOpen = false }
    BackHandler(enabled = state.editing != null && !state.homeMenuOpen) { state.editing = null }
    BackHandler(enabled = arrange.folder != null && !state.homeMenuOpen) { arrange.folder = null }
    LaunchedEffect(engine) {
        engine.launcherAction = { action ->
            when (action) {
                // "themes" from before the screen was style; widgets still send it.
                "style", "themes" -> state.show(SettingsPage.Style)
                "agents" -> state.show(SettingsPage.Agents)
                "store" -> state.show(SettingsPage.Store)
                "linux" -> state.show(SettingsPage.Linux)
                "drawer" -> state.open()
            }
        }
    }
    // Folding or unfolding keeps the page that was showing: unfolded, the pair it's in; folded, the left one of the pair.
    var shownPer by remember { mutableIntStateOf(per) }
    LaunchedEffect(per) {
        if (per == shownPer) return@LaunchedEffect
        val i = pageNumbers.indexOf(state.currentPage)
        shownPer = per
        if (i >= 0) pager.scrollToPage(i / per)
    }
    // The drawer pins apps to whichever page is showing (the left one of a pair).
    LaunchedEffect(pager.currentPage, pageNumbers) {
        state.currentPage = pageNumbers.getOrElse(pager.currentPage * per) { 1 }
        engine.activePage = state.currentPage
    }
    // The background slides with the pages, like stock launchers; a wallpaper Android draws (ours, or the user's own) is sent the offsets.
    val view = LocalView.current
    DisposableEffect(engine, view) {
        engine.view = view
        onDispose { if (engine.view === view) engine.view = null }
    }
    LaunchedEffect(pager, view) {
        val wallpapers = WallpaperManager.getInstance(view.context)
        snapshotFlow {
            val last = pager.pageCount - 1
            val offset = if (last <= 0) 0.5f else ((pager.currentPage + pager.currentPageOffsetFraction) / last).coerceIn(0f, 1f)
            // Also resent when our live wallpaper is picked, which starts out centered.
            offset to runtime.themes.wallpaperBehind
        }.collect { (offset, behind) ->
            state.wallpaperOffset = offset
            if (behind) {
                view.windowToken?.let {
                    wallpapers.setWallpaperOffsetSteps(if (pager.pageCount > 1) 1f / (pager.pageCount - 1) else 1f, 1f)
                    wallpapers.setWallpaperOffsets(it, offset, 0.5f)
                }
            }
        }
    }
    // A terminal tile keeps the keyboard only while its page shows.
    LaunchedEffect(pager.currentPage) { terminals.deactivate() }
    // Its shell exited, or the app lost focus: the soft keyboard goes too.
    val typing = terminals.active != null
    LaunchedEffect(typing) { if (!typing) terminals.keyboard?.hide() }
    LaunchedEffect(state.requestedPage) {
        state.requestedPage?.let { if (it < pageNumbers.size) pager.animateScrollToPage(it / per) }
        state.requestedPage = null
    }

    fun openMenu(item: HomeItem?) {
        menuFor = item
        state.homeMenuOpen = true
    }

    fun remove(item: HomeItem) {
        state.editing = null
        when (item) {
            is WidgetItem -> engine.removeWidget(item.spec)
            is Shortcut, is Folder -> engine.shortcuts.remove(item.key)
            is AppWidgetItem -> engine.appWidgets.remove(item.key)
        }
        engine.layout.remove(item.key)
    }

    /** Opens the default agent on the widget's file; with no agent installed, the agents screen instead. */
    fun edit(item: WidgetItem) {
        state.editing = null
        val agent = runtime.agents.default
        if (agent == null) state.show(SettingsPage.Agents) else runtime.terminal(editWidgetCommand(agent.bin, item.spec), "Edit ${item.spec.name}", agent)
    }

    // Overlays below are siblings of the pager inside this Box, so they never change the page's size.
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    // Where the keyboard ends up, so a terminal tile resizes once rather than with every frame of its animation.
    @OptIn(ExperimentalLayoutApi::class)
    val imeTarget = WindowInsets.imeAnimationTarget
    val statusBar = WindowInsets.statusBars.union(WindowInsets.displayCutout)
    // Back with the soft keyboard up only reaches the keyboard app, which closes itself. Closed like that
    // (not by the tile's ⌨ button), the tile lets go too, so the bar and recent apps come back. Only once it's gone:
    // hiding it again mid-way, while a back swipe still drives its animation, left home taking no touches.
    LaunchedEffect(terminals) {
        snapshotFlow { ime.getBottom(density) > 0 }.collect { shown ->
            if (!shown && terminals.keyboard?.wanted == true) terminals.deactivate()
        }
    }
    // The bar and recent apps don't wait for that: back as the keyboard starts closing, like the pages.
    LaunchedEffect(terminals) {
        snapshotFlow { imeTarget.getBottom(density) == 0 && terminals.keyboard?.wanted == true }.collect { terminals.keyboardLeaving = it }
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val extraKeys = terminals.active != null && extraKeysWanted()
    val side = homeSide()
    val turned = homeTurned()
    val sideRoom = sideWidth()
    // Without the bar on home (home.prompt-bar), only the gap to the edge stays below the strip.
    val barRoom =
        if (!barOnHome(runtime)) {
            EdgeGap
        } else {
            with(density) { state.barHeight.takeIf { it > 0 }?.toDp() ?: (PromptBarHeight + HomeGap + EdgeGap) }
        }
    LaunchedEffect(extraKeys) { if (!extraKeys) terminals.extraKeys = null }
    Box(
        modifier
            .onGloballyPositioned { origin = it.positionInWindow() }
            // Sees every press first. The tile's own overlay never hears touches on its TextureView.
            // A still tap wakes an inert tile. A drag up or down scrolls it on that same press.
            // A sideways drag on an inert tile is left for the pager. One outside the active tile ends its turn.
            .pointerInput(terminals, swiper) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val start = down.position
                    val windowStart = origin + start
                    if (terminals.keyboardButton?.contains(windowStart) == true) return@awaitEachGesture
                    terminals.pressAt(windowStart)
                    val term = terminals.terminalAt(windowStart)
                    val wasActive = term != null && terminals.active === term
                    // The active tile owns this press. Take it before the overlay, which would
                    // otherwise scroll the same drag again on the places it can hear.
                    if (wasActive) down.consume()
                    var scrolling = false
                    var turnedPage = false
                    // Handed to home's swipe (search, the drawer, the shade), as a swipe on any other tile would be.
                    var homing = false
                    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, start) }
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            if (scrolling) terminals.endDrag()
                            if (homing) swiper.stop(0f)
                            break
                        }
                        val delta = change.position - start
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!scrolling && !turnedPage && !homing && term != null && delta.getDistance() > slop) {
                            // Read now, not when the press began: home's swipes stay off while a tile is in use.
                            val homeTakes = terminals.active == null && state.editing == null && arrange.folder == null && swiper.wants(delta.y)
                            when (terminalGesture(wasActive, delta.x, delta.y, homeTakes)) {
                                TerminalGesture.TurnPage -> turnedPage = true
                                TerminalGesture.ScrollTile -> scrolling = true
                                TerminalGesture.Home -> {
                                    homing = true
                                    change.consume()
                                    swiper.start()
                                    swiper.drag(delta.y)
                                    // A flick can cross the slop as it lifts: finish it here, or the drawer stays ajar.
                                    if (!change.pressed) {
                                        swiper.stop(tracker.calculateVelocity().y)
                                        break
                                    }
                                    continue
                                }
                            }
                        }
                        when {
                            homing -> {
                                change.consume()
                                if (change.pressed) {
                                    swiper.drag(change.position.y - change.previousPosition.y)
                                } else {
                                    swiper.stop(tracker.calculateVelocity().y)
                                    break
                                }
                            }
                            scrolling && term != null -> {
                                change.consume()
                                if (change.pressed) {
                                    terminals.drag(term, down.id.value.toInt(), windowStart, origin + change.position, wasActive)
                                } else {
                                    terminals.endDrag()
                                    break
                                }
                            }
                            !change.pressed -> {
                                if (!turnedPage && delta.getDistance() <= slop) {
                                    if (wasActive) {
                                        change.consume()
                                        terminals.clickAt(windowStart)
                                    } else {
                                        terminals.activateAt(windowStart)
                                    }
                                }
                                break
                            }
                            turnedPage -> break
                        }
                    }
                }
            },
    ) {
        // Owns the soft keyboard for terminal tiles.
        AndroidView(
            factory = { KeyboardSink(it, runtime.keyboard).also { sink -> terminals.keyboard = sink } },
            onRelease = { if (terminals.keyboard === it) terminals.keyboard = null },
            modifier = Modifier.size(1.dp),
        )
    Column(
        Modifier
            .fillMaxSize()
            // Swiping up opens the drawer and down search, or what gesture settings say (the notification shade, say);
            // not while editing, in a folder or typing in a tile. A long-press drag takes its touches first, and the
            // pager only wants sideways ones.
            .homeSwipe(runtime, state, enabled = swipesOn)
            // A widget's list at its end hands on the swipe, so pulling one down at its top still opens search.
            .homeSwipeAfterScroll(swiper, enabled = swipesOn)
            // Typing in a terminal tile: lift the pages so the tile stays above the keyboard, but not under the status
            // bar; a tile too tall for both gives up the rest at its bottom.
            .graphicsLayer {
                val keys = if (extraKeys) ExtraKeysHeight.toPx() else 0f
                translationY =
                    -terminals.liftFor(
                        imeHeight = ime.getBottom(density),
                        imeTarget = imeTarget.getBottom(density),
                        windowHeight = view.height,
                        margin = 8.dp.toPx() + keys,
                        top = statusBar.getTop(density) + EdgeGap.toPx(),
                        minHeight = 64.dp.toPx(),
                    )
            }
            // Nothing below where the typing tile ends, above the extra keys, so the gaps around them show the wallpaper, and
            // nothing lifted under the status bar.
            .drawWithContent {
                val keyboard = ime.getBottom(density)
                if (terminals.active == null || keyboard == 0) {
                    drawContent()
                } else {
                    val keys = if (extraKeys) ExtraKeysHeight.toPx() else 0f
                    val lift = terminals.lift - origin.y
                    clipRect(
                        top = statusBar.getTop(density) + lift,
                        bottom = view.height - keyboard - keys - 8.dp.toPx() + lift,
                    ) { this@drawWithContent.drawContent() }
                }
            }
            // Room for the bar, which DesktopScreen draws over the pages (so the keyboard lifts it without shrinking
            // the grid). The grid ends with a gap between tiles; below it the strip, another gap, the bar and the gap
            // to the edge: the bar as measured, since a large font makes it taller. In a phone's landscape they stand beside it instead, on
            // the side portrait's bottom turned to.
            .padding(
                start = if (side == HomeSide.Left) sideRoom else 0.dp,
                end = if (side == HomeSide.Right) sideRoom else 0.dp,
                bottom = if (side != null) 0.dp else barRoom,
            ),
    ) {
        HomeGrid(
            pager = pager,
            pages = pageNumbers,
            itemsOn = engine::itemsOn,
            layout = engine.layout,
            arrange = arrange,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            selected = state.editing,
            onSelect = { state.editing = it },
            actions = { item ->
                // A pinned Android app offers its shortcuts above the bar, as its long-press menu in the drawer does.
                val app = (item as? Shortcut)?.component?.let { runtime.androidApps.pinned(it, item.profile) }
                val (menu, offered) =
                    offeredShortcuts(item.key) {
                        app?.let { shortcutMenu(runtime, it, page = { item.page }, onOpen = { arrange.folder = null }) }
                    }
                // Nothing until they're loaded, so the bar doesn't jump down as they arrive.
                if (offered != null) Column {
                    if (menu != null && offered.isNotEmpty()) {
                        MenuItems(emptyList(), { state.editing = null }, minWidth = 160.dp, itemPadding = 12.dp, fontSize = TextSize.Item) {
                            ShortcutRows(offered, menu, { state.editing = null }, TextSize.Item, rule = false)
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    EditBar {
                        if (item is WidgetItem) EditAction("edit") { edit(item) }
                        // As a QR code or link, or (your own) to the store. An agent's tile is a terminal, not a widget to pass on.
                        if (item is WidgetItem && !item.spec.file.name.startsWith("agent-")) {
                            EditAction("share") {
                                state.editing = null
                                state.sharing = item.spec
                            }
                        }
                        if (item is WidgetItem) GlassActions(engine.layout, item.spec.file.name)
                        // An Android widget that lets its settings change later, in its own screen.
                        if (item is AppWidgetItem && engine.appWidgets.reconfigurable(item.id)) {
                            EditAction("settings") {
                                state.editing = null
                                view.context.startActivity(AppWidgetSetupActivity.reconfigure(view.context, item.id))
                            }
                        }
                        EditAction("remove", Palette.red) { remove(item) }
                    }
                }
            },
            render = { item, tile, highlighted ->
                when (item) {
                    is WidgetItem -> {
                        // Whose terminal has the keyboard: the tile gives up what the keyboard covers once the lift is spent.
                        val typing = engine.instance(item.spec)?.owns(terminals.active) == true
                        WidgetTile(
                            spec = item.spec,
                            instance = engine.instance(item.spec),
                            // As shown: on its side in landscape.
                            cells = engine.layout.cell(item.key)?.let { if (turned) it.h to it.w else it.w to it.h },
                            // Accent like Hyprland's active border: only while one of its terminals has the keyboard.
                            focused = highlighted || typing,
                            modifier =
                                if (typing) {
                                    tile
                                        .onGloballyPositioned { terminals.tile = it.boundsInWindow().translate(0f, terminals.lift) }
                                        .padding(bottom = with(density) { terminals.cut.toDp() })
                                } else {
                                    tile
                                },
                            onTap = { engine.tap(item.spec) },
                            needs = engine.missing[item.spec.file.name].orEmpty(),
                            install = engine.installs[item.spec.file.name],
                            onInstall = { engine.installDependencies(item.spec) },
                            waitsForLinux = if (engine.waitsForLinux(item.spec)) linuxState else null,
                            onInstallLinux = { state.show(SettingsPage.Linux) },
                            glass = engine.layout.glass(item.spec.file.name),
                            onFix = { log ->
                                val agent = runtime.agents.default
                                if (agent == null) {
                                    state.show(SettingsPage.Agents)
                                } else {
                                    runtime.terminal(fixDependenciesCommand(agent.bin, item.spec, log), "Fix ${item.spec.name}", agent)
                                }
                            },
                        )
                    }
                    is Shortcut ->
                        ShortcutTile(
                            shortcut = item,
                            label = runtime.shortcutLabel(item),
                            icon = runtime.shortcutIcon(item, LauncherStyle.monochromeIcons),
                            focused = highlighted,
                            modifier = tile,
                            onTap = {
                                // Opening an app from a folder closes the folder, like Android.
                                arrange.folder = null
                                runtime.open(item)
                            },
                            paused = runtime.androidApps.paused(item.profile),
                            clone = runtime.androidApps.cloned(item.profile),
                        )
                    is Folder ->
                        FolderTile(
                            folder = item,
                            icons = item.apps.take(4).map { runtime.shortcutIcon(it, LauncherStyle.monochromeIcons) },
                            labels = item.apps.take(4).map { runtime.shortcutLabel(it) },
                            focused = highlighted,
                            modifier = tile,
                            onTap = { arrange.folder = item.key },
                        )
                    is AppWidgetItem -> AppWidgetTile(item, engine.appWidgets, highlighted, tile, swiper.takeIf { swipesOn })
                }
            },
            // The strip's room (the dock or recent apps) and the pill's above it: kept while they're empty, so tiles don't
            // reflow when they fill, and still home's to swipe and long-press there until they do.
            room = if (side == null) homeStripRoom(runtime) else 0.dp,
            turned = turned,
            side = side,
            per = per,
            onLongPressEmpty = { openMenu(null) },
            onNoRoom = { item -> showPage = engine.moveToFreePage(item) },
            onMove = { item, page ->
                engine.moveToPage(item, page)
                showPage = page
            },
            onRelocate = engine::moveToPage,
            onGroup = { app, target -> engine.shortcuts.group(app, target) },
            onReorder = { app, before -> engine.shortcuts.moveBefore(app.key, before) },
        )
    }

    if (extraKeys) {
        ExtraKeys(
            runtime.keyboard,
            Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .onGloballyPositioned { terminals.extraKeys = it.boundsInWindow() },
        )
    }

    }
}


/**
 * The room below the grid for the strip (the dock or recent apps) and the pill above it, each only while it shows.
 * Added up in whole pixels, as the column drawing them rounds each part, so the pill can't reach into the grid's gap.
 */
@Composable
internal fun homeStripRoom(runtime: Runtime): Dp =
    with(LocalDensity.current) {
        val strip = if (LauncherStyle.recentApps) stripHeight.roundToPx() else 0
        val pill = if (LauncherStyle.homePill && pillLabel(runtime.gestures.swipeDown) != null) PillHeight.roundToPx() + HomeGap.roundToPx() else 0
        (strip + pill).toDp()
    }

/** Whether the prompt bar stays on home: style says so, or nothing else opens the app panel (no swipe, no pill). */
internal fun barOnHome(runtime: Runtime) = LauncherStyle.promptBarOnHome || !runtime.gestures.reachPanel
