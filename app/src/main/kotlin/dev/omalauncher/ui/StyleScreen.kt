package dev.omalauncher.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import dev.omalauncher.Runtime
import dev.omalauncher.WEB_SEARCHES
import dev.omalauncher.linux.LauncherFont
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.linux.TerminalFont
import dev.omalauncher.linux.TerminalText
import dev.omalauncher.theme.OmarchyTheme
import dev.omalauncher.theme.Setting
import dev.omalauncher.theme.Style
import dev.omalauncher.theme.StyleStore
import dev.omalauncher.theme.ThemeDirs
import dev.omalauncher.theme.ThemeDownload
import dev.omalauncher.theme.ThemeStore
import dev.omalauncher.theme.Toml
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Omarchy's Style menu: the theme, its background, how the home screen
 * draws itself and, with Linux, the terminal's text size. Phone build: in
 * tabs along the top ([StyleTab]), or down the side on a wide screen.
 */
@Composable
fun StyleScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
    /** Opens the community themes over this screen. */
    onBrowse: () -> Unit,
    /** Opens the icon packs over this screen. */
    onIconPacks: () -> Unit,
) {
    val store = runtime.themes
    val scope = rememberCoroutineScope()
    RescanThemes(store)
    var installOpen by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<OmarchyTheme?>(null) }
    val linux by runtime.bootstrap.state.collectAsState()
    var tab by remember { mutableStateOf(lastStyleTab) }
    // The terminal's only with Linux, whose fonts it lists.
    val tabs = StyleTab.entries.filter { it != StyleTab.Terminal || linux == LinuxState.Installed }
    val shown = tab.takeIf { it in tabs } ?: StyleTab.Theme
    val pick = { t: StyleTab ->
        tab = t
        lastStyleTab = t
    }
    OverlayPanel("style", onDismiss) {
        val pane = @Composable { modifier: Modifier ->
            // Each tab opens at its top.
            key(shown) {
                Column(modifier.verticalScroll(rememberScrollState())) {
                    StylePane(
                        shown,
                        runtime,
                        linux,
                        onBrowse = onBrowse,
                        onIconPacks = onIconPacks,
                        onInstall = {
                            store.clearInstallError()
                            installOpen = true
                        },
                        onRemove = { removing = it },
                    )
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            if (maxWidth >= STYLE_PANES_WIDTH) {
                // Side by side, as the store is on a wide screen: the tabs down the side, the one picked beside them.
                Row {
                    Column(Modifier.width(150.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (t in tabs) SideTab(t.label, t == shown) { pick(t) }
                    }
                    Box(Modifier.padding(horizontal = 12.dp).width(1.dp).fillMaxHeight().background(Palette.border))
                    pane(Modifier.weight(1f))
                }
            } else {
                Column {
                    // Scrolls under the panel's padding to its frame, as the store's tabs do.
                    Row(Modifier.bleed(PanelPadding).horizontalScroll(rememberScrollState()).padding(horizontal = PanelPadding), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (t in tabs) TabChip(t.label, t == shown) { pick(t) }
                    }
                    Spacer(Modifier.height(4.dp))
                    pane(Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
        if (installOpen) {
            TextDialog(
                "install theme",
                "",
                "install",
                hint = "A theme's GitHub repo. It replaces an installed theme of the same name.",
                onDismiss = { installOpen = false },
            ) { url ->
                installOpen = false
                scope.launch { store.install(url) }
            }
        }
        removing?.let { theme ->
            val builtIn = theme.dirs.size > 1
            ConfirmDialog(
                "remove ${theme.title.lowercase()}?",
                if (builtIn) "Your changes to it go; Omarchy's version stays." else "It's deleted from the phone and from Linux.",
                "remove",
                onDismiss = { removing = null },
            ) {
                removing = null
                scope.launch { store.remove(theme) }
            }
        }
    }
}

/** The Style screen's tabs, each a pane of its settings. */
private enum class StyleTab(
    val label: String,
) {
    Theme("theme"),
    Home("home"),
    Drawer("drawer"),
    Widgets("widgets"),
    Bars("bars"),
    Terminal("terminal"),
}

/** The tab shown last: Style opens on it again while the launcher runs. */
private var lastStyleTab = StyleTab.Theme

/** How wide the panel's content is when Style's tabs go down the side, the one picked beside them, as the store's grid and page do. */
private val STYLE_PANES_WIDTH = 720.dp

/** One of Style's tabs: the theme and background, home, the drawer, widgets and screens, the system bars, the terminal. */
@Composable
private fun StylePane(
    tab: StyleTab,
    runtime: Runtime,
    linux: LinuxState,
    onBrowse: () -> Unit,
    onIconPacks: () -> Unit,
    onInstall: () -> Unit,
    onRemove: (OmarchyTheme) -> Unit,
) {
    val store = runtime.themes
    val style = runtime.style
    when (tab) {
        StyleTab.Theme -> {
            ThemeSection(store, onBrowse = onBrowse, onInstall = onInstall, onRemove = onRemove)
            store.current?.let { BackgroundSection(store, style, it) }
            if (linux == LinuxState.Installed) {
                // Linux's fonts, which the terminal tab lists too.
                Heading("launcher & widgets font")
                val fonts = listOf(Style.SYSTEM_FONT) + runtime.terminalFont.families
                FontOption(fonts, style.style[Style.FONT], label = { if (it == Style.SYSTEM_FONT) "system default" else it }) { family ->
                    style.update { it.with(Style.FONT, family) }
                }
            }
        }
        // Style choices don't depend on a theme, so they're offered before themes are downloaded too.
        StyleTab.Home -> {
            Flag("icons", "monochrome", "colored", style, Style.MONOCHROME_ICONS)
            IconPackOption(runtime, onIconPacks)
            Flag("home apps", "boxed", "icon only", style, Style.APP_TILES)
            Flag("app names", "shown", "hidden", style, Style.APP_LABELS)
            // The strip above the bar: the dock you fill, the apps used lately, or neither.
            StyleOption(
                "strip",
                listOf("dock", "recent", "hidden"),
                when {
                    !style.style[Style.RECENT_APPS] -> 2
                    style.style[Style.DOCK] -> 0
                    else -> 1
                },
            ) { i -> style.update { if (i == 2) it.with(Style.RECENT_APPS, false) else it.with(Style.RECENT_APPS, true).with(Style.DOCK, i == 0) } }
            Flag("dock", "boxed", "bare", style, Style.DOCK_BOX)
            NumberOption("dock size", listOf("small" to 56.0, "large" to 100.0), Style.DOCK_HEIGHT, style, unit = "dp")
            NumberOption("settings strip", listOf("off" to 0.0, "on" to 24.0), Style.SETTINGS_STRIP, style, unit = "dp")
            OptionHint("long-press the space between home's apps and the dock for settings")
            // Does what swiping down does, as gestures set it.
            Flag("pill", "shown", "hidden", style, Style.HOME_PILL)
            Flag("prompt bar", "always", "with apps", style, Style.PROMPT_BAR)
            // What the bar's web row searches with; another address in style.toml shows as custom.
            val web = style.style[Style.WEB_SEARCH] ?: WEB_SEARCHES.first().second
            StyleOption("web search", WEB_SEARCHES.map { it.first }, WEB_SEARCHES.indexOfFirst { it.second == web }, web) { i ->
                style.update { it.with(Style.WEB_SEARCH, if (i == 0) null else WEB_SEARCHES[i].second) }
            }
            NumberOption("rows", listOf("auto" to 0.0) + (7..12).map { "$it" to it.toDouble() }, Style.HOME_ROWS, style)
            OptionHint("tiles below the last row move up at their size, or to a later page; more rows later won't move them back")
            CountOption("columns", Style.COLUMNS, style)
            Flag("pages", "one", "auto", style, Style.FORCE_SINGLE_PAGE)
            NumberOption("corners", Corners.entries.map { it.label to it.scale.toDouble() }, Style.CORNER_SCALE, style)
            HomePreview(runtime)
        }
        StyleTab.Drawer -> {
            StyleOption("sort", listOf("a–z", "most used", "newest"), Style.DRAWER_SORT.options.indexOf(style.style[Style.DRAWER_SORT])) { i ->
                style.update { it.with(Style.DRAWER_SORT, Style.DRAWER_SORT.options[i]) }
            }
            Flag("letter rail", "shown", "hidden", style, Style.DRAWER_LETTERS)
            Flag("most used row", "shown", "hidden", style, Style.DRAWER_SUGGESTIONS)
            Flag("new apps", "dot", "no dot", style, Style.DRAWER_NEW)
            // Phone build: one list, or a page per category as Smart Launcher's app page.
            StyleOption("layout", listOf("list", "pages"), Style.DRAWER_LAYOUT.options.indexOf(style.style[Style.DRAWER_LAYOUT])) { i ->
                style.update { it.with(Style.DRAWER_LAYOUT, Style.DRAWER_LAYOUT.options[i]) }
            }
            StyleOption("categories", listOf("smart launcher", "android"), Style.DRAWER_CATEGORIES.options.indexOf(style.style[Style.DRAWER_CATEGORIES])) { i ->
                style.update { it.with(Style.DRAWER_CATEGORIES, Style.DRAWER_CATEGORIES.options[i]) }
            }
            OptionHint("pages: swipe sideways between categories, or tap one in the bar under them; your groups get pages too")
            Flag("list", "sections", "one list", style, Style.DRAWER_SECTIONS)
            NumberOption("columns", listOf("auto" to 0.0) + (4..7).map { "$it" to it.toDouble() }, Style.DRAWER_COLUMNS, style)
            NumberOption("icons", listOf("small" to 44.0, "normal" to 52.0, "large" to 64.0), Style.DRAWER_ICON, style, unit = "dp")
            Flag("app names", "shown", "hidden", style, Style.DRAWER_LABELS)
            NumberOption("background", listOf("solid" to 1.0, "dim" to 0.97, "see-through" to 0.8), Style.DRAWER_OPACITY, style)
            NumberOption("blur", listOf("none" to 0.0, "soft" to 12.0, "strong" to 28.0), Style.DRAWER_BLUR, style, unit = "dp")
            // Turning the lock off asks for it, or anyone holding the phone could.
            val context = LocalContext.current
            StyleOption("hidden apps", listOf("locked", "open"), if (style.style[Style.DRAWER_LOCK]) 0 else 1) { i ->
                val lock = i == 0
                if (lock || !style.style[Style.DRAWER_LOCK]) {
                    style.update { it.with(Style.DRAWER_LOCK, lock) }
                } else {
                    unlock(context, "turn off the lock on hidden apps") { style.update { it.with(Style.DRAWER_LOCK, false) } }
                }
            }
        }
        StyleTab.Widgets -> {
            // Like Hyprland's gaps_in, gaps_out and border_size; the colors are in style.toml.
            Heading("widgets")
            OpacityStepper("opacity", style, Style.WIDGET_OPACITY)
            Stepper("blur", style, Style.WIDGET_BLUR, step = 4.0)
            Stepper("gaps in", style, Style.GAPS_IN)
            Stepper("gaps out", style, Style.GAPS_OUT)
            Stepper("border width", style, Style.TILE_BORDER_WIDTH)
            WidgetPreview()
            Heading("screens")
            Stepper("gaps out", style, Style.SCREEN_GAPS_OUT)
            Stepper("border width", style, Style.SCREEN_BORDER_WIDTH)
        }
        StyleTab.Bars -> {
            // Both bars at once; style.toml can set them apart. Background is the band behind their icons.
            Flag("status bar", "shown", "hidden", style, Style.STATUS_BAR_SHOWN)
            Flag("background", "shown", "hidden", style, Style.STATUS_BAR.framed, Style.NAVIGATION_BAR.framed)
            Stepper("border width", style, Style.STATUS_BAR.borderWidth, Style.NAVIGATION_BAR.borderWidth)
            OpacityStepper("opacity", style, Style.STATUS_BAR.opacity, Style.NAVIGATION_BAR.opacity)
        }
        StyleTab.Terminal -> TerminalSection(runtime.terminalFont, runtime.launcherFont, style)
    }
    if (linux == LinuxState.Installed) {
        BasicText(
            "more settings, like border and bar colors: ~/${StyleStore.GUEST_DIR}/defaults.toml",
            Modifier.padding(top = 20.dp),
            style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
        )
    }
}

/** A tab of Style along the top, framed in the accent while it's the one showing, as the store's are. */
@Composable
private fun TabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    BasicText(
        label,
        Modifier
            .frame(1.dp, if (selected) Palette.accent else Palette.border, if (selected) Palette.accent.copy(alpha = 0.15f) else Color.Transparent, radius = 14.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        style = Mono.copy(color = if (selected) Palette.accent else Palette.foreground),
    )
}

/** A tab of Style down the side on a wide screen: the one showing on the surface in the accent. */
@Composable
private fun SideTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    BasicText(
        label,
        Modifier
            .fillMaxWidth()
            .clip(LauncherStyle.shape(12.dp))
            .background(if (selected) Palette.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        style = Mono.copy(color = if (selected) Palette.accent else Palette.foreground),
    )
}

/** The installed themes in a row, starting at the current one; browse has Omarchy's whole catalog. */
@Composable
private fun ThemeSection(
    store: ThemeStore,
    onBrowse: () -> Unit,
    onInstall: () -> Unit,
    onRemove: (OmarchyTheme) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val current = store.current
    Heading("theme") {
        BracketAction("browse", Palette.accent, onBrowse)
        BracketAction("install", Palette.accent, onInstall)
    }
    InstallStatus(store)
    if (!store.complete) {
        DownloadPrompt(store.download, partial = store.themes.isNotEmpty()) { scope.launch { store.ensureRelease() } }
        Spacer(Modifier.height(8.dp))
    }
    if (store.themes.isNotEmpty()) {
        val start = store.themes.indexOfFirst { it.name == current?.name }.coerceAtLeast(0)
        LazyRow(
            // Scrolls under the panel's padding to its frame, starting aligned with the heading.
            Modifier.bleed(PanelPadding),
            state = rememberLazyListState(start),
            contentPadding = PaddingValues(horizontal = PanelPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(store.themes, key = { it.name }) { theme ->
                ThemeCard(theme, selected = theme.name == current?.name, Modifier.width(160.dp)) { scope.launch { store.apply(theme) } }
            }
        }
    }
    if (current?.own != null) {
        val source = remember(current) { ThemeDirs.sourceOf(current) }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                source?.label ?: "your own theme",
                Modifier.weight(1f),
                style = Mono.copy(color = Palette.muted),
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (source != null) BracketAction("update", Palette.accent) { scope.launch { store.update(current) } }
            BracketAction("remove", Palette.red) { onRemove(current) }
        }
    }
}

/**
 * The theme's backgrounds and the user's own images, Android's own wallpaper (live ones
 * included) instead of them, and handing the theme's to Android.
 */
@Composable
private fun BackgroundSection(
    store: ThemeStore,
    style: StyleStore,
    theme: OmarchyTheme,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Keyed on the background too, so an image just added shows up; removals bump [removed].
    var removed by remember { mutableStateOf(0) }
    val backgrounds = remember(theme, store.background, removed) { theme.backgrounds() }
    var removing by remember { mutableStateOf<File?>(null) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { store.addBackground(uri) }
    }
    Heading("background")
    StyleOption(
        "wallpaper",
        listOf("android's", "the theme's"),
        if (store.androidWallpaper) 0 else 1,
    ) { i ->
        style.update { it.with(Style.ANDROID_WALLPAPER, i == 0) }
        scope.launch { store.refreshLiveWallpaper() }
    }
    if (store.androidWallpaper) {
        // The window stays clear; pages slide Android's wallpaper through its offsets.
        OptionHint("the launcher draws nothing over it; Linux apps keep the theme's background")
    } else {
        LazyRow(Modifier.bleed(PanelPadding), contentPadding = PaddingValues(horizontal = PanelPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Omarchy's Install > Style > Background, with Android's image picker instead of a folder. First, so your
            // own image is a tap away rather than past every preset.
            item(key = "add") {
                Box(
                    Modifier
                        .width(120.dp)
                        .aspectRatio(16f / 10f)
                        .frame(2.dp, Palette.border, Palette.surface, radius = 12.dp)
                        .clickable { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("[ + image ]", style = Mono.copy(color = Palette.accent))
                }
            }
            items(backgrounds, key = { it.path }) { file ->
                Thumbnail(
                    file,
                    selected = file == store.background,
                    modifier = Modifier.width(120.dp).aspectRatio(16f / 10f),
                    onClick = { scope.launch { store.setBackground(file) } },
                    // Only the user's own images; the theme's come back with it anyway.
                    onLongClick = if (theme.isExtraBackground(file)) ({ removing = file }) else null,
                )
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            // Some phones (Android Go) have no live wallpaper picker.
            .clickable(enabled = !store.liveWallpaperActive) { runCatching { context.startActivity(store.liveWallpaperIntent()) } }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (store.liveWallpaperActive) {
            // SystemUI defers live-wallpaper color changes until the screen turns off.
            BasicText(
                "✓ android wallpaper & system colors follow the theme (system colors update after the screen turns off)",
                style = Mono.copy(color = Palette.green),
            )
        } else {
            BasicText("[ use as android wallpaper + system colors ]", style = Mono.copy(color = Palette.accent))
        }
    }
    removing?.let { file ->
        ConfirmDialog(
            "remove background?",
            "It's deleted from the phone and from Linux.",
            "remove",
            onDismiss = { removing = null },
        ) {
            removing = null
            scope.launch {
                store.removeBackground(file)
                removed++
            }
        }
    }
}

/** The terminals' font and the size they open at, on top of Android's font size; see [TerminalFont]. */
@Composable
private fun TerminalSection(
    font: TerminalFont,
    launcherFont: LauncherFont,
    style: StyleStore,
) {
    LaunchedEffect(Unit) { font.refreshFamilies() }
    Heading("terminal")
    FontOption(font.families, font.family) { family -> style.update { it.with(Style.TERMINAL_FONT, family) } }
    OptionHint("terminals opened from now on · more with pacman, like ttf-cascadia-code", Modifier.padding(bottom = 4.dp))
    NumberOption("text", TerminalText.entries.map { it.label to it.em.toDouble() }, Style.TERMINAL_TEXT, style, unit = " dp")
    OpacityStepper("opacity", style, Style.TERMINAL_OPACITY)
    OptionHint("see-through also needs foot's alpha, under [colors] in ~/.config/foot/foot.ini", Modifier.padding(bottom = 4.dp))
    Flag("keyboard", "voice + clipboard", "plain keys", style, Style.TERMINAL_KEYBOARD_TEXT)
    OptionHint("voice + clipboard shows the keyboard's mic and clipboard; it may autocorrect commands · from the next time the keyboard opens", Modifier.padding(bottom = 4.dp))
    // In portrait, where columns run short; landscape has plenty.
    val metrics = LocalContext.current.resources.displayMetrics
    val columns = font.columns(minOf(metrics.widthPixels, metrics.heightPixels))
    OptionHint("${TerminalFont.format(font.size).removeSuffix(".0")} pt · $columns columns in portrait", Modifier.padding(top = 2.dp))
    TerminalPreview(font, launcherFont)
}

/**
 * Terminal output in the chosen [TerminalFont] and the theme's terminal colors,
 * drawn here as foot will draw it: at its pixel size and pad, wrapped at its
 * columns, without ligatures. The family loads from Linux's files; until it
 * has, the one before it (or Android's monospace) stands in.
 */
@Composable
private fun TerminalPreview(
    font: TerminalFont,
    launcherFont: LauncherFont,
) {
    val family by produceState<FontFamily?>(null, font.family) { value = launcherFont.family(font.family) }
    val density = LocalDensity.current
    val style =
        TextStyle(
            fontFamily = family ?: FontFamily.Monospace,
            fontSize = with(density) { font.emPx.toSp() },
            color = Palette.foreground,
            fontFeatureSettings = "liga 0, calt 0",
        )
    val measurer = rememberTextMeasurer()
    PreviewLabel()
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .frame(2.dp, Palette.border, Palette.background, radius = 12.dp)
            .padding(with(density) { font.padPx.toDp() }),
    ) {
        val cell = remember(style) { measurer.measure("0".repeat(100), style).size.width / 100f }
        val columns = (constraints.maxWidth / cell).toInt().coerceAtLeast(1)
        val palette = Palette.current
        BasicText(remember(columns, palette) { previewText(columns) }, style = style, softWrap = false)
    }
}

/**
 * A sketch of home's bottom: a row of pinned apps, the recent strip and the prompt bar,
 * drawn with home's own frames, so the options above show as they change.
 */
@Composable
private fun HomePreview(runtime: Runtime) {
    val columns = runtime.style.style[Style.COLUMNS]
    val apps = familiarApps(runtime, columns + 4)
    val mono = LauncherStyle.monochromeIcons
    val bare = LauncherStyle.bareAppIcons
    PreviewScreen {
        // A row of home's grid: square cells, as most pinned apps are.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HomeGap)) {
            for (i in 0 until columns) {
                val app = apps.getOrNull(i)
                PreviewApp(app?.name(runtime) ?: "app", app?.icons(runtime)?.let { if (mono) it.second else it.first }, Modifier.weight(1f).aspectRatio(1f))
            }
        }
        if (LauncherStyle.recentApps) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .then(if (bare) Modifier else Modifier.tile())
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (app in apps.drop(columns)) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        PreviewIcon(app.name(runtime), app.icons(runtime).let { if (mono) it.second else it.first }, 30.dp, strip = true)
                    }
                }
            }
        }
        if (barOnHome(runtime)) Box(Modifier.fillMaxWidth().height(32.dp).tile())
    }
}

/** A pinned app as ShortcutTile draws it, without what makes it one. */
@Composable
private fun PreviewApp(
    label: String,
    icon: ImageBitmap?,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.tileFrame(focused = false).padding(4.dp)) {
        val showLabel = LauncherStyle.appLabels && showsLabel()
        val iconSize = tileIconSize(showLabel)
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            PreviewIcon(label, icon, iconSize)
            if (showLabel) TileLabel(label)
        }
    }
}

/** An app's icon as home draws it: a silhouette in the accent color when monochrome, on a badge when bare; [strip] icons fill their cell. */
@Composable
private fun PreviewIcon(
    label: String,
    icon: ImageBitmap?,
    size: Dp,
    strip: Boolean = false,
) {
    val mono = LauncherStyle.monochromeIcons
    when {
        LauncherStyle.bareAppIcons && (mono || icon == null) ->
            IconBadge(size) {
                if (icon != null) {
                    Image(icon, null, Modifier.size(size * 0.75f), colorFilter = ColorFilter.tint(Palette.accent))
                } else {
                    BasicText(initials(label), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Body))
                }
            }
        icon != null -> Image(icon, null, Modifier.size(size * if (mono && strip) 0.85f else 1f), colorFilter = if (mono) ColorFilter.tint(Palette.accent) else null)
        else -> BasicText(initials(label), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Large))
    }
}

