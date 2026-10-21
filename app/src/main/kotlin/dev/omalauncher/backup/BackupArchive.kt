package dev.omalauncher.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Phone build: what a launcher backup holds and how it goes back, on plain files and maps.
 *
 * It holds what is the user's: home and its pages, widgets and their saved values, the dock, drawer groups,
 * hidden apps, app names and icons, gestures, style, their own themes and backgrounds. Never Linux, never a
 * widget's `secret` state, and never what belongs to one install: setup, GPU and home-role markers, Linux's
 * setup records (they would start a setup on a phone without Linux), Android widget IDs not bound here.
 *
 * A zip of `manifest.json`, `prefs.json` and `files/…`, written the same way every time: entries sorted, one
 * fixed time, so unchanged settings make the same bytes and Feather keeps no second copy.
 */
object BackupArchive {
    const val FORMAT = 1

    /**
     * Under filesDir, what a restore replaces whole. Replacing, not merging, and with [MANAGED] beside them:
     * a merge, or these without their manifest, brings back defaults the user had deleted.
     */
    val DIRS = listOf("widgets", "style", "home", "widget-state", "app-icons", "themes", "backgrounds")

    /** [dev.omalauncher.LauncherData.LAUNCHER_MANIFEST]: which of widgets/ and style/ are still as shipped. */
    const val MANAGED = "launcher-managed.json"

    /** The preference files a backup carries keys of; [prefKept] says which keys. */
    val PREF_FILES = listOf("launcher", "widget_grid", "shortcuts", "app_widgets", "editor")

    private val LAUNCHER_KEYS = setOf("hidden_apps", "dock", "drawer_groups", "app_names", "bar_to_web", "home_layout_written")

    /** Whether [key] of preference file [file] is the user's. The rest stay as they are on restore. */
    fun prefKept(
        file: String,
        key: String,
    ): Boolean =
        when (file) {
            // Not setup_done, linux_stopped, gpu_*, home_role_asked: this install's, and a new phone must ask for home.
            "launcher" -> key in LAUNCHER_KEYS || key.startsWith("gesture_") || key.startsWith("touchpad_")
            "widget_grid" -> key == "layout"
            "shortcuts" -> key == "items" || key == "folders"
            "app_widgets" -> key == "items"
            "editor" -> key == "wrap"
            else -> false
        }

    /** Whether the file at [path] (relative to filesDir, `/`-separated) goes in a backup, and may come out of one. */
    fun carried(path: String): Boolean {
        if (path == MANAGED) return true
        // Feather refuses a zip with either, so one such file would fail every backup.
        if ('\\' in path || '\u0000' in path) return false
        val parts = path.split('/')
        if (parts.size < 2 || parts.any { it.isEmpty() || it == "." || it == ".." }) return false
        val top = parts[0]
        val rest = parts.drop(1)
        val name = rest.last()
        return when (top) {
            // Dotted ones are caches, the trash, the stock copies; but the store's records go with its files.
            "widgets" -> rest.none { it.startsWith(".") } || (rest.size == 1 && name.startsWith(".market") && name.endsWith(".json"))
            "style" -> rest.none { it.startsWith(".") } && !(rest.size == 1 && name in setOf("defaults.toml", "errors.txt"))
            "home" -> rest.none { it.startsWith(".") } && !(rest.size == 1 && name == "errors.txt")
            // persist values only; `.secret` is sealed with this install's key and stays out.
            "widget-state" -> rest.size == 1 && !name.startsWith(".") && name.endsWith(".json")
            "app-icons", "backgrounds" -> rest.none { it.startsWith(".") }
            // A theme's own markers (.omalauncher-source) are dotted; a clone's .git isn't the theme.
            "themes" -> rest.none { it == ".git" } && !name.endsWith(".tmp")
            else -> false
        }
    }

    /** What a backup of [filesDir] carries, as paths relative to it, sorted. Symlinks are never followed. */
    fun files(filesDir: File): List<String> {
        val out = mutableListOf<String>()
        fun walk(
            dir: File,
            rel: String,
        ) {
            for (f in dir.listFiles().orEmpty()) {
                val path = "$rel/${f.name}"
                when {
                    Files.isSymbolicLink(f.toPath()) -> {}
                    f.isDirectory -> walk(f, path)
                    f.isFile && carried(path) -> out += path
                }
            }
        }
        for (dir in DIRS) {
            val f = File(filesDir, dir)
            if (f.isDirectory && !Files.isSymbolicLink(f.toPath())) walk(f, dir)
        }
        if (File(filesDir, MANAGED).let { it.isFile && !Files.isSymbolicLink(it.toPath()) }) out += MANAGED
        return out.sorted()
    }

