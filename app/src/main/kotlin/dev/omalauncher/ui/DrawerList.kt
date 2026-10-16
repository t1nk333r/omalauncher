package dev.omalauncher.ui

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

/**
 * [hits] in the drawer's [sort] order: "name" A to Z, "used" by [score] (most first), "new" by [AppHit.installed]
 * (newest first). Ties, and apps that don't say when they were installed, go by [name].
 */
internal fun sortApps(
    hits: List<AppHit>,
    sort: String,
    name: (AppHit) -> String,
    score: (AppHit) -> Double,
): List<AppHit> {
    val byName = compareBy<AppHit> { name(it).lowercase() }
    return when (sort) {
        "used" -> hits.sortedWith(compareByDescending<AppHit> { score(it) }.then(byName))
        "new" -> hits.sortedWith(compareByDescending<AppHit> { it.installed }.then(byName))
        else -> hits.sortedWith(byName)
    }
}

/** One line of the drawer's list. */
internal sealed interface DrawerEntry {
    /** Stable across recompositions, for the list's item keys. */
    val key: String
}

/** A section's title, with bracketed [actions] at its end. */
internal class HeaderEntry(
    override val key: String,
    val title: String,
    val actions: List<Pair<String, () -> Unit>> = emptyList(),
) : DrawerEntry

/** A row of apps; [selected] is the one Enter would open, or -1. */
internal class RowEntry(
    override val key: String,
    val cells: List<AppHit>,
    val selected: Int = -1,
) : DrawerEntry

/** Where Linux apps would be listed, offering to install Linux. */
internal object LinuxOfferEntry : DrawerEntry {
    override val key = "linux-offer"
}

/** Adds a section: its title (unless [titled] is false) and its [hits] in rows of [columns]. Nothing for no apps and no actions. */
internal fun MutableList<DrawerEntry>.section(
    id: String,
    title: String,
    hits: List<AppHit>,
    columns: Int,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
    titled: Boolean = true,
) {
    if (hits.isEmpty() && actions.isEmpty()) return
    if (titled) add(HeaderEntry("h:$id", title, actions))
    hits.chunked(columns).forEachIndexed { r, cells -> add(RowEntry("$id:$r", cells)) }
}

/** The letter an app files under in the rail: its name's first letter, or '#' for anything else. */
internal fun railLetter(name: String): Char = name.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#'

/**
 * For each letter in the rail, the index in [entries] of the first row holding an app that starts with it, in the
 * order the letters first appear. Rows before [from] (the most-used row, groups) aren't counted.
 */
internal fun letterIndex(
    entries: List<DrawerEntry>,
    name: (AppHit) -> String,
    from: Int = 0,
): Map<Char, Int> {
    val out = LinkedHashMap<Char, Int>()
    entries.forEachIndexed { i, entry ->
        if (i < from || entry !is RowEntry) return@forEachIndexed
        for (hit in entry.cells) out.putIfAbsent(railLetter(name(hit)), i)
    }
    return out.entries.sortedBy { if (it.key == '#') '{' else it.key }.associate { it.key to it.value }
}