/**
 * Widgets on home as empty tiles: a wide one over two small ones, the last in use and so
 * outlined as focused, with the gaps between them and to the screen's edges.
 */
@Composable
private fun WidgetPreview() {
    PreviewScreen {
        Box(Modifier.fillMaxWidth().height(40.dp).tile())
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(HomeGap)) {
            Box(Modifier.weight(1f).fillMaxHeight().tile())
            Box(Modifier.weight(1f).fillMaxHeight().tile(focused = true))
        }
    }
}

/**
 * A patch of home for the previews, in the controls' column so it reads as a sketch rather than one of them:
 * home's gaps out to its edges, and [content] stacked home's gaps in apart.
 */
@Composable
private fun PreviewScreen(content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        OptionLabel("preview")
        Column(
            Modifier
                .weight(1f)
                .clip(LauncherStyle.shape(12.dp))
                .background(Palette.surface)
                .padding(EdgeGap),
            verticalArrangement = Arrangement.spacedBy(HomeGap),
            content = content,
        )
    }
}

/** Over a preview, so it isn't mistaken for controls. */
@Composable
private fun PreviewLabel() = BasicText("preview", Modifier.padding(top = 12.dp, bottom = 4.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))

/** A stretch of [PREVIEW] in theme colors (foreground and none without them), maybe bold or italic. */
private class Run(
    val text: String,
    val color: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val background: String? = null,
)