    /** Writes the backup of [filesDir] to [out]: [manifest], [prefs] (file → key → value) and every [files] path. */
    fun pack(
        out: OutputStream,
        filesDir: File,
        manifest: JSONObject,
        prefs: Map<String, Map<String, Any?>>,
        files: List<String> = files(filesDir),
    ) {
        ZipOutputStream(out).use { zip ->
            // 2000-01-01 00:00 in the phone's own zone: what a zip stores is local time, so any zone gives the same bytes.
            val time = FIXED_TIME.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            fun entry(name: String) = ZipEntry(name).apply { this.time = time }
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            zip.putNextEntry(entry("manifest.json"))
            zip.write(manifest.toString(2).toByteArray())
            zip.putNextEntry(entry("prefs.json"))
            zip.write(encodePrefs(prefs).toString(2).toByteArray())
            for (path in files) {
                // Images are compressed already; deflating them again only costs time.
                zip.setLevel(if (path.substringAfterLast('.').lowercase() in STORED_EXTENSIONS) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
                zip.putNextEntry(entry("files/$path"))
                File(filesDir, path).inputStream().use { it.copyTo(zip) }
            }
            zip.closeEntry()
        }
    }

    class Unpacked(
        val manifest: JSONObject,
        val prefs: Map<String, Map<String, Any?>>,
        /** The staged `files/`, laid out like filesDir. */
        val files: File,
    )

    /**
     * Unpacks [zip] into [staging], emptied first: `files/` as filesDir lays out, `manifest.json` and `prefs.json`
     * beside it for [stagedPrefs] after the restart. Refuses an entry whose path would leave it or that a backup
     * doesn't carry ([carried]), so a backup can't put Linux's setup records or anything else in place, and a
     * backup without its preferences (put back as none, they'd clear every one of the user's). Stops past
     * [MAX_ENTRIES] or [MAX_BYTES], counting what it reads rather than what the zip claims.
     */
    fun unpack(
        zip: File,
        staging: File,
    ): Unpacked {
        staging.deleteRecursively()
        val files = File(staging, "files").apply { mkdirs() }
        var manifest: JSONObject? = null
        var prefs: Map<String, Map<String, Any?>>? = null
        var total = 0L
        ZipFile(zip).use { z ->
            val entries = z.entries().toList()
            if (entries.size > MAX_ENTRIES) throw IOException("the backup has more than $MAX_ENTRIES files")
            for (e in entries) {
                if (e.isDirectory) continue
                val name = e.name
                if (name.startsWith("/") || '\\' in name || '\u0000' in name) throw IOException("unsafe path in the backup: $name")
                when {
                    name == "manifest.json" -> manifest = JSONObject(z.getInputStream(e).use { it.readCapped(MAX_DOCUMENT) }.decodeToString())
                    name == "prefs.json" -> prefs = decodePrefs(JSONObject(z.getInputStream(e).use { it.readCapped(MAX_DOCUMENT) }.decodeToString()))
                    name.startsWith("files/") -> {
                        val path = name.removePrefix("files/")
                        if (!carried(path)) throw IOException("the backup holds a file it shouldn't: $path")
                        val target = File(files, path)
                        target.parentFile?.mkdirs()
                        z.getInputStream(e).use { input -> target.outputStream().use { total += input.copyCapped(it, MAX_BYTES - total) } }
                    }
                    else -> throw IOException("unknown entry in the backup: $name")
                }
            }
        }
        val m = manifest ?: throw IOException("not a launcher backup (no manifest)")
        if (m.optInt("format", -1) !in 1..FORMAT) throw IOException("a backup from a newer launcher (format ${m.optInt("format")}); update first")
        val p = prefs ?: throw IOException("the backup has no settings (prefs.json)")
        File(staging, "manifest.json").writeText(m.toString())
        File(staging, "prefs.json").writeText(encodePrefs(p).toString())
        return Unpacked(m, p, files)
    }

    /** The preferences [unpack] staged in [staging]. */
    fun stagedPrefs(staging: File): Map<String, Map<String, Any?>> = decodePrefs(JSONObject(File(staging, "prefs.json").readText()))

    /** A rollback that didn't finish: some of this phone's files are still in the old/ directory. */
    class PartialRestore(
        cause: Throwable,
    ) : IOException("couldn't move everything back", cause)

    /** What [replaceFiles] moved, to [undo] if what follows fails, or to finish with [keepSecrets]. */
    class Swap internal constructor(
        private val filesDir: File,
        private val old: File,
        private val done: List<Pair<File, File>>,
    ) {
        /** Moves everything back; false if something wouldn't move (it stays in old/). */
        fun undo(): Boolean = done.asReversed().map { (from, to) -> !Files.exists(to.toPath(), LinkOption.NOFOLLOW_LINKS) || to.renameTo(from) }.all { it }

        /**
         * This install's widget secrets come back for the widgets the backup still has: they are sealed with
         * this phone's key, so they only ever work here, and a restore on the same phone shouldn't lose them.
         * Returns how many wouldn't copy.
         */
        fun keepSecrets(): Int {
            val secrets = File(old, "widget-state").listFiles { f -> f.name.endsWith(".secret") }.orEmpty()
            var lost = 0
            for (secret in secrets) {
                if (!File(filesDir, "widgets/" + secret.name.removeSuffix(".secret")).isFile) continue
                val state = File(filesDir, "widget-state").apply { mkdirs() }
                // Copied, not moved: until the restore is done, old/ must stay this phone's files whole.
                if (runCatching { secret.copyTo(File(state, secret.name), overwrite = true) }.isFailure) lost++
            }
            return lost
        }
    }

    /**
     * Moves each of [DIRS] and [MANAGED] in [filesDir] aside into [old], and the staged one from [staged] in its
     * place. Renames within one filesystem, so it's quick; on a failure what moved goes back and it throws
     * ([PartialRestore] if that didn't work either).
     */
    fun replaceFiles(
        filesDir: File,
        staged: File,
        old: File,
    ): Swap {
        old.mkdirs()
        val done = mutableListOf<Pair<File, File>>()
        val swap = Swap(filesDir, old, done)
        try {
            for (name in DIRS + MANAGED) {
                val current = File(filesDir, name)
                if (Files.exists(current.toPath(), LinkOption.NOFOLLOW_LINKS)) move(current, File(old, name), done)
                val incoming = File(staged, name)
                if (incoming.exists()) move(incoming, current, done)
            }
        } catch (e: Exception) {
            if (!swap.undo()) throw PartialRestore(e)
            throw e
        }
        return swap
    }

    /** Which of [DIRS] and [MANAGED] are in [filesDir] now: a restore's journal, for [recover]. */
    fun existing(filesDir: File): List<String> = (DIRS + MANAGED).filter { Files.exists(File(filesDir, it).toPath(), LinkOption.NOFOLLOW_LINKS) }

    /**
     * Puts [filesDir] back as it was before a [replaceFiles] that was cut off at any point: [existed] (from
     * [existing], taken just before) says which were there. What came in from the backup goes to [aside], and
     * what's in [old] comes back. False if something wouldn't move; running it again carries on.
     */
    fun recover(
        filesDir: File,
        old: File,
        aside: File,
        existed: Set<String>,
    ): Boolean {
        var ok = true
        aside.mkdirs()
        for (name in DIRS + MANAGED) {
            val current = File(filesDir, name)
            val saved = File(old, name)
            val here = Files.exists(current.toPath(), LinkOption.NOFOLLOW_LINKS)
            val kept = Files.exists(saved.toPath(), LinkOption.NOFOLLOW_LINKS)
            // Still in place (the swap never reached it), or never there and nothing came in.
            if (name in existed && !kept) continue
            if (here && !current.renameTo(File(aside, name))) ok = false
            if (kept && !saved.renameTo(current)) ok = false
        }
        return ok
    }

    private fun move(
        from: File,
        to: File,
        done: MutableList<Pair<File, File>>,
    ) {
        if (!from.renameTo(to)) throw IOException("can't move $from to $to")
        done += from to to
    }

    /**
     * For one preference file: the keys to remove (every one of the user's there now) and the values to put (the
     * backup's), so keys the backup doesn't have don't linger. A backup value of another type than this
     * launcher keeps under that key now is left out.
     */
    fun prefChanges(
        file: String,
        current: Map<String, *>,
        backup: Map<String, Any?>,
    ): Pair<Set<String>, Map<String, Any?>> =
        current.keys.filter { prefKept(file, it) }.toSet() to
            // A value whose type this launcher no longer stores there would throw at its first read.
            backup.filter { (key, v) -> prefKept(file, key) && current[key].let { it == null || sameKind(it, v) } }

    private fun sameKind(
        a: Any?,
        b: Any?,
    ) = a is String && b is String || a is Boolean && b is Boolean || a is Int && b is Int || a is Long && b is Long ||
        a is Float && b is Float || a is Set<*> && b is Set<*>

    /**
     * "app_widgets" items without the widgets whose ID isn't bound here to the same provider, and the providers
     * of those dropped. A reinstall or another phone binds none of them; worse, a later widget there can get the
     * same number, and two tiles would share it.
     */
    fun boundAppWidgets(
        items: String?,
        bound: (id: Int, provider: String) -> Boolean,
    ): Pair<String?, List<String>> {
        val root = items?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return items to emptyList()
        val array = root.optJSONArray("data") ?: return items to emptyList()
        val kept = JSONArray()
        val dropped = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (bound(o.optInt("id", -1), o.optString("provider"))) kept.put(o) else dropped += o.optString("provider")
        }
        return root.put("data", kept).toString() to dropped
    }

