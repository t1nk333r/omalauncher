package dev.omalauncher

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import dev.omalauncher.backup.Backups

class App : Application() {
    val runtime by lazy { Runtime(this) }

    /** Phone build: settings kept on Feather; see [Backups]. */
    val backups by lazy { Backups(this) }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        AppLanguage.load(this, base)
    }

    /** In the language picked in settings, if one is: what the app's context reads its text from. */
    override fun getResources(): Resources = AppLanguage.resources(baseContext, super.getResources())

    override fun onConfigurationChanged(newConfig: Configuration) {
        // Android has set the default Locale back to the phone's; the widgets' callbacks below read it.
        AppLanguage.applyDefault()
        super.onConfigurationChanged(newConfig)
    }

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
