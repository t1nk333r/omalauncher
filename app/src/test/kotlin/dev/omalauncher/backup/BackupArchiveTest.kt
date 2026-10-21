package dev.omalauncher.backup

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun phone(): File {
        val files = tmp.newFolder("files")
        mapOf(
            "widgets/10-today.owl" to "today",
            "widgets/drawer.owl" to "drawer",
            "widgets/market-clock.owl" to "clock",
            "widgets/.market.json" to "{}",
            "widgets/.stock-drawer.owl" to "stock",
            "widgets/.errors/x.txt" to "cache",
            "widgets/.removed/old.owl" to "trash",
            "widgets/examples/a.owl" to "example",
            "widget-state/10-today.owl.json" to "{\"v\":1}",
            "widget-state/10-today.owl.secret" to "sealed",
            "style/style.toml" to "theme = 'x'",
            "style/defaults.toml" to "defaults",
            "style/errors.txt" to "",
            "home/layout.toml" to "[[page]]",
            "home/errors.txt" to "",
            "app-icons/ab.png" to "png",
            "app-icons/ab.png.key" to "a:pkg",
            "themes/mine/colors.toml" to "c",
            "themes/mine/.omalauncher-source" to "https://example.com/mine",
            "themes/mine/.git/HEAD" to "ref",
            "backgrounds/mine/image-1.jpg" to "jpg",
            "launcher-managed.json" to "{\"v\":1}",
            "managed.json" to "{}",
            "rootfs-steps.json" to "{}",
            "setup-agents" to "claude",
            "arch/etc/hostname" to "phone",
            "omarchy/themes/x" to "release",
        ).forEach { (path, text) -> File(files, path).apply { parentFile.mkdirs() }.writeText(text) }
        return files
    }

    private fun pack(
        files: File,
        prefs: Map<String, Map<String, Any?>> = PREFS,
    ): ByteArray {
        val manifest = JSONObject().put("format", 1).put("package", "dev.example")
        return ByteArrayOutputStream().also { BackupArchive.pack(it, files, manifest, prefs) }.toByteArray()
    }

    @Test fun carriesTheUsersFilesAndNotLinuxSecretsOrCaches() {
        assertEquals(
            listOf(
                "app-icons/ab.png",
                "app-icons/ab.png.key",
                "backgrounds/mine/image-1.jpg",
                "home/layout.toml",
                "launcher-managed.json",
                "style/style.toml",
                "themes/mine/.omalauncher-source",
                "themes/mine/colors.toml",
                "widget-state/10-today.owl.json",
                "widgets/.market.json",
                "widgets/10-today.owl",
                "widgets/drawer.owl",
                "widgets/examples/a.owl",
                "widgets/market-clock.owl",
            ),
            BackupArchive.files(phone()),
        )
    }

    @Test fun nothingOutsideTheBackupSetCanComeOutOfOne() {
        for (path in listOf("widgets/a\\b.owl", "themes/x/a\u0000b", "rootfs-steps.json", "setup-agents", "managed.json", "arch/etc/passwd", "omarchy/x", "widget-state/a.owl.secret", "widgets/../../x", "../x", "widgets//x", "widgets/./x")) {
            assertFalse(path, BackupArchive.carried(path))
        }
    }

    @Test fun symlinksAreNotFollowed() {
        val files = phone()
        val outside = tmp.newFolder("outside").also { File(it, "secret.txt").writeText("no") }
        Files.createSymbolicLink(File(files, "themes/link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(files, "widgets/link.owl").toPath(), File(outside, "secret.txt").toPath())
        val listed = BackupArchive.files(files)
        assertTrue(listed.none { "link" in it })
    }

    @Test fun theSameSettingsMakeTheSameBytes() {
        val files = phone()
        val first = pack(files)
        // A file rewritten with the same contents, as the stock ones are at every start.
        File(files, "widgets/drawer.owl").apply { writeText("drawer") }.setLastModified(12_345_000L)
        assertArrayEquals(first, pack(files))
        File(files, "widgets/drawer.owl").writeText("drawer, edited")
        assertFalse(first.contentEquals(pack(files)))
        // Every entry has the one fixed time, not when it was packed: two packs seconds apart match too.
        val zip = tmp.newFile().apply { writeBytes(first) }
        java.util.zip.ZipFile(zip).use { z -> assertTrue(z.entries().toList().all { it.timeLocal == java.time.LocalDateTime.of(2000, 1, 1, 0, 0) }) }
    }

    @Test fun unpacksWhatItPacked() {
        val zip = tmp.newFile("b.zip").apply { writeBytes(pack(phone())) }
        val out = BackupArchive.unpack(zip, tmp.newFolder("staging"))
        assertEquals("dev.example", out.manifest.getString("package"))
        assertEquals(PREFS, out.prefs)
        assertEquals("drawer", File(out.files, "widgets/drawer.owl").readText())
        assertEquals("https://example.com/mine", File(out.files, "themes/mine/.omalauncher-source").readText())
        assertFalse(File(out.files, "widget-state/10-today.owl.secret").exists())
        assertFalse(File(out.files, "rootfs-steps.json").exists())
    }

    private fun zipOf(vararg entries: Pair<String, String>) =
        tmp.newFile().apply {
            ZipOutputStream(outputStream()).use { z ->
                for ((name, text) in entries) {
                    z.putNextEntry(ZipEntry(name))
                    z.write(text.toByteArray())
                }
            }
        }

    @Test fun stagesWhatTheRestartApplies() {
        val zip = tmp.newFile("b.zip").apply { writeBytes(pack(phone())) }
        val staging = tmp.newFolder("staging")
        BackupArchive.unpack(zip, staging)
        // After the restart only the staging directory is left to read.
        assertEquals(PREFS, BackupArchive.stagedPrefs(staging))
        assertEquals("dev.example", JSONObject(File(staging, "manifest.json").readText()).getString("package"))
    }

    @Test fun refusesWhatIsNotALauncherBackup() {
        val manifest = "manifest.json" to """{"format":1,"package":"dev.example"}"""
        val prefs = "prefs.json" to "{}"
        val bad =
            listOf(
                // No settings: put back as none, they'd clear the user's.
                zipOf(manifest, "files/widgets/a.owl" to "x"),
                zipOf(manifest, prefs, "files/widgets/a\\b.owl" to "x"),
                zipOf(manifest, "prefs.json" to " ".repeat(5 * 1024 * 1024)),
                zipOf("files/rootfs-steps.json" to "{}", manifest, prefs),
                zipOf("files/setup-agents" to "x", manifest, prefs),
                zipOf("files/../escape" to "x", manifest, prefs),
                zipOf("files/widgets/../../escape" to "x", manifest, prefs),
                zipOf("/abs" to "x", manifest, prefs),
                zipOf("other.txt" to "x", manifest, prefs),
                zipOf("files/widgets/a.owl" to "x"),
                zipOf("manifest.json" to """{"format":99}"""),
            )
        for (zip in bad) {
            val staging = tmp.newFolder()
            try {
                BackupArchive.unpack(zip, staging)
                fail("accepted ${zip.name}")
            } catch (_: IOException) {
            }
            assertFalse(File(staging.parentFile, "escape").exists())
        }
    }

    @Test fun replacesTheDirectoriesAndKeepsThisInstallsSecretsForWidgetsStillThere() {
        val files = phone()
        File(files, "widget-state/gone.owl.secret").writeText("sealed")
        File(files, "themes/local-only/colors.toml").apply { parentFile.mkdirs() }.writeText("x")
        val staged = tmp.newFolder("staged")
        File(staged, "widgets/10-today.owl").apply { parentFile.mkdirs() }.writeText("today, restored")
        File(staged, "widget-state/10-today.owl.json").apply { parentFile.mkdirs() }.writeText("{\"v\":2}")
        File(staged, "launcher-managed.json").writeText("{\"v\":1,\"data\":{}}")
        val old = File(tmp.root, "old")

        BackupArchive.replaceFiles(files, staged, old).keepSecrets()

        assertEquals("today, restored", File(files, "widgets/10-today.owl").readText())
        assertFalse(File(files, "widgets/drawer.owl").exists())
        assertEquals("{\"v\":2}", File(files, "widget-state/10-today.owl.json").readText())
        assertEquals("sealed", File(files, "widget-state/10-today.owl.secret").readText())
        assertFalse(File(files, "widget-state/gone.owl.secret").exists())
        // Replaced whole: a theme the backup didn't have is gone, and so is style/ (it comes back from the APK).
        assertFalse(File(files, "themes").exists())
        assertFalse(File(files, "style").exists())
        assertEquals("{\"v\":1,\"data\":{}}", File(files, "launcher-managed.json").readText())
        // Linux and the rest are never touched.
        assertEquals("phone", File(files, "arch/etc/hostname").readText())
        assertEquals("{}", File(files, "rootfs-steps.json").readText())
        assertEquals("drawer", File(old, "widgets/drawer.owl").readText())
    }

    @Test fun aFailedReplaceMovesEverythingBack() {
        val files = phone()
        val staged = tmp.newFolder("staged")
        File(staged, "widgets/a.owl").apply { parentFile.mkdirs() }.writeText("new")
        val old = File(tmp.root, "old")
        // home can't be moved aside: something is already there.
        File(old, "home/x").apply { parentFile.mkdirs() }.writeText("in the way")
        try {
            BackupArchive.replaceFiles(files, staged, old)
            fail("expected a failure")
        } catch (_: IOException) {
        }
        assertEquals("drawer", File(files, "widgets/drawer.owl").readText())
        assertFalse(File(files, "widgets/a.owl").exists())
        assertEquals("theme = 'x'", File(files, "style/style.toml").readText())
        assertEquals("[[page]]", File(files, "home/layout.toml").readText())
        assertEquals("new", File(staged, "widgets/a.owl").readText())
    }

    @Test fun undoingAFinishedSwapPutsEverythingBack() {
        // As when the settings won't write after the files went in.
        val files = phone()
        val staged = tmp.newFolder("staged")
        File(staged, "widgets/a.owl").apply { parentFile.mkdirs() }.writeText("new")
        File(staged, "style/style.toml").apply { parentFile.mkdirs() }.writeText("restored")
        val swap = BackupArchive.replaceFiles(files, staged, File(tmp.root, "old"))
        assertEquals("restored", File(files, "style/style.toml").readText())
        assertTrue(swap.undo())
        assertEquals("theme = 'x'", File(files, "style/style.toml").readText())
        assertEquals("drawer", File(files, "widgets/drawer.owl").readText())
        assertEquals("sealed", File(files, "widget-state/10-today.owl.secret").readText())
        assertFalse(File(files, "widgets/a.owl").exists())
        assertEquals("new", File(staged, "widgets/a.owl").readText())
    }

    /** Every file under [dir] with its contents, for comparing whole trees. */
    private fun tree(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readText() }

    @Test fun recoveryPutsThePhoneBackWhereverTheSwapStopped() {
        // Cut off after widgets went both ways and style went aside, before anything else.
        val files = phone()
        val before = tree(files)
        val existed = BackupArchive.existing(files).toSet()
        assertTrue("backgrounds" in existed)
        val old = File(tmp.root, "old").apply { mkdirs() }
        val staged = tmp.newFolder("staged")
        File(staged, "widgets/a.owl").apply { parentFile.mkdirs() }.writeText("restored")
        assertTrue(File(files, "widgets").renameTo(File(old, "widgets")))
        assertTrue(File(staged, "widgets").renameTo(File(files, "widgets")))
        assertTrue(File(files, "style").renameTo(File(old, "style")))

        assertTrue(BackupArchive.recover(files, old, File(tmp.root, "aside"), existed))
        assertEquals(before, tree(files))
        assertEquals("restored", File(tmp.root, "aside/widgets/a.owl").readText())
    }

    @Test fun recoveryAfterAFinishedSwapRemovesWhatWasNotThereBefore() {
        val files = phone()
        File(files, "backgrounds").deleteRecursively()
        val before = tree(files)
        val existed = BackupArchive.existing(files).toSet()
        val staged = tmp.newFolder("staged")
        File(staged, "backgrounds/mine/new.jpg").apply { parentFile.mkdirs() }.writeText("new")
        File(staged, "widgets/a.owl").apply { parentFile.mkdirs() }.writeText("restored")
        val old = File(tmp.root, "old")
        BackupArchive.replaceFiles(files, staged, old).keepSecrets()

        assertTrue(BackupArchive.recover(files, old, File(tmp.root, "aside"), existed))
        assertEquals(before, tree(files))
        // Again changes nothing: a recovery cut off itself just runs again.
        assertTrue(BackupArchive.recover(files, old, File(tmp.root, "aside2"), existed))
        assertEquals(before, tree(files))
    }

    @Test fun undoMovesBackADanglingSymlinkToo() {
        val files = phone()
        File(files, "backgrounds").deleteRecursively()
        Files.createSymbolicLink(File(files, "backgrounds").toPath(), File(tmp.root, "nowhere").toPath())
        val swap = BackupArchive.replaceFiles(files, tmp.newFolder("staged"), File(tmp.root, "old"))
        assertTrue(swap.undo())
        assertTrue(Files.isSymbolicLink(File(files, "backgrounds").toPath()))
    }

    @Test fun aBackupValueOfAnotherTypeIsLeftOut() {
        val (_, put) = BackupArchive.prefChanges("launcher", mapOf("dock" to setOf("a"), "bar_to_web" to true), mapOf("dock" to "a\nb", "bar_to_web" to false))
        assertEquals(mapOf("bar_to_web" to false), put)
    }

    @Test fun prefsReplaceOnlyTheUsersKeys() {
        val current = mapOf("dock" to "a", "hidden_apps" to setOf("x"), "setup_done" to true, "home_role_asked" to true, "gpu_wanted" to true, "linux_stopped" to false)
        val (remove, put) = BackupArchive.prefChanges("launcher", current, mapOf("dock" to "b", "gesture_swipe_up" to "drawer", "setup_done" to false))
        assertEquals(setOf("dock", "hidden_apps"), remove)
        assertEquals(mapOf("dock" to "b", "gesture_swipe_up" to "drawer"), put)
        assertEquals(emptySet<String>() to emptyMap<String, Any?>(), BackupArchive.prefChanges("themes", mapOf("rendered_by" to "1"), mapOf("rendered_by" to "2")))
    }

    @Test fun androidWidgetsStayOnlyWhereTheyAreBound() {
        val items = """{"v":1,"data":[{"id":5,"provider":"a/.W"},{"id":9,"provider":"b/.W"},{"id":12,"provider":"c/.W"}]}"""
        val (kept, dropped) = BackupArchive.boundAppWidgets(items) { id, provider -> id == 5 && provider == "a/.W" || id == 12 && provider == "x/.Other" }
        assertEquals(setOf(5), BackupArchive.appWidgetIds(kept))
        assertEquals(listOf("b/.W", "c/.W"), dropped)
        assertNull(BackupArchive.boundAppWidgets(null) { _, _ -> true }.first)

        val layout = """{"v":1,"data":{"columns":4,"cells":{"appwidget:5":{"x":0},"appwidget:9":{"x":1},"10-today.owl":{"x":2}},"glass":{"appwidget:9":0.5,"10-today.owl":0.4}}}"""
        val data = JSONObject(BackupArchive.withoutOtherAppWidgets(layout, setOf(5))).getJSONObject("data")
        assertEquals(setOf("appwidget:5", "10-today.owl"), data.getJSONObject("cells").keys().asSequence().toSet())
        assertEquals(setOf("10-today.owl"), data.getJSONObject("glass").keys().asSequence().toSet())
    }

    @Test fun prefTypesSurviveTheRoundTrip() {
        assertEquals(PREFS, BackupArchive.decodePrefs(JSONObject(BackupArchive.encodePrefs(PREFS).toString())))
    }

    private companion object {
        val PREFS: Map<String, Map<String, Any?>> =
            mapOf(
                "launcher" to mapOf("dock" to "a\nb", "hidden_apps" to setOf("a:x", "a:y"), "bar_to_web" to true),
                "editor" to mapOf("wrap" to false),
                "widget_grid" to mapOf("layout" to "{\"v\":1,\"data\":{}}"),
                "app_usage" to mapOf("n" to 3, "big" to (1L shl 40), "f" to 1.5f),
            )
    }
}