/** A prompt, as the preview's shell shows it. */
private val PROMPT = listOf(Run("~", "green"), Run(" "), Run("❯", "blue"), Run(" "))

/**
 * What the preview shows: ls, some code with the characters fonts tell apart and
 * an italic docstring, git's colors, and a prompt with the theme's block cursor.
 */
private val PREVIEW =
    listOf(
        PROMPT + Run("ls"),
        listOf(Run("Documents", "blue", bold = true), Run("  "), Run("Projects", "blue", bold = true), Run("  notes.md  "), Run("run.sh", "green")),
        PROMPT + Run("cat greet.py"),
        listOf(Run("def", "magenta"), Run(" "), Run("greet", "blue"), Run("(name):  "), Run("# 0O 1lI {} [] <=", "color8")),
        listOf(Run("    "), Run("\"\"\"Say hi, nicely.\"\"\"", "green", italic = true)),
        listOf(Run("    "), Run("return", "magenta"), Run(" "), Run("f\"hi {name}\"", "green")),
        PROMPT + Run("git status -s"),
        listOf(Run(" M", "red"), Run(" "), Run("greet.py", "cyan"), Run("  "), Run("??", "yellow"), Run(" "), Run("todo.txt", "cyan")),
        PROMPT + Run(" ", "background", background = "cursor"),
    )

