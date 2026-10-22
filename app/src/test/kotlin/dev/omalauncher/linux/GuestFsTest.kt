package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class GuestFsTest {
    @get:Rule val dir = TemporaryFolder()

    private val root by lazy { dir.newFolder("arch") }

    /** Bound over /tmp, like Proot's binds. */
    private val tmp by lazy { dir.newFolder("tmp") }

    /** The launcher's own files, beside the rootfs, as widget-state is. */
    private val own by lazy { dir.newFolder("widget-state").also { File(it, "x.owl.json").writeText("{\"token\":\"t\"}") } }

    private fun hostOf(path: String) = if (path == "/tmp" || path.startsWith("/tmp/")) File(tmp, path.removePrefix("/tmp")) else File(root, path.removePrefix("/"))

    private val fs by lazy {
        own
        val visible = listOf(root, tmp).map { it.canonicalFile }
        GuestFs(::hostOf) { real -> visible.any { real.startsWith(it) } }
    }

    private fun file(
        path: String,
        text: String,
    ) = File(root, path.removePrefix("/")).also {
        it.parentFile!!.mkdirs()
        it.writeText(text)
    }

    private fun link(
        path: String,
        target: String,
    ) {
        val at = File(root, path.removePrefix("/"))
        at.parentFile!!.mkdirs()
        Files.createSymbolicLink(at.toPath(), File(target).toPath())
    }

    private fun read(path: String) = runCatching { fs.host(path).readText() }.getOrNull()

    /** Whatever the guest can read, a widget can: any folder, links absolute or relative, and binds. */
    @Test fun readsWhatTheGuestSees() {
        file("/usr/share/backgrounds/a.png", "png")
        file("/root/notes.txt", "notes")
        file("/usr/share/zoneinfo/Europe/Berlin", "tz")
        link("/etc/localtime", "/usr/share/zoneinfo/Europe/Berlin")
        link("/root/pics", "../usr/share/backgrounds")
        File(tmp, "out.json").writeText("{}")
        assertEquals("png", read("/usr/share/backgrounds/a.png"))
        assertEquals("notes", read("/root/notes.txt"))
        assertEquals("tz", read("/etc/localtime"))
        assertEquals("png", read("/root/pics/a.png"))
        assertEquals("{}", read("/tmp/out.json"))
        assertEquals("notes", read("/root/../root/./notes.txt"))
    }

    /** `..` stops at /, and a link out of the rootfs lands back inside it, as in the guest. */
    @Test fun neverLeavesTheGuest() {
        assertEquals(null, read("/../widget-state/x.owl.json"))
        assertEquals(null, read("/../../../widget-state/x.owl.json"))
        link("/root/esc", "../../../widget-state")
        assertEquals(null, read("/root/esc/x.owl.json"))
        link("/root/abs", own.path)
        assertEquals(null, read("/root/abs/x.owl.json"))
        // A path Android would follow itself (here a bind gone wrong, or a link swapped in after resolving) fails.
        val leaky = GuestFs({ File(own, "x.owl.json") }) { real -> real.startsWith(root.canonicalFile) }
        assertTrue(runCatching { leaky.host("/x") }.exceptionOrNull() is IOException)
    }

    /** A loop fails, rather than handing the link to Android to follow. */
    @Test fun loopsFail() {
        link("/a", "/b")
        link("/b", "/a")
        assertTrue(runCatching { fs.host("/a/x") }.exceptionOrNull() is IOException)
        // A listing shows such a link as broken rather than failing.
        val a = fs.list("/", showHidden = true).single { it.name == "a" }
        assertTrue(a.isLink)
        assertFalse(a.isDir)
    }

    /** Acting on a link itself (deleting it) works even when it points out of the guest. */
    @Test fun linksThemselvesStayUsable() {
        link("/root/abs", own.path)
        fs.delete("/root/abs")
        assertFalse(Files.exists(File(root, "root/abs").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(File(own, "x.owl.json").isFile)
    }

    /** Phone build: /sdcard bound over a rootfs without a sdcard directory, as when phone storage is turned on late. */
    @Test fun aBoundFolderIsListedWithoutItsMountPoint() {
        val rootfs = dir.newFolder("arch2").apply { File(this, "usr").mkdir() }
        val storage = dir.newFolder("storage").apply { File(this, "Download").mkdir() }
        val fs =
            GuestFs(
                hostOf = { path -> if (path == "/sdcard" || path.startsWith("/sdcard/")) File(storage, path.removePrefix("/sdcard")) else File(rootfs, path) },
                mountsIn = { dir -> if (dir == "/") listOf("sdcard") else emptyList() },
            )
        val root = fs.list("/", showHidden = true)
        assertEquals(listOf("sdcard", "usr"), root.map { it.name })
        assertTrue(root.first().isDir)
        assertEquals(listOf("Download"), fs.list("/sdcard", showHidden = true).map { it.name })
        // Once the mount point is there too, it's still listed once.
        File(rootfs, "sdcard").mkdir()
        assertEquals(listOf("sdcard", "usr"), fs.list("/", showHidden = true).map { it.name })
    }
}
