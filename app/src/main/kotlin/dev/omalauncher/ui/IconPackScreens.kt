package dev.omalauncher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.omalauncher.IconPack
import dev.omalauncher.IconPackStore
import dev.omalauncher.Runtime
import dev.omalauncher.searchPackIcons
import dev.omalauncher.suggestPackIcons
import dev.omalauncher.theme.Style
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Style's icon pack, as other launchers list them: each pack on the phone with its own icon, and the apps' own
 * icons; a tap picks one, and the apps are drawn again with it. Packs are installed in their own apps.
 */
@Composable
fun IconPackScreen(
    runtime: Runtime,
    onDismiss: () -> Unit,
) {
    val packs by installedPacks(runtime)
    val chosen = runtime.style.style[Style.ICON_PACK]
    val pick = { pack: String? -> runtime.style.update { it.with(Style.ICON_PACK, pack) } }
    OverlayPanel("icon pack", onDismiss, contentWidth = ReadingWidth) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { PackRow(null, "each app's own", "the icons apps come with", chosen == null) { pick(null) } }
            // Picked in style.toml, or since uninstalled: shown, so it's clear why the apps have their own icons.
            if (chosen != null && packs?.none { it.packageName == chosen } == true) {
                item { PackRow(null, chosen, "not on the phone · apps show their own icons", picked = true) {} }
            }
            items(packs.orEmpty(), key = { it.packageName }) { pack ->
                PackRow(pack.packageName, pack.label, pack.packageName, chosen == pack.packageName) { pick(pack.packageName) }
            }
            item {
                val none = packs?.isEmpty() == true
                BasicText(
                    (if (none) "no icon packs on the phone; packs made for Nova, ADW or Lawnchair work here. " else "") +
                        "An app's customize can take any one of a pack's icons, and that one stays.",
                    Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                    style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                )
            }
        }
    }
}

/**
 * One of an icon pack's icons for [hit], from its customize: the packs on the phone (straight to the only one),
 * then a pack's icons, the ones meant for the app first, searchable by the apps they stand for or by name. [onPick]
 * gets the one tapped.
 */
@Composable
internal fun PackIconDialog(
    runtime: Runtime,
    hit: AppHit,
    onDismiss: () -> Unit,
    onPick: (Drawable) -> Unit,
) {
    val packs by installedPacks(runtime)
    var opened by remember { mutableStateOf<IconPackStore.Installed?>(null) }
    val only = packs?.singleOrNull()
    val pack = opened ?: only
    // Back goes from a pack's icons to the packs, as the panel's back chevron does.
    val back = if (opened != null && only == null) ({ opened = null }) else null
    Dialog(onDismissRequest = back ?: onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        OverlayPanel(
            pack?.label?.lowercase() ?: "icon pack",
            onDismiss,
            contentWidth = ReadingWidth,
            onBack = back,
        ) {
            val list = packs
            when {
                list == null -> {}
                pack != null -> PackIcons(runtime, pack.packageName, hit, onPick)
                list.isEmpty() ->
                    BasicText(
                        "no icon packs on the phone; packs made for Nova, ADW or Lawnchair work here",
                        Modifier.padding(4.dp),
                        style = Mono.copy(color = Palette.muted),
                    )
                else ->
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(list, key = { it.packageName }) { p -> PackRow(p.packageName, p.label, p.packageName, picked = false) { opened = p } }
                    }
            }
        }
    }
}

