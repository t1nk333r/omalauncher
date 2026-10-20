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

/** The letter an app files under in the rail: its name's first letter, or '#' for anything else. */
internal fun railLetter(name: String): Char = name.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#'
