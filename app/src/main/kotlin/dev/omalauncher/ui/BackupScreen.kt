package dev.omalauncher.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.LocalNetwork
import dev.omalauncher.R
import dev.omalauncher.backup.Backups
import dev.omalauncher.backup.FeatherBackups
import kotlinx.coroutines.launch

/**
 * Phone build: settings kept on Feather. Where (server, token, this phone's name), backing up now or daily, and
 * restoring any of this launcher's backups there: this phone's, an earlier install's, another phone's.
 */
@Composable
fun BackupScreen(
    backups: Backups,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy by backups.busy.collectAsState()
    val last by backups.last.collectAsState()
    var server by remember { mutableStateOf(backups.server) }
    var label by remember { mutableStateOf(backups.label) }
    var hasToken by remember { mutableStateOf(backups.hasToken) }
    var tokenError by remember { mutableStateOf<String?>(null) }
    val unsafe by backups.unsafe.collectAsState()
    var daily by remember { mutableStateOf(backups.daily) }
    var editing by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf<List<FeatherBackups.Device>?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<String?>(null) }
    var snapshots by remember { mutableStateOf<Map<String, List<FeatherBackups.Snapshot>>>(emptyMap()) }
    var confirm by remember { mutableStateOf<Pair<FeatherBackups.Device, FeatherBackups.Snapshot>?>(null) }
    var localNetworkMissing by remember { mutableStateOf(LocalNetwork.missing(context)) }
    val askLocalNetwork =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            localNetworkMissing = !granted
            if (!granted && (context as? Activity)?.shouldShowRequestPermissionRationale(LocalNetwork.PERMISSION) == false) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

    fun refresh() {
        scope.launch {
            listError = null
            devices = null
            snapshots = emptyMap()
            devices =
                try {
                    backups.devices().sortedWith(compareByDescending<FeatherBackups.Device> { it.id == backups.deviceId }.thenByDescending { it.lastBackup })
                } catch (e: Exception) {
                    listError = Backups.reason(e)
                    emptyList()
                }
        }
    }

    fun open(device: FeatherBackups.Device) {
        opened = if (opened == device.id) null else device.id
        if (opened == null || device.id in snapshots) return
        scope.launch {
            snapshots = snapshots + (device.id to runCatching { backups.snapshots(device.id) }.getOrDefault(emptyList()))
        }
    }

    // A "restore anyway?" left from before belongs to that visit, not the next one.
    DisposableEffect(Unit) {
        backups.dismissUnsafe()
        onDispose { backups.dismissUnsafe() }
    }

    // Again after each backup, so this phone's new one shows.
    LaunchedEffect(hasToken, server, last?.at) { if (hasToken) refresh() }

    OverlayPanel(stringResource(R.string.phone_backup_title), onDismiss, contentWidth = ReadingWidth) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "about") {
                BasicText(
                    stringResource(R.string.phone_backup_about),
                    style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                )
            }
            if (localNetworkMissing) {
                item(key = "local") {
                    SettingRow(stringResource(R.string.phone_backup_local_network), stringResource(R.string.phone_backup_local_network_subtitle)) { askLocalNetwork.launch(LocalNetwork.PERMISSION) }
                }
            }
            item(key = "server") { SettingRow(stringResource(R.string.phone_backup_server), server) { editing = "server" } }
            item(key = "token") {
                SettingRow(stringResource(R.string.phone_backup_token), tokenError ?: stringResource(if (hasToken) R.string.phone_backup_token_saved else R.string.phone_backup_token_missing)) { editing = "token" }
            }
            if (hasToken) {
                item(key = "forget") {
                    SettingRow(stringResource(R.string.phone_backup_forget), stringResource(R.string.phone_backup_forget_subtitle)) {
                        backups.setToken("")
                        hasToken = backups.hasToken
                        tokenError = null
                    }
                }
            }
            item(key = "label") { SettingRow(stringResource(R.string.phone_backup_this_phone), label) { editing = "label" } }
            item(key = "daily") {
                SettingRow(stringResource(R.string.phone_backup_daily), stringResource(if (daily) R.string.phone_backup_daily_on else R.string.phone_backup_daily_off)) {
                    daily = !daily
                    backups.daily = daily
                }
            }
            item(key = "now") {
                Row(Modifier.card(), verticalAlignment = Alignment.CenterVertically) {
                    val status = busy ?: last?.let { "${it.message} · ${ago(it.at)}" } ?: stringResource(R.string.phone_backup_not_yet)
                    BasicText(
                        status,
                        Modifier.weight(1f),
                        style = Mono.copy(color = if (busy == null && last?.ok == false) Palette.red else Palette.muted, fontSize = TextSize.Small),
                    )
                    if (busy == null && hasToken) {
                        BracketAction(stringResource(R.string.phone_backup_now), Palette.accent) { backups.scope.launch { backups.backUp() } }
                    }
                }
            }
            if (hasToken) {
                item(key = "restore-label") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel(stringResource(R.string.phone_backup_restore), Modifier.weight(1f))
                        BracketAction(stringResource(R.string.phone_backup_refresh), Palette.muted, onClick = ::refresh)
                    }
                }
                val list = devices
                when {
                    list == null -> item(key = "loading") { Muted(stringResource(R.string.phone_backup_loading)) }
                    listError != null -> item(key = "error") { Muted(listError.orEmpty(), Palette.red) }
                    list.isEmpty() -> item(key = "none") { Muted(stringResource(R.string.phone_backup_none)) }
                    else ->
                        items(list, key = { "device:" + it.id }) { d ->
                            Column(Modifier.card(onClick = { open(d) })) {
                                val mine = d.id == backups.deviceId
                                BasicText(d.label + if (mine) stringResource(R.string.phone_backup_this_phone_suffix) else "", style = Mono.copy(fontSize = TextSize.Item))
                                BasicText(
                                    pluralStringResource(
                                        R.plurals.phone_backup_device,
                                        d.snapshots,
                                        d.snapshots,
                                        mb(d.size),
                                        d.lastBackup?.let(::local) ?: stringResource(R.string.phone_backup_never),
                                    ),
                                    style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small),
                                )
                                if (opened == d.id) {
                                    val shots = snapshots[d.id]
                                    if (shots == null) Muted(stringResource(R.string.phone_backup_loading))
                                    for (s in shots.orEmpty()) {
                                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            BasicText("${local(s.created)} · ${mb(s.size)}", Modifier.weight(1f), style = Mono.copy(fontSize = TextSize.Small))
                                            if (busy == null) BracketAction(stringResource(R.string.phone_backup_restore), Palette.accent) { confirm = d to s }
                                        }
                                    }
                                }
                            }
                        }
                }
            }
        }
    }

    when (editing) {
        "server" ->
            TextDialog(stringResource(R.string.phone_backup_server_title), server, stringResource(R.string.phone_backup_save), hint = stringResource(R.string.phone_backup_server_hint), onDismiss = { editing = null }) {
                backups.server = it
                server = backups.server
                editing = null
            }
        "token" ->
            TextDialog(
                stringResource(R.string.phone_backup_token_title),
                "",
                stringResource(R.string.phone_backup_save),
                hint = stringResource(R.string.phone_backup_token_hint, context.packageName),
                onDismiss = { editing = null },
            ) {
                tokenError = runCatching { backups.setToken(it) }.exceptionOrNull()?.let { e -> context.getString(R.string.phone_backup_token_failed, e.message) }
                hasToken = backups.hasToken
                editing = null
            }
        "label" ->
            TextDialog(stringResource(R.string.phone_backup_label_title), label, stringResource(R.string.phone_backup_save), hint = stringResource(R.string.phone_backup_label_hint), onDismiss = { editing = null }) {
                backups.label = it
                label = backups.label
                editing = null
            }
    }

    unsafe?.let { (device, snapshot) ->
        ConfirmDialog(
            title = stringResource(R.string.phone_backup_unsafe_title),
            message = stringResource(R.string.phone_backup_unsafe_message, last?.message.orEmpty(), device.label, local(snapshot.created)),
            confirm = stringResource(R.string.phone_backup_unsafe_confirm),
            onDismiss = backups::dismissUnsafe,
        ) {
            backups.dismissUnsafe()
            backups.scope.launch { backups.restore(device, snapshot, safetyCopy = false) }
        }
    }

    confirm?.let { (device, snapshot) ->
        ConfirmDialog(
            title = stringResource(R.string.phone_backup_restore_title, device.label),
            message = stringResource(R.string.phone_backup_restore_message, local(snapshot.created)),
            confirm = stringResource(R.string.phone_backup_restore),
            onDismiss = { confirm = null },
        ) {
            confirm = null
            // Not this screen's scope: closing it mustn't stop a restore half-way.
            backups.scope.launch { backups.restore(device, snapshot) }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(Modifier.card(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = Mono.copy(fontSize = TextSize.Item))
            BasicText(subtitle, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Symbol(Glyph.ChevronRight, Palette.accent, fontSize = TextSize.Title)
    }
}

@Composable
private fun Muted(
    text: String,
    color: androidx.compose.ui.graphics.Color = Palette.muted,
) = BasicText(text, Modifier.padding(4.dp), style = Mono.copy(color = color, fontSize = TextSize.Small))

private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1048576.0)

private fun local(iso: String) = Backups.localTime(iso)

@Composable
private fun ago(millis: Long): String {
    val minutes = (System.currentTimeMillis() - millis) / 60_000
    return when {
        minutes < 1 -> stringResource(R.string.phone_backup_just_now)
        minutes < 60 -> stringResource(R.string.phone_backup_minutes_ago, minutes)
        minutes < 48 * 60 -> stringResource(R.string.phone_backup_hours_ago, minutes / 60)
        else -> stringResource(R.string.phone_backup_days_ago, minutes / (24 * 60))
    }
}
