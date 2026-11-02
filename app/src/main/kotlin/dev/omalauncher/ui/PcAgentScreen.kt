package dev.omalauncher.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omalauncher.LocalNetwork
import dev.omalauncher.R
import dev.omalauncher.remote.PcAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phone build: settings › PC agent. Turns the launcher's MCP server on or off, says whether widgets from the
 * computer may run commands, and copies what adds the phone to an agent there: per address, Claude Code's command.
 */
@Composable
fun PcAgentScreen(
    pc: PcAgent,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val status by pc.status.collectAsState()
    var enabled by remember { mutableStateOf(pc.enabled) }
    var allow by remember { mutableStateOf(pc.allowCommands) }
    var confirmAllow by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf<String?>(null) }
    // Bumped by a new token: the server comes back on the same port, so the status alone doesn't change.
    var tokenGeneration by remember { mutableIntStateOf(0) }
    var addresses by remember { mutableStateOf(emptyList<PcAgent.Address>()) }
    // Which row was copied last, so it says so.
    var copied by remember { mutableStateOf<String?>(null) }
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

    // Again after each start or new token: the keystore and the network list are read off the main thread.
    LaunchedEffect(status, tokenGeneration) {
        withContext(Dispatchers.IO) {
            token = if (pc.enabled) runCatching { pc.token() }.getOrNull() else null
            // One host per row: the same address can show up on two interfaces.
            addresses = pc.addresses().distinctBy { it.host }
        }
    }

    fun copy(
        key: String,
        text: String,
    ) {
        val clip =
            ClipData.newPlainText("omalauncher", text).apply {
                // Kept out of the clipboard's preview: it holds the token.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                }
            }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        copied = key
    }

    val copiedText = stringResource(R.string.phone_pc_agent_copied)

    @Composable
    fun NewToken() =
        PcRow(stringResource(R.string.phone_pc_agent_new_token), stringResource(R.string.phone_pc_agent_new_token_subtitle)) {
            copied = null
            token = null
            pc.newToken()
            tokenGeneration++
        }

    OverlayPanel(stringResource(R.string.phone_pc_agent_title), onDismiss, contentWidth = ReadingWidth) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "about") {
                BasicText(stringResource(R.string.phone_pc_agent_about), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
            }
            if (localNetworkMissing) {
                item(key = "local") {
                    PcRow(stringResource(R.string.phone_pc_agent_local_network), stringResource(R.string.phone_pc_agent_local_network_subtitle)) {
                        askLocalNetwork.launch(LocalNetwork.PERMISSION)
                    }
                }
            }
            item(key = "access") {
                val subtitle =
                    when (val s = status) {
                        is PcAgent.Status.Listening -> stringResource(R.string.phone_pc_agent_access_on, s.port)
                        is PcAgent.Status.Failed -> stringResource(R.string.phone_pc_agent_access_failed, s.port, s.error)
                        PcAgent.Status.NoToken -> stringResource(R.string.phone_pc_agent_access_no_token)
                        PcAgent.Status.Off -> stringResource(R.string.phone_pc_agent_access_off)
                    }
                PcRow(stringResource(R.string.phone_pc_agent_access), subtitle, error = status is PcAgent.Status.Failed || status == PcAgent.Status.NoToken) {
                    enabled = !enabled
                    pc.enabled = enabled
                }
            }
            if (enabled) {
                item(key = "commands") {
                    PcRow(
                        stringResource(R.string.phone_pc_agent_commands),
                        stringResource(if (allow) R.string.phone_pc_agent_commands_on else R.string.phone_pc_agent_commands_off),
                    ) {
                        if (allow) {
                            allow = false
                            pc.allowCommands = false
                        } else {
                            confirmAllow = true
                        }
                    }
                }
                val t = token
                if (status is PcAgent.Status.Listening && t != null) {
                    item(key = "add") { SectionLabel(stringResource(R.string.phone_pc_agent_add)) }
                    if (addresses.isEmpty()) {
                        item(key = "none") {
                            BasicText(stringResource(R.string.phone_pc_agent_no_address), Modifier.padding(4.dp), style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                        }
                    }
                    items(addresses, key = { "address:" + it.host }) { a ->
                        val title =
                            when (a.network) {
                                PcAgent.Network.Tailscale -> stringResource(R.string.phone_pc_agent_tailscale, a.host)
                                PcAgent.Network.WiFi -> stringResource(R.string.phone_pc_agent_wifi, a.host)
                                PcAgent.Network.Local -> stringResource(R.string.phone_pc_agent_local, a.host)
                            }
                        val key = "address:" + a.host
                        PcRow(title, if (copied == key) copiedText else stringResource(R.string.phone_pc_agent_copy_command)) { copy(key, pc.claudeCommand(a.host, t)) }
                    }
                    addresses.firstOrNull()?.let { a ->
                        item(key = "other") {
                            PcRow(stringResource(R.string.phone_pc_agent_other), if (copied == "other") copiedText else stringResource(R.string.phone_pc_agent_other_subtitle)) {
                                copy("other", pc.jsonConfig(a.host, t))
                            }
                        }
                    }
                    item(key = "token") {
                        PcRow(stringResource(R.string.phone_pc_agent_token), if (copied == "token") copiedText else stringResource(R.string.phone_pc_agent_token_subtitle)) {
                            copy("token", t)
                        }
                    }
                    item(key = "new") { NewToken() }
                }
                // A token the keystore can't open any more is replaced only when asked.
                if (status == PcAgent.Status.NoToken) item(key = "new") { NewToken() }
            }
        }
    }

    if (confirmAllow) {
        ConfirmDialog(
            title = stringResource(R.string.phone_pc_agent_commands_confirm_title),
            message = stringResource(R.string.phone_pc_agent_commands_confirm_message),
            confirm = stringResource(R.string.phone_pc_agent_commands_confirm),
            onDismiss = { confirmAllow = false },
        ) {
            confirmAllow = false
            allow = true
            pc.allowCommands = true
        }
    }
}

@Composable
private fun PcRow(
    title: String,
    subtitle: String,
    error: Boolean = false,
    onClick: () -> Unit,
) {
    Row(Modifier.card(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = Mono.copy(fontSize = TextSize.Item))
            BasicText(subtitle, style = Mono.copy(color = if (error) Palette.red else Palette.muted, fontSize = TextSize.Small), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Symbol(Glyph.ChevronRight, Palette.accent, fontSize = TextSize.Title)
    }
}