    /** IDs in "app_widgets" [items] (a versioned array of `{id, …}`). */
    fun appWidgetIds(items: String?): Set<Int> {
        val array = items?.let { runCatching { JSONObject(it).optJSONArray("data") }.getOrNull() } ?: return emptySet()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optInt("id", -1)?.takeIf { id -> id >= 0 } }.toSet()
    }

    /** "widget_grid" [layout] without the cells (and glass) of Android widgets other than [keep]. */
    fun withoutOtherAppWidgets(
        layout: String?,
        keep: Set<Int>,
    ): String? {
        val root = layout?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return layout
        val data = root.optJSONObject("data") ?: return layout
        for (section in listOf("cells", "glass")) {
            val o = data.optJSONObject(section) ?: continue
            for (key in o.keys().asSequence().toList()) {
                val id = key.removePrefix("appwidget:").takeIf { key.startsWith("appwidget:") }?.toIntOrNull() ?: continue
                if (id !in keep) o.remove(key)
            }
        }
        return root.toString()
    }

    /** Typed, since SharedPreferences has no JSON of its own: `{"file": {"key": {"t": "s", "v": …}}}`, keys sorted. */
    fun encodePrefs(prefs: Map<String, Map<String, Any?>>): JSONObject {
        val root = JSONObject()
        for (file in prefs.keys.sorted()) {
            val values = JSONObject()
            val map = prefs.getValue(file)
            for (key in map.keys.sorted()) {
                val v = map[key]
                val typed =
                    when (v) {
                        is String -> JSONObject().put("t", "s").put("v", v)
                        is Boolean -> JSONObject().put("t", "b").put("v", v)
                        is Int -> JSONObject().put("t", "i").put("v", v)
                        is Long -> JSONObject().put("t", "l").put("v", v)
                        is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                        is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }.sorted()))
                        else -> continue
                    }
                values.put(key, typed)
            }
            root.put(file, values)
        }
        return root
    }

    fun decodePrefs(json: JSONObject): Map<String, Map<String, Any?>> =
        json.keys().asSequence().associateWith { file ->
            val values = json.getJSONObject(file)
            values.keys().asSequence().mapNotNull { key ->
                val o = values.getJSONObject(key)
                val v: Any =
                    when (o.getString("t")) {
                        "s" -> o.getString("v")
                        "b" -> o.getBoolean("v")
                        "i" -> o.getInt("v")
                        "l" -> o.getLong("v")
                        "f" -> o.getDouble("v").toFloat()
                        "set" -> o.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                        else -> return@mapNotNull null
                    }
                key to v
            }.toMap()
        }

    private fun java.io.InputStream.readCapped(budget: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        copyCapped(out, budget)
        return out.toByteArray()
    }

    private fun java.io.InputStream.copyCapped(
        out: OutputStream,
        budget: Long,
    ): Long {
        val buf = ByteArray(1 shl 16)
        var n = 0L
        while (true) {
            val r = read(buf)
            if (r < 0) return n
            n += r
            if (n > budget) throw IOException("the backup unpacks to more than it may (${MAX_BYTES / (1024 * 1024)} MB in all, 4 MB for its settings)")
            out.write(buf, 0, r)
        }
    }

    const val MAX_ENTRIES = 20_000
    const val MAX_BYTES = 1024L * 1024 * 1024

    /** manifest.json and prefs.json: a few kilobytes really; this only stops one from filling memory. */
    private const val MAX_DOCUMENT = 4L * 1024 * 1024
    private val FIXED_TIME = LocalDateTime.of(2000, 1, 1, 0, 0)
    private val STORED_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "avif", "heic", "mp4", "webm", "zip", "gz", "xz", "zst")
}
