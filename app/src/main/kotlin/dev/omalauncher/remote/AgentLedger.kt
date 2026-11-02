package dev.omalauncher.remote

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Phone build: what a PC agent wrote, kept where Linux can't reach it (filesDir/pc-agent): each file's hash as the
 * agent last saved it, and the files the launcher starts without the values the version before them kept
 * ([WidgetTools] marks them, WidgetEngine takes the mark). A widget the agent didn't write could hold the user's
 * notes or a command's output: in its place, the agent's version would otherwise be handed them, and show them.
 */
class AgentLedger(
    private val dir: File,
) {
    private val written get() = File(dir, "written")
    private val fresh get() = File(dir, "fresh")

    /** The hash of [name] as the agent last saved it; null when it didn't, or not since it was deleted. */
    fun written(name: String): String? = read(File(written, name))

    fun recordWritten(
        name: String,
        hash: String,
    ) = put(File(written, name), hash)

    /** Before [name] is saved as [hash]: that version starts without the values of the one before it. */
    fun markFresh(
        name: String,
        hash: String,
    ) = put(File(fresh, name), hash)

    /** Whether [name], now [hash], was marked by [markFresh]; the mark is used up. */
    fun takeFresh(
        name: String,
        hash: String,
    ): Boolean {
        val f = File(fresh, name)
        if (read(f) != hash) return false
        f.delete()
        return true
    }

    fun forget(name: String) {
        File(written, name).delete()
        File(fresh, name).delete()
    }

    private fun read(f: File): String? = runCatching { f.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()

    private fun put(
        f: File,
        value: String,
    ) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${UUID.randomUUID()}.tmp")
        try {
            tmp.writeText(value)
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.delete()
        }
    }

    companion object {
        /** In the app's own files: Linux sees only the widgets folder. */
        fun of(filesDir: File) = AgentLedger(File(filesDir, "pc-agent"))
    }
}
