package dev.omalauncher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Apps hidden from the drawer, search and the recent apps strip, by their [dev.omalauncher.ui.AppHit] key. The
 * drawer's "show hidden" lists them again, dimmed, to be shown again. Tiles already on home stay where they are.
 */
class HiddenApps(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    var keys by mutableStateOf(prefs.getStringSet(HIDDEN, null)?.toSet() ?: emptySet())
        private set

    operator fun contains(key: String) = key in keys

    /** Hides [hide] or shows them again; saved as it's set. */
    fun set(
        hide: Collection<String>,
        hidden: Boolean,
    ) {
        keys = if (hidden) keys + hide else keys - hide.toSet()
        prefs.edit().putStringSet(HIDDEN, keys).apply()
    }

    private companion object {
        const val HIDDEN = "hidden_apps"
    }
}
