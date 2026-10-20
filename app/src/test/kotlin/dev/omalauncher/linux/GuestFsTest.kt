package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GuestFsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aBoundFolderIsListedWithoutItsMountPoint() {
        val rootfs = tmp.newFolder("arch").apply { File(this, "usr").mkdir() }
        val storage = tmp.newFolder("storage").apply { File(this, "Download").mkdir() }
        // /sdcard bound over a rootfs without a sdcard directory, as when phone storage is turned on late.
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