/** [PREVIEW] wrapped as foot wraps, at [columns] characters rather than between words. */
private fun previewText(columns: Int) =
    buildAnnotatedString {
        PREVIEW.forEachIndexed { i, line ->
            if (i > 0) append('\n')
            var column = 0
            for (run in line) {
                val style =
                    SpanStyle(
                        color = run.color?.let(Palette::named) ?: Color.Unspecified,
                        fontWeight = if (run.bold) FontWeight.Bold else null,
                        fontStyle = if (run.italic) FontStyle.Italic else null,
                        background = run.background?.let(Palette::named) ?: Color.Unspecified,
                    )
                withStyle(style) {
                    for (c in run.text) {
                        if (column == columns) {
                            append('\n')
                            column = 0
                        }
                        append(c)
                        column++
                    }
                }
            }
        }
    }

/** A section's title, set apart from the muted labels of the options under it by the accent color, as the weather screen sets its sections apart; [actions] go at the far end. */
@Composable
private fun Heading(
    text: String,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(text, Modifier.weight(1f), style = Mono.copy(color = Palette.accent), softWrap = false)
        actions()
    }
}

/** Font names run long and pacman can add many: the picked one shows, and tapping it lists the others under it. */
@Composable
private fun FontOption(
    families: List<String>,
    selected: String,
    label: (String) -> String = { it },
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OptionLabel("font")
        Box(Modifier.weight(1f, fill = false)) {
            Row(
                Modifier
                    .frame(1.dp, Palette.border, radius = 12.dp)
                    .clickable { open = !open }
                    .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(label(selected), Modifier.weight(1f, fill = false), style = Mono.copy(color = Palette.foreground), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Symbol(if (open) Glyph.ChevronUp else Glyph.ChevronDown, Palette.muted, Modifier.padding(start = 6.dp))
            }
            if (open) {
                ChoicePopup(families, selected, BelowAnchorStart, onDismiss = { open = false }, onPick = onSelect, label = label)
            }
        }
    }
}

