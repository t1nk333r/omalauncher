package dev.omalauncher.ui

import android.content.pm.ApplicationInfo

/** When an app was first installed, in millis; 0 for Linux apps and shortcuts, which don't say. */
internal val AppHit.installed: Long get() = (this as? AppHit.Android)?.app?.installed ?: 0L

/** How long after it's installed an app not opened since counts as new. */
internal const val NEW_FOR_MS = 3L * 24 * 60 * 60 * 1000

/** Installed within [NEW_FOR_MS] of [now] and never opened ([used] is its usage score). */
internal fun isNew(
    installed: Long,
    used: Double,
    now: Long,
) = installed > 0 && now - installed in 0..NEW_FOR_MS && used <= 0.0

/** The letter an app files under in the rail: its name's first letter, or '#' for anything else. */
internal fun railLetter(name: String): Char = name.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#'

/**
 * The kind of app [category] (ApplicationInfo.category) says, as "group similar" names a group for it; null for one
 * that declares none. Android's own titles are longer and in the phone's language; these stay short and lowercase.
 */
internal fun categoryName(category: Int): String? =
    when (category) {
        ApplicationInfo.CATEGORY_GAME -> "games"
        ApplicationInfo.CATEGORY_AUDIO -> "audio"
        ApplicationInfo.CATEGORY_VIDEO -> "video"
        ApplicationInfo.CATEGORY_IMAGE -> "photos"
        ApplicationInfo.CATEGORY_SOCIAL -> "social"
        ApplicationInfo.CATEGORY_NEWS -> "news"
        ApplicationInfo.CATEGORY_MAPS -> "maps"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
        ApplicationInfo.CATEGORY_ACCESSIBILITY -> "accessibility"
        else -> null
    }

/** The kind of app it is, for "group similar": an Android app's declared one; null for the rest, and one that says none. */
internal val AppHit.category: String? get() = (this as? AppHit.Android)?.app?.category?.let(::categoryName)

/**
 * Where "group similar" puts apps, by group name. For each of [asked] (keys with their kind), every app of that kind
 * among [apps] that isn't in a group yet joins the asked one's group ([groupOf]), else a group named for the kind. An
 * app alone in its kind, in no group, has nothing to be grouped with; each app is put in one group at most.
 */
internal fun similarGroups(
    asked: List<Pair<String, String?>>,
    apps: List<Pair<String, String?>>,
    groupOf: (String) -> String?,
): Map<String, List<String>> {
    val out = LinkedHashMap<String, MutableList<String>>()
    val taken = HashSet<String>()
    for ((key, kind) in asked) {
        if (kind == null) continue
        val group = groupOf(key)
        val free = apps.filter { (k, c) -> c == kind && groupOf(k) == null && k !in taken }.map { it.first }
        if (free.isEmpty() || (group == null && free.size < 2)) continue
        out.getOrPut(group ?: kind) { mutableListOf() } += free
        taken += free
    }
    return out
}