/** A pack's icons in a grid under a search field; with the search empty, the ones meant for [hit] come first. */
@Composable
private fun PackIcons(
    runtime: Runtime,
    packageName: String,
    hit: AppHit,
    onPick: (Drawable) -> Unit,
) {
    var unreadable by remember(packageName) { mutableStateOf(false) }
    val pack by produceState<IconPack?>(null, packageName) {
        // Reading the list is the slow part, a big pack's thousands of names: done here, off the main thread.
        value = withContext(Dispatchers.IO) { runtime.iconPacks.open(packageName)?.also { it.icons } }
        unreadable = value == null
    }
    val p = pack
    if (p == null) {
        BasicText(if (unreadable) "this pack's icons couldn't be read" else "reading the pack…", Modifier.padding(4.dp), style = Mono.copy(color = Palette.muted))
        return
    }
    var query by remember(packageName) { mutableStateOf("") }
    val component = (hit as? AppHit.Android)?.app?.component
    // The phone's apps' names, by the icon the pack draws each with: "signal" finds Signal's icon whatever its
    // drawable is called. With the apps each icon stands for, what a search or a suggestion matches beside its name.
    val names = remember(p) { runtime.androidApps.apps.mapNotNull { app -> p.drawableFor(app.component)?.let { it to app.label } }.groupBy({ it.first }, { it.second }) }
    val about = remember(p, names) { { name: String -> (p.appsOf(name) + names[name].orEmpty()).joinToString(" ") } }
    // Tens of thousands in a big pack: matched off the main thread, the last result shown until the next is ready.
    val suggested by produceState(emptyList(), p) {
        value = withContext(Dispatchers.Default) { suggestPackIcons(p.icons, hit.label, component?.let(p::drawableFor), about = about) }
    }
    val shown by produceState(p.icons, p, query) { value = withContext(Dispatchers.Default) { searchPackIcons(p.icons, query, about) } }
    // Drawn icons, so scrolling back doesn't draw them again; by their bytes, which a dense screen makes many.
    val cache =
        remember(p) {
            object : LruCache<String, ImageBitmap>(8 shl 20) {
                override fun sizeOf(
                    key: String,
                    value: ImageBitmap,
                ) = value.width * value.height * 4
            }
        }
    SearchField(query, { query = it }, onGo = {}, placeholder = "search ${p.icons.size} icons by app")
    Spacer(Modifier.height(8.dp))
    if (shown.isEmpty()) BasicText(if (p.icons.isEmpty()) "this pack lists no icons" else "no icon for that", Modifier.padding(4.dp), style = Mono.copy(color = Palette.muted))
    LazyVerticalGrid(GridCells.Adaptive(64.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (query.isBlank() && suggested.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("for ${hit.label.lowercase()}") }
            items(suggested, key = { "for:$it" }) { PackIconCell(p, it, cache, onPick) }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("all") }
        }
        items(shown, key = { "all:$it" }) { PackIconCell(p, it, cache, onPick) }
    }
}

/** One of [pack]'s icons, [name], drawn off the main thread; a tap gives it to [onPick]. */
@Composable
private fun PackIconCell(
    pack: IconPack,
    name: String,
    cache: LruCache<String, ImageBitmap>,
    onPick: (Drawable) -> Unit,
) {
    val px = with(LocalDensity.current) { CellIcon.roundToPx() }
    val icon by produceState(cache.get(name), pack, name) {
        if (value == null) value = withContext(Dispatchers.IO) { pack.drawable(name)?.let { runCatching { bitmapOf(it, px) }.getOrNull() } }?.also { cache.put(name, it) }
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(LauncherStyle.shape(12.dp))
            .clickable { pack.drawable(name)?.let(onPick) },
        contentAlignment = Alignment.Center,
    ) {
        icon?.let { Image(it, name.replace('_', ' '), Modifier.size(CellIcon)) }
    }
}

/** A pack, or the apps' own icons for a null [packageName], with its app's icon; [picked] marks the one in use. */
@Composable
private fun PackRow(
    packageName: String?,
    label: String,
    subtitle: String,
    picked: Boolean,
    onClick: () -> Unit,
) {
    Row(Modifier.card(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        val icon = packageName?.let { appIcon(it, RowIcon) }
        if (icon != null) Image(icon, null, Modifier.size(RowIcon)) else IconBadge(RowIcon, Palette.background) {}
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            BasicText(label, style = Mono.copy(color = if (picked) Palette.accent else Palette.foreground, fontSize = TextSize.Item))
            BasicText(subtitle, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), maxLines = 1)
        }
        if (picked) BasicText("●", style = Mono.copy(color = Palette.accent))
    }
}

/** The icon packs on the phone, listed off the main thread; null until they are. */
@Composable
private fun installedPacks(runtime: Runtime): State<List<IconPackStore.Installed>?> =
    produceState(null) { value = withContext(Dispatchers.IO) { runtime.iconPacks.installed() } }

/** [packageName]'s app icon at [size]; null until it's drawn, or if it can't be. */
@Composable
private fun appIcon(
    packageName: String,
    size: Dp,
): ImageBitmap? {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) { runCatching { bitmapOf(context.packageManager.getApplicationIcon(packageName), px) }.getOrNull() }
    }
    return icon
}

private fun bitmapOf(
    drawable: Drawable,
    px: Int,
): ImageBitmap =
    Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { bmp ->
        drawable.setBounds(0, 0, px, px)
        drawable.draw(Canvas(bmp))
    }.asImageBitmap()

private val RowIcon = 40.dp
private val CellIcon = 44.dp