/** Just under the anchor, lined up with its start, and kept on screen. */
private object BelowAnchorStart : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ) = IntOffset(
        anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        (anchorBounds.bottom + 8).coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)),
    )
}

/** The column option names take, so the controls beside them line up. */
private val LabelWidth = 104.dp

/** An option's name; a long one wraps rather than push its control out of line with the others. */
@Composable
private fun OptionLabel(
    text: String,
    modifier: Modifier = Modifier,
) = BasicText(text, modifier.width(LabelWidth).padding(end = 8.dp), style = Mono.copy(color = Palette.muted))

/** A note under an option, lined up with its control. */
@Composable
private fun OptionHint(
    text: String,
    modifier: Modifier = Modifier,
) = BasicText(text, modifier.padding(start = LabelWidth), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))

/**
 * [Style] numbers stepped together by 1 within their range, from the first one's value; a value set
 * in style.toml between steps snaps to the next one. Reset shows once one is off its default.
 */
@Composable
private fun Stepper(
    label: String,
    style: StyleStore,
    vararg settings: Setting.Number,
    step: Double = 1.0,
    format: (Double) -> String = { Toml.format(it) + " dp" },
) {
    val all = settings.toList()
    val setting = all.first()
    val value = style.style[setting]
    // Snapped to the step, so a value set in style.toml between steps lands back on one.
    fun set(v: Double) = style.update { it.with(all) { s -> (Math.round(v / step) * step).coerceIn(s.range) } }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OptionLabel(label)
        Row(Modifier.frame(1.dp, Palette.border, radius = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val down = value > setting.range.start
            val up = value < setting.range.endInclusive
            BasicText(
                "−",
                Modifier.clickable(enabled = down) { set(ceil(value / step - 1) * step) }.padding(horizontal = 16.dp, vertical = 8.dp),
                style = Mono.copy(color = if (down) Palette.foreground else Palette.muted),
            )
            BasicText(format(value), Modifier.widthIn(min = 64.dp), style = Mono.copy(textAlign = TextAlign.Center), softWrap = false)
            BasicText(
                "+",
                Modifier.clickable(enabled = up) { set(floor(value / step + 1) * step) }.padding(horizontal = 16.dp, vertical = 8.dp),
                style = Mono.copy(color = if (up) Palette.foreground else Palette.muted),
            )
        }
        if (all.any { style.style[it] != it.default }) BracketAction("reset", Palette.muted) { style.update { it.with(all) { s -> s.default } } }
    }
    if (style.style.common(all) == null) OptionHint("they differ in style.toml; a step sets them all", Modifier.padding(bottom = 4.dp))
}

