package dev.omalauncher

import android.app.Application
import dev.omalauncher.backup.Backups

class App : Application() {
    val runtime by lazy { Runtime(this) }

    /** Phone build: settings kept on Feather; see [Backups]. */
    val backups by lazy { Backups(this) }

    override fun onCreate() {
        super.onCreate()
        // Phone build: a restore staged before the restart goes in before anything reads what it replaces.
        // Only in the launcher's own process, not the one that restarted it.
        // Neither may ever take home down: both catch their own failures, and this is the last guard.
        if (getProcessName() == packageName) {
            runCatching { backups.applyStaged() }
            runCatching { backups.ensureScheduled() }
        }
    }
}
