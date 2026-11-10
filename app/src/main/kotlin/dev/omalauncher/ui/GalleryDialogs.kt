package dev.omalauncher.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.omalauncher.R
import dev.omalauncher.widgets.Gallery

/**
 * Phone build: the gallery's GitHub token, pasted once; blank forgets it. [onSaved] follows a token saved, not one
 * forgotten. It's sealed on the phone, so a failure to keep it shows as a toast.
 */
@Composable
internal fun GalleryTokenDialog(
    gallery: Gallery,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    TextDialog(
        stringResource(R.string.phone_gallery_token_title),
        "",
        stringResource(R.string.phone_backup_save),
        hint = stringResource(R.string.phone_gallery_token_hint, gallery.repo),
        onDismiss = onDismiss,
    ) {
        val failed = runCatching { gallery.setToken(it) }.exceptionOrNull()
        when {
            failed != null -> {
                Toast.makeText(context, context.getString(R.string.phone_backup_token_failed, failed.message), Toast.LENGTH_LONG).show()
                onDismiss()
            }
            gallery.hasToken -> onSaved()
            else -> onDismiss()
        }
    }
}