/** A 0–1 [Style] number in 5% steps, shown as a percentage: how much a surface covers the wallpaper. */
@Composable
private fun OpacityStepper(
    label: String,
    style: StyleStore,
    vararg settings: Setting.Number,
) = Stepper(label, style, *settings, step = 0.05, format = { "${Math.round(it * 100)}%" })

/** Style's icon pack by its name, or the apps' own icons; a tap opens the packs to pick from. */
@Composable
private fun IconPackOption(
    runtime: Runtime,
    onOpen: () -> Unit,
) {
    val chosen = runtime.style.style[Style.ICON_PACK]
    val label by produceState<String?>(null, chosen) {
        // Not the last pack's name while this one's is looked up.
        value = null
        value = chosen?.let { pkg -> withContext(Dispatchers.IO) { runtime.iconPacks.installed().firstOrNull { it.packageName == pkg }?.label } ?: pkg }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OptionLabel("icon pack")
        Row(
            Modifier
                .frame(1.dp, Palette.border, radius = 12.dp)
                .clickable(onClick = onOpen)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                if (chosen == null) "each app's own" else label ?: "",
                Modifier.weight(1f, fill = false),
                style = Mono.copy(color = Palette.foreground),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Symbol(Glyph.ChevronRight, Palette.accent, Modifier.padding(start = 8.dp))
        }
    }
}

