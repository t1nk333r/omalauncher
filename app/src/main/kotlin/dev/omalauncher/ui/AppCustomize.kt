package dev.omalauncher.ui

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import dev.omalauncher.Runtime
import kotlinx.coroutines.launch

/**
 * An app's own look and place, from its long-press menu: your name for it (blank for its own), your icon (an image
 * from the phone), and its drawer group. Names and icons show in the drawer, on home and in the dock.
 */
@Composable
internal fun AppCustomizeDialog(
    runtime: Runtime,
    hit: AppHit,
    onDismiss: () -> Unit,
) {
    val customs = runtime.customs
    val groups = runtime.drawerGroups
    var name by remember(hit.key) { mutableStateOf(customs.name(hit.key).orEmpty()) }
    var group by remember(hit.key) { mutableStateOf(groups.groupOf(hit.key)) }
    var naming by remember { mutableStateOf(false) }
    var unreadable by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Set at once, unlike the name and group: the picker is a trip out of the dialog.
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) scope.launch { unreadable = !customs.setIcon(hit.key, uri) }
        }
    DialogFrame(onDismiss) {
        BasicText("customize", style = Mono.copy(fontSize = TextSize.Title))
        BasicText(hit.label, style = Mono.copy(color = Palette.muted))
        FieldLabel("name")
        BasicTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            textStyle = Mono.copy(fontSize = TextSize.Large),
            cursorBrush = SolidColor(Palette.accent),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(LauncherStyle.shape(12.dp))
                    .background(Palette.background)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Box {
                    if (name.isEmpty()) BasicText(hit.label, style = Mono.copy(fontSize = TextSize.Large, color = Palette.muted))
                    inner()
                }
            },
        )
        FieldLabel("icon")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val (icon, _) = hit.icons(runtime)
            if (icon != null) Image(icon, null, Modifier.size(44.dp))
            BracketAction("pick image", Palette.accent) {
                unreadable = false
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            if (hit.key in customs.icons) BracketAction("reset", Palette.muted) { customs.resetIcon(hit.key) }
        }
        if (unreadable) BasicText("that image couldn't be read", style = Mono.copy(color = Palette.red, fontSize = TextSize.Small))
        // Groups are the personal tab's: a work app stays on the work tab.
        if (!hit.isWork) {
            FieldLabel("drawer group")
            GroupChips(groups.groups.map { it.name }, group, onPick = { group = it }, onNew = { naming = true })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            BracketAction("cancel", Palette.muted, onDismiss)
            BracketAction("save", Palette.accent) {
                customs.rename(hit.key, name)
                if (!hit.isWork) group?.let { groups.put(listOf(hit.key), it) } ?: groups.ungroup(listOf(hit.key))
                onDismiss()
            }
        }
    }
    if (naming) TextDialog("new group", "", "add", onDismiss = { naming = false }) { new -> group = new; naming = false }
}

/** Puts the picked apps [keys] in a drawer group, from the select bar; a tap applies it. */
@Composable
internal fun GroupPickDialog(
    runtime: Runtime,
    keys: Set<String>,
    onDismiss: () -> Unit,
) {
    val groups = runtime.drawerGroups
    var naming by remember { mutableStateOf(false) }
    val apply = { name: String? ->
        name?.let { groups.put(keys, it) } ?: groups.ungroup(keys)
        onDismiss()
    }
    DialogFrame(onDismiss) {
        BasicText("drawer group", style = Mono.copy(fontSize = TextSize.Title))
        BasicText("${keys.size} picked", style = Mono.copy(color = Palette.muted))
        GroupChips(groups.groups.map { it.name }, chosen = null, onPick = apply, onNew = { naming = true }, noneLabel = "no group")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { BracketAction("cancel", Palette.muted, onDismiss) }
    }
    if (naming) TextDialog("new group", "", "add", onDismiss = { naming = false }) { new -> apply(new) }
}

/** The drawer's groups to pick from, [chosen] marked, with one to leave a group and one to make a new one. */
@Composable
private fun GroupChips(
    names: List<String>,
    chosen: String?,
    onPick: (String?) -> Unit,
    onNew: () -> Unit,
    noneLabel: String = "none",
) {
    // A new name just typed shows among them before it's saved.
    val shown = if (chosen != null && chosen !in names) names + chosen else names
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(noneLabel, chosen == null) { onPick(null) }
        for (n in shown) Chip(n, n == chosen) { onPick(n) }
        Chip("+ new", false, onNew)
    }
}

@Composable
private fun Chip(
    text: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    BasicText(
        text,
        Modifier
            .clip(LauncherStyle.shape(16.dp))
            .background(if (on) Palette.accent else Palette.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = Mono.copy(color = if (on) Palette.background else Palette.foreground),
    )
}

@Composable
private fun FieldLabel(text: String) = BasicText(text, style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))

/**
 * Asks for the fingerprint, face or screen lock before [onUnlocked], as showing hidden apps does with style's
 * drawer.lock-hidden. A phone with no lock set has nothing to ask for, so it goes ahead.
 */
internal fun unlock(
    context: Context,
    title: String,
    onUnlocked: () -> Unit,
) {
    if (context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) return onUnlocked()
    val builder = BiometricPrompt.Builder(context).setTitle(title)
    if (Build.VERSION.SDK_INT >= 30) {
        builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
    } else {
        @Suppress("DEPRECATION")
        builder.setDeviceCredentialAllowed(true)
    }
    builder.build().authenticate(
        CancellationSignal(),
        context.mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onUnlocked()
        },
    )
}
