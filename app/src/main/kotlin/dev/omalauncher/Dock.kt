package dev.omalauncher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** How many apps the dock holds. */
const val DOCK_SLOTS = 5

/**
 * The apps in the dock above the prompt bar, left to right, by their [dev.omalauncher.ui.AppHit] key. An app
 * that's uninstalled keeps its place and comes back to it if it returns, as Linux apps do once they've loaded.
 */
class Dock(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    // One key a line: no key holds a line break.
    var keys by mutableStateOf(prefs.getString(DOCK, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty())
        private set

    operator fun contains(key: String) = key in keys

    val full get() = keys.size >= DOCK_SLOTS

    /** Adds [add] after the apps already there, as many as there's room for. */
    fun add(add: List<String>) = save(docked(keys, add))

    fun remove(key: String) = save(keys - key)

    /** Puts [key] at slot [at], moving it there if it's in already; nothing when it isn't and the dock is full. */
    fun insert(
        key: String,
        at: Int,
    ) = save(inserted(keys, key, at))

    /** Moves [key] [by] places, negative to the left. */
    fun move(
        key: String,
        by: Int,
    ) = save(moved(keys, key, by))

    private fun save(new: List<String>) {
        if (new == keys) return
        keys = new
        prefs.edit().putString(DOCK, new.joinToString("\n")).apply()
    }

    private companion object {
        const val DOCK = "dock"
    }
}

/** [current] with [add] after it, skipping ones already in, up to [slots]. */
internal fun docked(
    current: List<String>,
    add: List<String>,
    slots: Int = DOCK_SLOTS,
) = (current + add.filter { it !in current }.distinct()).take(slots)

/** [current] with [key] moved [by] places, kept within the ends; unchanged when [key] isn't in it. */
internal fun moved(
    current: List<String>,
    key: String,
    by: Int,
): List<String> {
    val from = current.indexOf(key)
    if (from < 0) return current
    val to = (from + by).coerceIn(0, current.lastIndex)
    return current.toMutableList().apply { add(to, removeAt(from)) }
}

/** [current] with [key] at slot [at] (moved there if it's in), or unchanged when it isn't and [slots] are full. */
internal fun inserted(
    current: List<String>,
    key: String,
    at: Int,
    slots: Int = DOCK_SLOTS,
): List<String> {
    if (key !in current && current.size >= slots) return current
    val rest = current - key
    return rest.toMutableList().apply { add(at.coerceIn(0, rest.size), key) }
}

/** The dock's key for a home tile's [homeKey] (app:, linux:, shortcut:), or null for anything else (a folder, a widget). */
fun dockKeyOf(homeKey: String): String? =
    when {
        homeKey.startsWith("app:") -> "a:" + homeKey.removePrefix("app:")
        homeKey.startsWith("linux:") -> "l:" + homeKey.removePrefix("linux:")
        homeKey.startsWith("shortcut:") -> "s:" + homeKey.removePrefix("shortcut:")
        else -> null
    }