/** [Style] flags as a choice of two, [on] first, set together. */
@Composable
private fun Flag(
    label: String,
    on: String,
    off: String,
    style: StyleStore,
    vararg settings: Setting.Flag,
) {
    val all = settings.toList()
    val selected =
        when (style.style.common(all)) {
            true -> 0
            false -> 1
            null -> -1
        }
    StyleOption(label, listOf(on, off), selected, "they differ") { i -> style.update { it.with(all) { i == 0 } } }
}

/** A [Style] number as named [presets]; a value set in style.toml between them shows as custom. */
@Composable
private fun NumberOption(
    label: String,
    presets: List<Pair<String, Double>>,
    setting: Setting.Number,
    style: StyleStore,
    unit: String = "",
) {
    val value = style.style[setting]
    StyleOption(label, presets.map { it.first }, presets.indexOfFirst { it.second == value }, Toml.format(value) + unit) { i ->
        style.update { it.with(setting, presets[i].second) }
    }
}

@Composable
private fun CountOption(
    label: String,
    setting: Setting.Count,
    style: StyleStore,
) {
    val counts = setting.range.toList()
    StyleOption(label, counts.map(Int::toString), counts.indexOf(style.style[setting])) { i -> style.update { it.with(setting, counts[i]) } }
}

/**
 * A labeled segmented choice: the picked option fills with the accent color.
 * None is picked for a value set in style.toml outside the options; [custom] says what it is.
 */
