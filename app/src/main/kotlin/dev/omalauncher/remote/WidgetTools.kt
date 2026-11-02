package dev.omalauncher.remote

import dev.omalauncher.widgets.lang.CompileError
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.OwlKind
import dev.omalauncher.widgets.lang.Parser
import dev.omalauncher.widgets.lang.Program
import dev.omalauncher.widgets.lang.Source
import dev.omalauncher.widgets.lang.WidgetAst
import dev.omalauncher.widgets.lang.sha256Hex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Phone build: what a coding agent on a computer may do in the widgets folder ([dir], the guest's
 * ~/.config/omalauncher/widgets), the tools [McpServer] serves. The same files and the same check as an agent in
 * the guest uses (owl-check.sh), so the launcher loads what's written here exactly as it loads theirs.
 *
 * Unless [allowCommands] says yes, a file that would run Linux commands, read files or what Android knows about the
 * user, or act as them, is refused ([Guard]): otherwise a widget could show the agents' keys in its own picture.
 * Then a file the agent didn't write starts without the values its version before kept, and only the agent's own
 * files are checked ([AgentLedger]): a picture would show them.
 */
class WidgetTools(
    private val dir: File,
    private val allowCommands: () -> Boolean,
    private val ledger: AgentLedger,
    private val guard: Guard = Guard(),
    /** A picture as sent to the agent: Android makes one too large for a model smaller (PcAgent). */
    private val fitImage: (ByteArray) -> Image = { Image(it, "image/png") },
    private val loadWaitMs: Long = 10_000,
    private val previewWaitMs: Long = 25_000,
    private val pollMs: Long = 100,
    /** How long after a write its problems are left to check_widget, and how often one file's are told at most ([changes]). */
    private val quietMs: Long = 30_000,
    private val failingEveryMs: Long = 600_000,
) {
    class ToolError(
        message: String,
    ) : Exception(message)

    class Image(
        val bytes: ByteArray,
        val mimeType: String,
    )

    /**
     * Something that happened on the phone to a file written from here, for [McpServer]'s event stream: [event] is
     * "changed", "deleted" or "failing", and [text] says so in the launcher's own words.
     */
    class Change(
        val name: String,
        val event: String,
        val text: String,
    )

    /** A tool's answer: its text, and the pictures to show with it. */
    class Result(
        val text: String,
        val images: List<Image> = emptyList(),
        val isError: Boolean = false,
    )

    private val checkDir get() = File(dir, ".check")
    private val errorDir get() = File(dir, ".errors")
    private val previewDir get() = File(dir, ".preview")
    private val historyDir get() = File(dir, HISTORY)

    /** Writes and deletes, and what [changes] keeps, one at a time: it compares a file with the ledger's hash. */
    private val lock = Any()

    /** Writes and deletes so far: a look by [changes] that a write or delete overtook is dropped. */
    private var generation = 0L

    /**
     * What [changes] last saw of a file written from here: [last] as seen by the call before, [told] as last said; the
     * problems already said ([toldFailing]), whether new ones were there the call before ([newFailing]), and when
     * ([failingAt], System.nanoTime) some were last said.
     */
    private class Seen(
        var last: String,
        var told: String,
        var toldFailing: Set<String>,
        var newFailing: Boolean = false,
        var failingAt: Long? = null,
    )

    private val seen = HashMap<String, Seen>()
    private var watching = false

    /** Until when (System.nanoTime) a file's problems are left to check_widget, since it was just written. */
    private val quiet = HashMap<String, Long>()

    /** The problems check_widget last returned for each file, which needn't be told again. */
    private val reported = HashMap<String, Set<String>>()

    /** Every file the launcher loads, with what it is and whether its current version compiled. */
    fun list(): Result {
        val files =
            dir.listFiles { f -> isLoadable(f.name) && (f.isFile || Files.isSymbolicLink(f.toPath())) }.orEmpty().sortedBy { it.name }.map { f ->
                val bytes = f.readBytesOrNull()
                val hash = bytes?.let(::sha256Hex)
                val check = File(checkDir, "${f.name}.txt")
                val status =
                    when {
                        Files.isSymbolicLink(f.toPath()) -> "a link, which these tools don't follow"
                        hash == null -> "unreadable"
                        field(check, "sha256") != hash -> "not loaded yet"
                        field(check, "result") == "ok" -> "compiles"
                        else -> "doesn't compile"
                    }
                JSONObject()
                    .put("name", f.name)
                    .put("kind", bytes?.let { OwlKind.of(it.decodeToString()).word } ?: "unknown")
                    .put("bytes", f.length())
                    .put("status", status)
                    // While commands are off, only these can be checked: see check().
                    .put("written_from_here", hash != null && ledger.written(f.name) == hash)
            }
        val docs = listOf("README.md") + File(dir, "docs").list { _, n -> n.endsWith(".md") }.orEmpty().sorted().map { "docs/$it" }
        val examples = File(dir, "examples").list { _, n -> n.endsWith(".owl") }.orEmpty().sorted().map { "examples/$it" }
        val out =
            JSONObject()
                .put("files", JSONArray(files))
                .put("docs", JSONArray(docs))
                .put("examples", JSONArray(examples))
                .put("commands_allowed", allowCommands())
        return Result(out.toString(2))
    }

    /** A file or folder in the widgets folder, by its path there: a widget, a doc, an example, a stock file. */
    fun read(path: String): Result {
        val f = readable(path)
        if (f.isDirectory) {
            val entries = f.listFiles().orEmpty().filter { !it.name.startsWith(".") }.sortedBy { it.name }.map { if (it.isDirectory) it.name + "/" else it.name }
            return Result(entries.joinToString("\n").ifEmpty { "(empty)" })
        }
        if (!Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)) throw ToolError("$path isn't a file")
        if (f.length() > MAX_READ) throw ToolError("$path is ${f.length()} bytes, more than this reads (${MAX_READ shr 20} MiB)")
        return Result(noFollow(f.toPath(), MAX_READ)?.decodeToString() ?: throw ToolError("$path: can't be read"))
    }

    /**
     * Saves [content] as widgets/[name] in one rename, as an editor would, after keeping what was there in
     * .pc-agent-history. The launcher picks it up within a second.
     */
    fun write(
        name: String,
        content: String,
    ): Result {
        checkName(name)
        val bytes = content.toByteArray()
        if (bytes.size > MAX_WRITE) throw ToolError("that's ${bytes.size} bytes; a file can be at most ${MAX_WRITE shr 20} MiB")
        val (compiled, compileError) = compile(content)
        val restricted = !allowCommands()
        if (restricted) {
            // One that doesn't compile here could on another launcher version, or in another language's strings.
            if (compiled == null) {
                throw ToolError(
                    "not saved: $name doesn't compile, and while commands are off this phone only saves files that do:\n$compileError",
                )
            }
            val refused = guard.refusals(compiled.second, compiled.first)
            if (refused.isNotEmpty()) throw ToolError(refusedMessage("not saved: $name would", refused))
        }
        val target = File(dir, name)
        dir.mkdirs()
        if (!folderInside(dir)) throw ToolError("not saved: the widgets folder isn't where it was")
        val hash = sha256Hex(bytes)
        val before = target.readBytesOrNull()?.let(::sha256Hex)
        // Its own file keeps its values; the user's, and anything that was there before, doesn't hand them over.
        val fresh = restricted && (before == null || before != ledger.written(name))
        val kept =
            synchronized(lock) {
                if (fresh) ledger.markFresh(name, hash)
                val kept = keep(target)
                val tmp = File(dir, ".pc-agent-${UUID.randomUUID()}.tmp")
                try {
                    tmp.outputStream().use {
                        it.write(bytes)
                        it.fd.sync()
                    }
                    // Replaces a link in the file's place rather than writing through it.
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    tmp.delete()
                }
                // Only what it wrote while held to the guard is its own: what it wrote while trusted may hold command output.
                if (restricted) ledger.recordWritten(name, hash) else ledger.forget(name)
                quiet[name] = System.nanoTime() + quietMs * 1_000_000
                generation++
                kept
            }
        val kind = OwlKind.of(content).word
        val notes =
            listOfNotNull(
                if (kept) "the version it replaced is in $HISTORY/$name/ on the phone" else null,
                if (fresh && before != null) "it starts without the values $name kept, since that version wasn't written from here" else null,
            ).joinToString("") { " ($it)" }
        val result =
            if (compiled != null) {
                "saved $name ($kind, ${bytes.size} bytes, sha256 $hash)$notes; it compiles here. " +
                    "Call check_widget to see it as the phone draws it."
            } else {
                "saved $name ($kind, ${bytes.size} bytes)$notes, but it doesn't compile:\n$compileError"
            }
        return Result(result, isError = compiled == null)
    }

    /** Deletes widgets/[name], keeping a copy in .pc-agent-history. Its saved values on the phone go with it. */
    fun delete(name: String): Result {
        checkName(name)
        val target = File(dir, name)
        // A link goes, not what it points to.
        if (!Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target.toPath())) throw ToolError("$name: no such file")
        val link = Files.isSymbolicLink(target.toPath())
        val kept =
            synchronized(lock) {
                val kept = keep(target)
                if (!target.delete()) throw ToolError("can't delete $name")
                ledger.forget(name)
                generation++
                kept
            }
        return Result(
            when {
                link -> "deleted $name, which was a link (what it pointed to is still there)"
                kept -> "deleted $name; a copy is in $HISTORY/$name/ on the phone"
                else -> "deleted $name (no copy could be kept)"
            },
        )
    }

    /**
     * What `owl-check` prints for [name], and its pictures: waits until the launcher has loaded the file as saved,
     * then for the picture of its tile (and of its screen, or an app's whole view) drawn with live data.
     */
    fun check(name: String): Result {
        checkName(name)
        val file = File(dir, name)
        if (Files.isSymbolicLink(file.toPath())) throw ToolError("$name is a link; these tools don't follow links")
        val bytes = file.readBytesOrNull() ?: throw ToolError("$name: no such file")
        val hash = sha256Hex(bytes)
        if (!allowCommands()) {
            // Its picture is drawn with live data: the values the user's widgets keep, what their commands print.
            if (ledger.written(name) != hash) {
                throw ToolError(
                    "$name wasn't written from here, or was changed since: while commands are off, a PC agent checks only the files it " +
                        "wrote, since the picture shows what a widget keeps. Save it with write_widget to check it; it then starts without those values.",
                )
            }
            val refused = compile(bytes.decodeToString()).first?.let { guard.refusals(it.second, it.first) }.orEmpty()
            if (refused.isNotEmpty()) throw ToolError(refusedMessage("not checked: $name would", refused))
        }
        val check = File(checkDir, "$name.txt")
        if (!waitFor(loadWaitMs) { field(check, "sha256") == hash }) {
            return Result("$name: the launcher hasn't loaded this version yet (is it running?); call check_widget again", isError = true)
        }
        val errors = File(errorDir, "$name.txt").readTextOrEmpty().trimEnd()
        if (field(check, "result") != "ok") {
            return Result("$name: doesn't compile\n$errors".trimEnd(), isError = true)
        }
        val out = StringBuilder("$name: compiles\n")
        val images = ArrayList<Image>()
        val previewLine = Regex("""^preview(-skipped)? $hash( |$)""", RegexOption.MULTILINE)
        // The launcher draws files that change while it runs; for anything else, ask.
        if (!previewLine.containsMatchIn(check.readTextOrEmpty())) want(name, hash)
        if (waitFor(previewWaitMs) { previewLine.containsMatchIn(check.readTextOrEmpty()) }) {
            val text = check.readTextOrEmpty()
            val preview = line(text, "preview $hash ")
            if (preview != null) {
                val (cells, px) = preview.split(' ').let { it.getOrElse(0) { "?" } to it.getOrElse(1) { "?" } }
                out.append("picture 1: its tile, $cells cells, $px px, with live data\n")
                picture(File(previewDir, "$name.png"))?.let(images::add)
            } else {
                out.append("no picture: ${line(text, "preview-skipped $hash ").orEmpty()}\n")
            }
            line(text, "screen $hash ")?.let { px ->
                out.append("picture ${images.size + 1}: ${if (preview == null) "its view" else "its screen { }"}, opened full screen, $px px, with live data\n")
                picture(File(previewDir, "$name.screen.png"))?.let(images::add)
            }
        } else {
            out.append("no picture yet (the launcher may be busy); call check_widget again for it\n")
        }
        // Problems while running: values that failed to evaluate, commands that failed.
        val problems = File(errorDir, "$name.txt").readTextOrEmpty().trimEnd()
        if (problems.isNotEmpty()) out.append(problems.lines().joinToString("\n") { "  $it" }).append('\n')
        synchronized(lock) { reported[name] = failingOf(problems) }
        val failing = problems.lines().any { it.isNotBlank() && !it.startsWith("runtime: warning:") }
        return Result(out.toString().trimEnd(), images, isError = failing)
    }

    /** What [changes] reads of a file written from here: its state ([GONE], [UNREADABLE] or its hash) and its problems. */
    private class Look(
        val name: String,
        val ours: String,
        val state: String,
        val failing: Set<String>,
    )

    /**
     * What happened on the phone, since the call before, to the files written from here: one changed or deleted
     * there, or one with a problem while it runs that wasn't there before (unless check_widget has shown it, or the
     * file was just written). Each is told once it has held for two calls, and in the launcher's own words with only
     * the file's name: what a widget prints can come from any server, and is no message to pass on. The first call
     * only looks.
     */
    fun changes(): List<Change> {
        // The files are read outside the lock: the guest can put a FIFO where one was, and reading it waits for ever.
        // Then only this waits, not every tool.
        val (since, mine) =
            synchronized(lock) {
                generation to ledger.names().filter { isLoadable(it) && NAME.matches(it) }.mapNotNull { n -> ledger.written(n)?.let { n to it } }
            }
        val looks =
            mine.map { (name, ours) ->
                val file = File(dir, name)
                val state = if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) GONE else file.readBytesOrNull()?.let(::sha256Hex) ?: UNREADABLE
                // Its problems count only while its version from here is the one the launcher has loaded.
                val loaded = state == ours && field(File(checkDir, "$name.txt"), "sha256") == ours
                Look(name, ours, state, if (loaded) failingOf(File(errorDir, "$name.txt").readTextOrEmpty()) else emptySet())
            }
        return synchronized(lock) {
            if (generation != since) return@synchronized emptyList()
            val now = System.nanoTime()
            val names = looks.map { it.name }.toSet()
            for (m in listOf(seen, quiet, reported)) m.keys.retainAll(names)
            val out = ArrayList<Change>()
            for (look in looks) {
                val name = look.name
                val s = seen[name]
                if (s == null) {
                    // What was failing before anyone listened isn't news; a file written since is.
                    seen[name] = Seen(look.state, look.state, if (watching) emptySet() else look.failing)
                    continue
                }
                if (look.state != s.last) {
                    s.last = look.state
                } else if (look.state != s.told) {
                    s.told = look.state
                    when (look.state) {
                        look.ours -> {}
                        GONE -> out += Change(name, "deleted", "$name (on the phone, written through these tools) was deleted on the phone.")
                        else -> out += Change(name, "changed", "$name (on the phone, written through these tools) was changed on the phone since. $ON_PHONE read_file tool.")
                    }
                }
                if (look.failing.isEmpty()) {
                    // Gone: when they come back, they're news again.
                    s.toldFailing = emptySet()
                    reported.remove(name)
                }
                val new = look.failing - s.toldFailing - reported[name].orEmpty()
                when {
                    new.isEmpty() -> s.newFailing = false
                    !s.newFailing -> s.newFailing = true
                    now >= (quiet[name] ?: now) && s.failingAt.let { it == null || now - it >= failingEveryMs * 1_000_000 } -> {
                        s.toldFailing = s.toldFailing + look.failing
                        s.newFailing = false
                        s.failingAt = now
                        out += Change(name, "failing", "$name (on the phone, written through these tools) has new problems while running there. $ON_PHONE check_widget tool.")
                    }
                }
            }
            watching = true
            out
        }
    }

    /**
     * A copy of [target] in .pc-agent-history/<name>/, keeping the last [HISTORY_KEPT] of each and [HISTORY_BYTES] in
     * all; whether one was made. A link isn't copied.
     */
    private fun keep(target: File): Boolean {
        if (!plain(target)) return false
        val folder = File(historyDir, target.name)
        return runCatching {
            folder.mkdirs()
            if (!folderInside(historyDir) || !folderInside(folder)) return false
            // Named by the time, a millisecond on for each one already taken, so they sort oldest first.
            var at = System.currentTimeMillis()
            while (Files.exists(File(folder, "$at.owl").toPath(), LinkOption.NOFOLLOW_LINKS)) at++
            // A link put in the file's place since copies as a link, not what it points to.
            Files.copy(target.toPath(), File(folder, "$at.owl").toPath(), LinkOption.NOFOLLOW_LINKS)
            kept(folder).sortedByDescending { stamp(it) }.drop(HISTORY_KEPT).forEach { it.delete() }
            // And the oldest of all of them, past what the phone should spend on them.
            val all = historyDir.listFiles().orEmpty().filter { folderInside(it) }.flatMap(::kept).sortedByDescending { stamp(it) }
            var bytes = 0L
            for (f in all) {
                bytes += f.length()
                if (bytes > HISTORY_BYTES && f.parentFile != folder) f.delete()
            }
            true
        }.getOrDefault(false)
    }

    private fun kept(folder: File) = folder.listFiles().orEmpty().filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }

    private fun stamp(f: File) = f.name.removeSuffix(".owl").toLongOrNull() ?: 0

    /** Asks the launcher for [name]'s preview, as owl-check does: its hash in .check/<file>.want, in one rename. */
    private fun want(
        name: String,
        hash: String,
    ) {
        runCatching {
            checkDir.mkdirs()
            if (!folderInside(checkDir)) return
            val tmp = File(checkDir, ".pc-agent-${UUID.randomUUID()}.tmp")
            try {
                tmp.writeText(hash + "\n")
                Files.move(tmp.toPath(), File(checkDir, "$name.want").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                tmp.delete()
            }
        }
    }

    /**
     * Whether [f] is a file of its own in the widgets folder. The guest can put links here (in place of a file,
     * of .check or .preview), and nothing is read or written through one: it could point at the app's own files.
     */
    private fun plain(f: File): Boolean =
        runCatching {
            Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS) && f.toPath().toRealPath().startsWith(root)
        }.getOrDefault(false)

    private fun folderInside(d: File): Boolean =
        runCatching {
            Files.isDirectory(d.toPath(), LinkOption.NOFOLLOW_LINKS) && d.toPath().toRealPath().startsWith(root)
        }.getOrDefault(false)

    /** Where the widgets folder was the first time: the guest could later put a link in its place, or in a folder's. */
    private val root: Path by lazy { dir.toPath().toRealPath() }

    private fun File.readTextOrEmpty() = readBytesOrNull()?.decodeToString().orEmpty()

    private fun File.readBytesOrNull() = if (plain(this)) noFollow(toPath(), MAX_READ) else null

    /**
     * [p]'s bytes, opened so a link put in its place since isn't followed (O_NOFOLLOW), up to [max]; null when it
     * can't be read or is larger.
     */
    private fun noFollow(
        p: Path,
        max: Long,
    ): ByteArray? =
        runCatching {
            Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS).use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 shl 10)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) return@use null
                }
                out.toByteArray()
            }
        }.getOrNull()

    /** A picture the launcher drew, only if it is one: what's returned as an image could be any file's bytes. */
    private fun picture(f: File): Image? = f.readBytesOrNull()?.takeIf { it.size > PNG.size && it.copyOf(PNG.size).contentEquals(PNG) }?.let(fitImage)

    /** [content] compiled for the phone's language, with its syntax tree; or why it doesn't compile. */
    private fun compile(content: String): Pair<Pair<Program, WidgetAst>?, String?> =
        try {
            (Compiler.compile(content) to Parser.parse(content)) to null
        } catch (e: CompileError) {
            null to Source(content).format(e)
        } catch (e: StackOverflowError) {
            null to "too deeply nested"
        } catch (e: RuntimeException) {
            null to e.toString()
        }

    private fun refusedMessage(
        lead: String,
        refused: List<String>,
    ) = "$lead " + refused.joinToString("; ") +
        ". This phone lets a PC agent save and check only widgets that show things, keep their own values and use servers on the " +
        "internet: no commands, files, notifications or apps, nothing done as the user, no servers on the phone or a home network. " +
        "Write it without those, or ask the phone's owner to allow commands in settings › PC agent."

    /** The rest of the first "<key> ..." line of a check file. */
    private fun field(
        file: File,
        key: String,
    ): String? = line(file.readTextOrEmpty(), "$key ")

    private fun checkName(name: String) {
        if (!isLoadable(name) || !NAME.matches(name)) {
            throw ToolError("$name: a widget or app is a .owl file directly in the widgets folder, named with letters, digits, '.', '-' and '_'")
        }
    }

    /** A path in the widgets folder that may be read: nothing hidden but the stock files, nothing outside it. */
    private fun readable(path: String): File {
        if ('\u0000' in path || '\\' in path || path.startsWith("/")) throw ToolError("$path: give a path inside the widgets folder, like docs/apps.md")
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw ToolError("$path: only the widgets folder can be read")
        val stock = parts.size == 1 && STOCK.matches(parts[0])
        if (!stock && parts.any { it.startsWith(".") }) throw ToolError("$path: hidden files are the launcher's own")
        if (parts.lastOrNull()?.endsWith(".secret") == true) throw ToolError("$path: secrets stay on the phone")
        val f = if (parts.isEmpty()) dir else File(dir, parts.joinToString("/"))
        if (!f.exists()) throw ToolError("$path: no such file")
        // The guest can put links here: whatever one points to must be in the widgets folder too.
        val real = runCatching { f.toPath().toRealPath() }.getOrElse { throw ToolError("$path: can't be read") }
        if (!real.startsWith(root)) throw ToolError("$path: points outside the widgets folder")
        return real.toFile()
    }

    private fun waitFor(
        ms: Long,
        ok: () -> Boolean,
    ): Boolean {
        val end = System.nanoTime() + ms * 1_000_000
        while (true) {
            if (runCatching(ok).getOrDefault(false)) return true
            if (System.nanoTime() >= end) return false
            Thread.sleep(pollMs)
        }
    }

    companion object {
        /** Where earlier versions of what an agent overwrote or deleted are kept; hidden, so nothing loads them. */
        const val HISTORY = ".pc-agent-history"
        const val HISTORY_KEPT = 20

        /** What history may take in all; past it, the oldest copies of other files go. */
        const val HISTORY_BYTES = 32L shl 20
        const val MAX_READ = 2L shl 20
        const val MAX_WRITE = 1 shl 20
        private val PNG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        private val NAME = Regex("""[A-Za-z0-9_][A-Za-z0-9._-]{0,119}""")
        private val STOCK = Regex("""\.stock-[a-z]+\.owl""")

        /** [changes]' states of a file besides its hash: not there, or not a plain file it can read. */
        private const val GONE = ""
        private const val UNREADABLE = "?"

        /** An agent told of a change set off to find the file on its own computer. */
        private const val ON_PHONE = "It's not on this computer: to see it, use this server's"

        /**
         * The problems in a file's .errors text that fail, by where they happened ("runtime: post", "runtime: source
         * w"): what follows quotes a server's reply or a command's output, which can differ each time. Not warnings,
         * nor that it draws slowly.
         */
        private fun failingOf(problems: String): Set<String> =
            problems.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("runtime: warning:") && !it.startsWith("runtime: drawing takes") }.map { line ->
                val second = line.indexOf(": ", line.indexOf(": ") + 2)
                if (line.indexOf(": ") >= 0 && second > 0) line.substring(0, second) else line
            }.toSet()

        /** What the launcher loads: a *.owl file directly in the folder, not hidden (WidgetEngine's widget files). */
        fun isLoadable(name: String) = name.endsWith(".owl") && !name.startsWith(".") && '/' !in name

        private fun line(
            text: String,
            prefix: String,
        ): String? = text.lineSequence().firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
    }
}
