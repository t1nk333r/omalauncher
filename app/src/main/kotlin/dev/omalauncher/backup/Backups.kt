package dev.omalauncher.backup

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.omalauncher.LauncherData
import dev.omalauncher.SecretBox
import dev.omalauncher.upgrade.writeAtomically
import dev.omalauncher.widgets.AppWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Phone build: the launcher's settings kept on Feather (its device backups) and brought back from there, after
 * a reinstall or onto a new phone. What goes in is [BackupArchive]'s; this is where it's kept, the token, the
 * daily job and the restart a restore needs.
 *
 * A restore never touches files the running launcher watches: it stages the backup in [restoreDir], restarts
 * the process, and [applyStaged] puts it in place from [dev.omalauncher.App.onCreate], before anything reads
 * those files. Its own settings (server, token, this phone's ID) are outside the backup and outside Android's.
 */
class Backups(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val tokenFile = File(context.filesDir, TOKEN_FILE)
    private val restoreDir = File(context.filesDir, RESTORE_DIR)
    private val lock = Mutex()

    /** Backups and restores run here, not in a screen's scope: leaving the screen doesn't stop them. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Status(
        val at: Long,
        val ok: Boolean,
        val message: String,
    )

    private val _busy = MutableStateFlow<String?>(null)

    /** What's under way ("backing up…"), or null. */
    val busy: StateFlow<String?> = _busy

    private val _last = MutableStateFlow(readStatus())

    /** How the last backup or restore went. */
    val last: StateFlow<Status?> = _last

    var server: String
        get() = prefs.getString(SERVER, null) ?: DEFAULT_SERVER
        set(value) = prefs.edit().putString(SERVER, value.trim().trimEnd('/')).apply()

    /** This phone's name on Feather; the phone's own device name until changed. */
    var label: String
        get() = prefs.getString(LABEL, null) ?: deviceName()
        set(value) = prefs.edit().putString(LABEL, value.trim().ifEmpty { deviceName() }.take(80)).apply()

    /** This install's folder on Feather: made once, so a reinstall starts a new one and the old one's backups stay. */
    val deviceId: String
        get() =
            prefs.getString(DEVICE, null) ?: ("phone-" + UUID.randomUUID().toString().replace("-", "").take(16)).also {
                prefs.edit().putString(DEVICE, it).commit()
            }

    var daily: Boolean
        get() = prefs.getBoolean(DAILY, true)
        set(value) {
            prefs.edit().putBoolean(DAILY, value).apply()
            ensureScheduled()
        }

    val hasToken get() = tokenFile.isFile

    /** Kept sealed with the keystore key widget secrets use: the file is useless off this install. Blank forgets it. */
    fun setToken(token: String) {
        val t = token.trim()
        if (t.isEmpty()) {
            tokenFile.delete()
        } else {
            val sealed = SecretBox.seal(t.toByteArray()) ?: throw IOException("can't encrypt the token on this phone")
            tokenFile.writeBytes(sealed)
        }
        ensureScheduled()
    }

    private fun client(): FeatherBackups {
        val token = tokenFile.takeIf { it.isFile }?.let { SecretBox.unseal(it.readBytes()) }?.decodeToString()
            ?: throw IOException("no Feather token yet: paste a device backups token above")
        return FeatherBackups(server, token, context.packageName)
    }

    suspend fun devices(): List<FeatherBackups.Device> = withContext(Dispatchers.IO) { client().devices() }

    suspend fun snapshots(device: String): List<FeatherBackups.Snapshot> = withContext(Dispatchers.IO) { client().snapshots(device) }

    /** Backs up now; [auto] for the daily job, which says less when nothing changed. */
    suspend fun backUp(auto: Boolean = false): Status =
        withContext(Dispatchers.IO) {
            lock.withLock {
                _busy.value = "backing up…"
                try {
                    val (snapshot, unchanged) = upload()
                    val size = "%.1f MB".format(snapshot.size / 1048576.0)
                    record(true, if (unchanged) "unchanged since the last backup · $size" else "backed up · $size")
                } catch (e: Exception) {
                    Log.w(TAG, "backup failed (auto=$auto)", e)
                    record(false, "backup failed: ${reason(e)}")
                } finally {
                    _busy.value = null
                }
            }
        }

    private fun upload(): Pair<FeatherBackups.Snapshot, Boolean> {
        val client = client()
        val zip = File(context.cacheDir, "backup-upload.zip")
        try {
            zip.outputStream().use { BackupArchive.pack(it, context.filesDir, manifest(), currentPrefs()) }
            return client.upload(deviceId, label, zip)
        } finally {
            zip.delete()
        }
    }

    private val _unsafe = MutableStateFlow<Pair<FeatherBackups.Device, FeatherBackups.Snapshot>?>(null)

    /** A restore that's downloaded but couldn't back this phone up first: the screen asks whether to go on without. */
    val unsafe: StateFlow<Pair<FeatherBackups.Device, FeatherBackups.Snapshot>?> = _unsafe

    fun dismissUnsafe() {
        _unsafe.value = null
    }

    /**
     * Restores [snapshot] of [device]: downloads and checks it, stages it, backs this phone up (the way back from
     * here; skipped only when [safetyCopy] is false, after asking), and restarts the launcher, which puts it in
     * place as it starts. Downloading first, so the safety copy can't prune the very backup being restored.
     * Returns only when nothing changed.
     */
    suspend fun restore(
        device: FeatherBackups.Device,
        snapshot: FeatherBackups.Snapshot,
        safetyCopy: Boolean = true,
    ): Status =
        withContext(Dispatchers.IO) {
            _unsafe.value = null
            lock.withLock {
                try {
                    _busy.value = "downloading ${device.label}'s backup…"
                    if (!discardRestore()) throw IOException("an earlier restore's files are still in files/$RESTORE_DIR and can't be moved aside")
                    restoreDir.mkdirs()
                    val zip = File(restoreDir, "backup.zip")
                    client().download(device.id, snapshot, zip)
                    _busy.value = "unpacking…"
                    val unpacked = BackupArchive.unpack(zip, File(restoreDir, STAGING))
                    val from = unpacked.manifest.optString("package")
                    if (from != context.packageName) throw IOException("that backup is of $from, not this launcher")
                    zip.delete()
                    if (safetyCopy) {
                        _busy.value = "backing up this phone first…"
                        try {
                            upload()
                        } catch (e: Exception) {
                            Log.w(TAG, "safety copy failed", e)
                            discardRestore()
                            _busy.value = null
                            _unsafe.value = device to snapshot
                            return@withLock record(false, "couldn't back this phone up first: ${reason(e)}")
                        }
                    }
                    File(restoreDir, READY).writeText(
                        JSONObject().put("label", device.label).put("created", snapshot.created).put("staged", System.currentTimeMillis()).toString(),
                    )
                    _busy.value = "restarting…"
                } catch (e: Exception) {
                    Log.w(TAG, "restore failed", e)
                    discardRestore()
                    _busy.value = null
                    return@withLock record(false, "restore failed: ${reason(e)}")
                }
                runCatching { RestartActivity.restart(context) }.onFailure { Log.w(TAG, "can't restart", it) }
                // Still here: Android didn't let the launcher restart (it was left, or the screen went off). A
                // staged restore mustn't wait for whenever the process next starts, so it goes.
                delay(RESTART_WAIT_MS)
                discardRestore()
                _busy.value = null
                record(false, "the launcher couldn't restart itself, so nothing changed: restore again with this screen open")
            }
        }

    /**
     * Puts a staged restore in place. Called first thing as the process starts, so nothing has read these files
     * or preferences yet, and it never throws: a launcher that can't start is worse than any lost restore.
     *
     * Before anything moves, a journal ([APPLYING]) records which of the replaced files existed, and this phone's
     * own settings go beside it. The journal goes only after success or a full rollback; finding it at a start
     * means a restore was cut off (the process killed, an Error) or couldn't be undone, and [recover] puts this
     * phone's files and settings back first.
     */
    fun applyStaged() {
        try {
            when {
                File(restoreDir, APPLYING).exists() -> recover()
                File(restoreDir, READY).isFile ->
                    try {
                        apply()
                    } catch (e: BackupArchive.PartialRestore) {
                        // The rollback stopped part-way; the journal says what to put back, so try that now.
                        Log.e(TAG, "restore half-applied", e)
                        recover()
                    }
                restoreDir.exists() -> discardRestore()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "restore", t)
            runCatching { record(false, "restore: ${t.message ?: t.javaClass.simpleName}") }
            // An Error part-way through: put this phone back now rather than start on half-swapped files.
            if (File(restoreDir, APPLYING).exists()) runCatching { recover() }
        }
        thread(name = "restore-sweep") {
            context.cacheDir.listFiles { f -> f.name.startsWith(TRASH) }?.forEach { runCatching { LauncherData.deleteTree(it) } }
        }
    }

    private fun apply() {
        val ready = File(restoreDir, READY)
        val info = runCatching { JSONObject(ready.readText()) }.getOrDefault(JSONObject())
        val age = System.currentTimeMillis() - info.optLong("staged", 0)
        if (age !in 0..STALE_MS) {
            ready.delete()
            record(false, "a restore staged ${age / 60_000} minutes ago wasn't applied; nothing changed")
            discardRestore()
            return
        }
        val staged = File(restoreDir, STAGING)
        val journal = File(restoreDir, APPLYING)
        val prefs =
            try {
                // The journal before the marker goes: whatever stops this from here on, a start finds one of them.
                writeAtomically(File(restoreDir, BEFORE), BackupArchive.encodePrefs(currentPrefs()).toString().toByteArray())
                writeAtomically(journal, JSONObject().put("existed", org.json.JSONArray(BackupArchive.existing(context.filesDir))).toString().toByteArray())
                ready.delete()
                BackupArchive.stagedPrefs(staged)
            } catch (e: Exception) {
                Log.e(TAG, "restore not applied", e)
                journal.delete()
                ready.delete()
                record(false, "restore not applied, nothing changed: ${reason(e)}")
                discardRestore()
                return
            }
        val swap =
            try {
                BackupArchive.replaceFiles(context.filesDir, File(staged, "files"), File(restoreDir, OLD))
            } catch (e: BackupArchive.PartialRestore) {
                throw e
            } catch (e: Exception) {
                // Moved back already; the settings weren't touched yet.
                Log.e(TAG, "restore not applied", e)
                journal.delete()
                record(false, "restore not applied, nothing changed: ${reason(e)}")
                discardRestore()
                return
            }
        val dropped =
            try {
                applyPrefs(prefs)
            } catch (e: Exception) {
                Log.e(TAG, "restore not applied", e)
                if (!swap.undo() || !restorePrefs(readBefore())) throw BackupArchive.PartialRestore(e)
                journal.delete()
                record(false, "restore not applied, nothing changed: ${reason(e)}")
                discardRestore()
                return
            }
        val lostSecrets = swap.keepSecrets()
        journal.delete()
        val again = if (dropped > 0) " · add its $dropped Android widget${if (dropped == 1) "" else "s"} again" else ""
        val secrets = if (lostSecrets > 0) " · $lostSecrets widget secret${if (lostSecrets == 1) "" else "s"} couldn't be kept" else ""
        record(true, "restored ${info.optString("label", "a backup")} from ${localTime(info.optString("created"))}$again$secrets")
        discardRestore()
    }

    /**
     * After a cut-off or failed restore: this phone's files and settings as they were before it. The files only
     * once: when they're back the journal says so, and a later start (the launcher ran in between, making files
     * of its own) only retries the settings.
     */
    private fun recover() {
        val journal = File(restoreDir, APPLYING)
        val entry = runCatching { JSONObject(journal.readText()) }.getOrNull()
        var back = entry?.optBoolean("filesBack") == true
        if (!back && entry != null) {
            val existed = runCatching { entry.getJSONArray("existed").let { a -> (0 until a.length()).map(a::getString).toSet() } }.getOrNull()
            back = existed != null && BackupArchive.recover(context.filesDir, File(restoreDir, OLD), File(restoreDir, "undone-${System.currentTimeMillis()}"), existed)
            if (back) {
                runCatching { writeAtomically(journal, entry.put("filesBack", true).toString().toByteArray()) }
                // What came in from the backup and the backup itself: not needed any more, and maybe the space that ran out.
                restoreDir.listFiles { f -> f.name == STAGING || f.name.startsWith("undone-") }?.forEach { runCatching { LauncherData.deleteTree(it) } }
            }
        }
        val before = runCatching { readBefore() }.getOrNull()
        if (back && before != null && restorePrefs(before)) {
            journal.delete()
            record(false, "a restore was cut off; this phone's home, widgets and settings are back as they were")
            discardRestore()
        } else {
            record(false, "a restore was cut off and isn't fully undone (this phone's files are kept in files/$RESTORE_DIR or files/$KEPT_DIR); restoring its latest backup from feather puts it right")
        }
    }

    private fun readBefore() = BackupArchive.decodePrefs(JSONObject(File(restoreDir, BEFORE).readText()))

    /**
     * Moves [restoreDir] into the cache at once and deletes it there in the background, so a restore started
     * right after never finds its new files deleted under it. One that couldn't be undone holds this phone's
     * own files: it's kept aside instead, never deleted.
     */
    private fun discardRestore(): Boolean {
        if (!restoreDir.exists()) return true
        if (File(restoreDir, APPLYING).exists()) {
            val parent = File(context.filesDir, KEPT_DIR)
            val kept = File(parent, System.currentTimeMillis().toString())
            // Never deleted, even when it won't move: then it stays where it is.
            return (parent.isDirectory || parent.mkdirs()) && restoreDir.renameTo(kept)
        }
        val trash = File(context.cacheDir, "$TRASH${System.nanoTime()}")
        if (!restoreDir.renameTo(trash)) {
            runCatching { LauncherData.deleteTree(restoreDir) }
            return !restoreDir.exists()
        }
        thread(name = "restore-cleanup") { runCatching { LauncherData.deleteTree(trash) } }
        return true
    }

    /** Writes the backup's preferences; returns how many of its Android widgets aren't bound here. Throws if one won't write. */
    private fun applyPrefs(backup: Map<String, Map<String, Any?>>): Int {
        val appWidgets = backup["app_widgets"].orEmpty()
        val manager = AppWidgetManager.getInstance(context)
        val ours = runCatching { AppWidgetHost(context, AppWidgets.HOST_ID).appWidgetIds.toSet() }.getOrDefault(emptySet())
        val (items, dropped) =
            BackupArchive.boundAppWidgets(appWidgets["items"] as? String) { id, provider ->
                id in ours && manager.getAppWidgetInfo(id)?.provider?.flattenToString() == provider
            }
        val kept = BackupArchive.appWidgetIds(items)
        val fixed =
            backup.mapValues { (file, values) ->
                when (file) {
                    "app_widgets" -> if (items == null) values - "items" else values + ("items" to items)
                    "widget_grid" -> values + ("layout" to BackupArchive.withoutOtherAppWidgets(values["layout"] as? String, kept))
                    else -> values
                }
            }
        for (file in BackupArchive.PREF_FILES) {
            val sp = context.getSharedPreferences(file, Context.MODE_PRIVATE)
            val (remove, put) = BackupArchive.prefChanges(file, sp.all, fixed[file].orEmpty())
            if (!write(sp, remove, put)) throw IOException("can't write the $file settings")
        }
        return dropped.size
    }

    /** Puts [before] (this phone's own kept keys) back in every preference file; false if one wouldn't write. */
    private fun restorePrefs(before: Map<String, Map<String, Any?>>): Boolean =
        BackupArchive.PREF_FILES
            .map { file ->
                val sp = context.getSharedPreferences(file, Context.MODE_PRIVATE)
                write(sp, sp.all.keys.filter { BackupArchive.prefKept(file, it) }.toSet(), before[file].orEmpty())
            }.all { it }

    private fun write(
        sp: android.content.SharedPreferences,
        remove: Set<String>,
        put: Map<String, Any?>,
    ): Boolean {
        val edit = sp.edit()
        remove.forEach { edit.remove(it) }
        for ((key, v) in put) {
            @Suppress("UNCHECKED_CAST")
            when (v) {
                is String -> edit.putString(key, v)
                is Boolean -> edit.putBoolean(key, v)
                is Int -> edit.putInt(key, v)
                is Long -> edit.putLong(key, v)
                is Float -> edit.putFloat(key, v)
                is Set<*> -> edit.putStringSet(key, v as Set<String>)
            }
        }
        return edit.commit()
    }

    private fun currentPrefs(): Map<String, Map<String, Any?>> =
        BackupArchive.PREF_FILES.associateWith { file ->
            context.getSharedPreferences(file, Context.MODE_PRIVATE).all.filterKeys { BackupArchive.prefKept(file, it) }
        }

    /** No dates: the same settings make the same bytes. A new launcher version makes a new backup. */
    private fun manifest(): JSONObject {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return JSONObject()
            .put("format", BackupArchive.FORMAT)
            .put("package", context.packageName)
            .put("versionCode", info.longVersionCode)
            .put("versionName", info.versionName)
    }

    /**
     * The daily backup, while there's a token and it's on. Not persisted across reboots: the launcher starts at boot
     * and calls this. Never throws, since [dev.omalauncher.App.onCreate] calls it (a network constraint needs
     * ACCESS_NETWORK_STATE, or schedule() throws, from Android 14).
     */
    fun ensureScheduled() {
        runCatching { schedule() }.onFailure { Log.w(TAG, "can't schedule the daily backup", it) }
    }

    private fun schedule() {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!daily || !hasToken) {
            scheduler.cancel(JOB_ID)
            return
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job =
            JobInfo
                .Builder(JOB_ID, ComponentName(context, BackupJobService::class.java))
                .setPeriodic(TimeUnit.DAYS.toMillis(1), TimeUnit.HOURS.toMillis(12))
                // Wi-Fi, where Feather (home network or Tailscale) is likeliest to answer.
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()
        scheduler.schedule(job)
    }

    private fun record(
        ok: Boolean,
        message: String,
    ): Status {
        val s = Status(System.currentTimeMillis(), ok, message)
        prefs.edit().putLong(LAST_AT, s.at).putBoolean(LAST_OK, ok).putString(LAST_MESSAGE, message).commit()
        _last.value = s
        return s
    }

    private fun readStatus(): Status? {
        val at = prefs.getLong(LAST_AT, 0)
        if (at == 0L) return null
        return Status(at, prefs.getBoolean(LAST_OK, false), prefs.getString(LAST_MESSAGE, null).orEmpty())
    }

    private fun deviceName(): String =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    companion object {
        /** Since build 1503 the phone build's own share page backs up to Feather's host; so does this. */
        const val DEFAULT_SERVER = "https://feather.aloqaili.xyz"

        /** Outside the backup and Android's (backup_rules.xml): another install must get its own ID and token. */
        const val PREFS = "backup"
        const val TOKEN_FILE = "backup-token"
        const val RESTORE_DIR = ".restore"

        private const val STAGING = "staging"

        /** In [RESTORE_DIR] once a restore is staged and waits for the restart; [RestartActivity] checks it too. */
        const val READY = "ready"

        /** The journal of a restore being applied; see [applyStaged]. */
        private const val APPLYING = "applying"

        /** This phone's own kept preferences, beside the journal. */
        private const val BEFORE = "before-prefs.json"

        /** Where [BackupArchive.replaceFiles] moves this phone's files aside. */
        private const val OLD = "old"

        /** Under filesDir: a restore that couldn't be undone, kept rather than deleted (outside Android's backup too). */
        const val KEPT_DIR = ".restore-kept"
        private const val TRASH = "restore-trash-"

        /** A staged restore older than this is dropped, not applied: the restart that should have followed didn't. */
        private const val STALE_MS = 10 * 60_000L
        private const val RESTART_WAIT_MS = 8_000L
        private const val JOB_ID = 0x0ba1
        private const val SERVER = "server"
        private const val LABEL = "label"
        private const val DEVICE = "device_id"
        private const val DAILY = "daily"
        private const val LAST_AT = "last_at"
        private const val LAST_OK = "last_ok"
        private const val LAST_MESSAGE = "last_message"
        private const val TAG = "backups"

        private val LOCAL_TIME = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        /** Feather's UTC time in this phone's zone. */
        fun localTime(iso: String): String =
            runCatching { LOCAL_TIME.format(java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())) }.getOrDefault(iso)

        /** A short reason for the settings screen. */
        fun reason(e: Exception): String =
            when (e) {
                is FeatherBackups.FeatherError -> e.message.orEmpty()
                is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
                    "can't reach Feather (it answers on the home network or Tailscale)"
                else -> e.message ?: e.javaClass.simpleName
            }
    }
}