@Composable
private fun StyleOption(
    label: String,
    options: List<String>,
    selected: Int,
    custom: String? = null,
    onSelect: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OptionLabel(label)
        // At a large Android font size the options may not fit: they scroll rather than wrap.
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.frame(1.dp, Palette.border, radius = 12.dp)) {
                options.forEachIndexed { i, option ->
                    val active = i == selected
                    BasicText(
                        option,
                        Modifier
                            .background(if (active) Palette.accent else Color.Transparent)
                            .clickable { onSelect(i) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        style = Mono.copy(color = if (active) Palette.background else Palette.foreground),
                        softWrap = false,
                    )
                }
            }
        }
    }
    if (selected < 0 && custom != null) OptionHint("custom: $custom, from style.toml", Modifier.padding(bottom = 4.dp))
}

@Composable
private fun DownloadPrompt(
    state: ThemeDownload,
    /** Some themes are here already (first run fetches only the picked one). */
    partial: Boolean,
    onDownload: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(
            if (partial) {
                "Omarchy's other themes are downloading from github.com/omacom/omarchy (~70 MB)."
            } else {
                "Omarchy's themes aren't installed yet. They're downloaded from github.com/omacom/omarchy (~70 MB)."
            },
            style = Mono,
        )
        when (state) {
            is ThemeDownload.Running -> BasicText("downloading… ${state.megabytes} MB", style = Mono.copy(color = Palette.accent))
            is ThemeDownload.Failed -> {
                BasicText("failed: ${state.message}", style = Mono.copy(color = Palette.red))
                BracketAction("retry", Palette.accent, onDownload)
            }
            ThemeDownload.Idle -> BracketAction("download themes", Palette.accent, onDownload)
        }
    }
}

@Composable
private fun ThemeCard(
    theme: OmarchyTheme,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val bg = theme.colors["background"].toColor()
    Column(
        modifier
            .frame(2.dp, if (selected) Palette.accent else Palette.border, bg, radius = 12.dp)
            .clickable(onClick = onClick),
    ) {
        Thumbnail(theme.preview, selected = false, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f), onClick = onClick)
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(theme.title, Modifier.weight(1f), style = Mono.copy(color = theme.colors["foreground"].toColor(), fontSize = TextSize.Small), maxLines = 1)
            for (key in listOf("accent", "red", "green", "yellow")) {
                Box(Modifier.padding(start = 2.dp).size(8.dp).background(theme.colors[key].toColor()))
            }
        }
    }
}

@Composable
private fun Thumbnail(
    file: File,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val image by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { decodeSampled(file, 480, 0)?.asImageBitmap() }
    }
    Box(
        modifier
            .frame(2.dp, if (selected) Palette.accent else Color.Transparent, Palette.surface, radius = 12.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick =
                    onLongClick?.let {
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            it()
                        }
                    },
            ),
    ) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

private fun String?.toColor(): Color = hexColor(this) ?: Color.Gray
