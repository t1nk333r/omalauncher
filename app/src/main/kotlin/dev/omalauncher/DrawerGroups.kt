package dev.omalauncher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** A group of apps in the drawer: its [name] and its apps' [dev.omalauncher.ui.AppHit] keys, in the order added. */
data class DrawerGroup(
    val name: String,
    val keys: List<String>,
)

/**
 * The drawer's groups, your own sections above its lists (games, work, tools). An app is in one group at most and is
 * listed there instead of under its usual heading; a group whose apps are gone stays until it's deleted.
 */
class DrawerGroups(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    var groups by mutableStateOf(read(prefs.getString(GROUPS, null)))
        private set

    /** The group [key]'s app is in, or null. */
    fun groupOf(key: String) = groups.firstOrNull { key in it.keys }?.name

    /** Puts the apps [keys] in the group [name], made if it's new, taking them out of any other. */
    fun put(
        keys: Collection<String>,
        name: String,
    ) = save(keys.fold(groups) { g, key -> withApp(g, key, name) })

    /** Takes the apps [keys] out of their groups. */
    fun ungroup(keys: Collection<String>) = save(keys.fold(groups) { g, key -> withoutApp(g, key) })

    fun rename(
        from: String,
        to: String,
    ) = save(renamed(groups, from, to))

    /** Deletes a group; its apps go back under their usual headings. */
    fun delete(name: String) = save(groups.filter { it.name != name })

    private fun save(new: List<DrawerGroup>) {
        if (new == groups) return
        groups = new
        prefs.edit().putString(GROUPS, write(new)).apply()
    }

    private companion object {
        const val GROUPS = "drawer_groups"
    }
}

/**
 * [groups] with [key] in the group [name] (made after the others if new) and out of any other. A name in another case
 * is the same group, as the drawer's pages and pickers have it.
 */
internal fun withApp(
    groups: List<DrawerGroup>,
    key: String,
    name: String,
): List<DrawerGroup> {
    val rest = withoutApp(groups, key)
    val same = rest.indexOfFirst { it.name.equals(name, ignoreCase = true) }
    return if (same >= 0) {
        rest.mapIndexed { i, g -> if (i == same) g.copy(keys = g.keys + key) else g }
    } else {
        rest + DrawerGroup(name, listOf(key))
    }
}

internal fun withoutApp(
    groups: List<DrawerGroup>,
    key: String,
) = groups.map { if (key in it.keys) it.copy(keys = it.keys - key) else it }

/** [groups] with [from] named [to]; unchanged when [to] is blank or another group already has that name, in any case. */
internal fun renamed(
    groups: List<DrawerGroup>,
    from: String,
    to: String,
): List<DrawerGroup> {
    if (to.isBlank() || groups.any { it.name.equals(to, ignoreCase = true) && it.name != from }) return groups
    return groups.map { if (it.name == from) it.copy(name = to) else it }
}

/** The saved groups; ones whose names differ only in case, saved apart before [withApp] matched them, as the first. */
internal fun read(json: String?): List<DrawerGroup> =
    runCatching {
        val array = JSONArray(json ?: return emptyList())
        (0 until array.length())
            .map { i ->
                val o = array.getJSONObject(i)
                val keys = o.getJSONArray("keys")
                DrawerGroup(o.getString("name"), (0 until keys.length()).map(keys::getString))
            }.groupBy { it.name.lowercase() }
            .values
            .map { same -> same.first().copy(keys = same.flatMap { it.keys }) }
    }.getOrDefault(emptyList())

internal fun write(groups: List<DrawerGroup>): String =
    JSONArray(groups.map { g -> JSONObject().put("name", g.name).put("keys", JSONArray(g.keys)) }).toString()
