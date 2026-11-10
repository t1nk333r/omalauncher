package dev.omalauncher.widgets

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.OwlKind
import dev.omalauncher.widgets.market.Catalog
import dev.omalauncher.widgets.market.Review
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Base64

class GalleryTest {
    private val widget = "widget \"Clock\" {\n  meta { owl: ${Compiler.LEVEL} }\n  size: 2x1\n  Text { text: \"hi\" }\n}\n"

    @Test
    fun namesAnUploadAfterItsFileAndTheRepoOwner() {
        assertEquals("t1nk333r/clock", GalleryCatalog.id("clock.owl", "t1nk333r"))
        assertEquals("t1nk333r/my-widget", GalleryCatalog.id("My Widget!!.owl", "T1nk333r"))
        // A store's widget changed here keeps its author in the name; one from the gallery is just its name again.
        assertEquals("t1nk333r/simon-weather", GalleryCatalog.id("market-simon-weather.owl", "t1nk333r"))
        assertEquals("t1nk333r/clock", GalleryCatalog.id("gallery-t1nk333r-clock.owl", "t1nk333r"))
        assertEquals("t1nk333r/widget", GalleryCatalog.id("___.owl", "t1nk333r"))
        assertEquals(39, GalleryCatalog.id("a".repeat(60) + ".owl", "t1nk333r").substringAfter('/').length)
        assertEquals("widgets/t1nk333r/clock/", GalleryCatalog.folder(OwlKind.Widget, "t1nk333r/clock"))
    }

    @Test
    fun deletesOnlyFoldersAnUploadMade() {
        assertEquals("widgets/t1nk333r/clock/", GalleryCatalog.folderOf("widgets/t1nk333r/clock/widget.owl"))
        assertEquals("faces/t1nk333r/desk/", GalleryCatalog.folderOf("faces/t1nk333r/desk/face.owl"))
        for (bad in listOf("widgets/clock.owl", "index.json", "widgets/../x/widget.owl", "widgets/t1nk333r/clock/a/widget.owl", "notes/t1nk333r/clock/widget.owl")) {
            assertNull(bad, GalleryCatalog.folderOf(bad))
        }
    }

    @Test
    fun eachUploadIsTheNextVersionAtTheTop() {
        val first = GalleryCatalog.entry(Review("widgets/t1nk333r/clock/widget.owl", widget), emptyList(), preview = true)
        assertEquals("1", first.version)
        assertEquals("widgets/t1nk333r/clock/preview-1.png", first.preview)
        assertEquals(2 to 1, first.size)
        val other = GalleryCatalog.entry(Review("widgets/t1nk333r/other/widget.owl", widget), emptyList(), preview = false)
        val entries = GalleryCatalog.upsert(listOf(first), other)
        val again = GalleryCatalog.entry(Review("widgets/t1nk333r/clock/widget.owl", widget.replace("hi", "hello")), entries, preview = false)
        assertEquals("2", again.version)
        assertEquals(first.added, again.added)
        assertNull(again.preview)
        // What the store reads back: the last uploaded first, each once.
        val read = Catalog.parse(Catalog.write(GalleryCatalog.upsert(entries, again), "now"), "widgets")
        assertEquals(listOf("t1nk333r/clock" to "2", "t1nk333r/other" to "1"), read.map { it.id to it.version })
    }

    private val github = FakeGitHub()

    @After
    fun stop() = github.stop()

    @Test
    fun writesAndDeletesInOneCommit() {
        github.files = mapOf("README.md" to "x", "widgets/o/a/widget.owl" to "old", "widgets/o/a/preview-1.png" to "png")
        val repo = GitHubRepo("o/r", "tok", github.url)
        repo.commit("upload") { paths, read ->
            assertEquals(setOf("README.md", "widgets/o/a/widget.owl", "widgets/o/a/preview-1.png"), paths)
            assertEquals("old", read("widgets/o/a/widget.owl")?.decodeToString())
            assertNull(read("index.json"))
            mapOf(
                "widgets/o/a/widget.owl" to "new".toByteArray(),
                "widgets/o/a/preview-1.png" to null,
                // Not there: left out, or GitHub would refuse the tree.
                "widgets/o/a/gone.png" to null,
                "index.json" to "{}".toByteArray(),
            )
        }
        assertEquals(mapOf("README.md" to "x", "widgets/o/a/widget.owl" to "new", "index.json" to "{}"), github.files)
        assertEquals(1, github.commits)
    }

    @Test
    fun startsAgainWhenAnotherPhoneCommittedMeanwhile() {
        github.files = mapOf("README.md" to "x")
        github.meanwhile = mapOf("index.json" to "theirs")
        var runs = 0
        GitHubRepo("o/r", "tok", github.url).commit("upload") { _, read ->
            runs++
            mapOf("mine.txt" to (read("index.json")?.decodeToString() ?: "none").toByteArray())
        }
        assertEquals(2, runs)
        assertEquals(mapOf("README.md" to "x", "index.json" to "theirs", "mine.txt" to "theirs"), github.files)
    }

    @Test
    fun aMissingFileIsAnEmptyCatalogButAMissingRepoIsNot() {
        github.files = mapOf("index.json" to "{\"format\": 1}")
        assertEquals("{\"format\": 1}", GitHubRepo("o/r", "tok", github.url).file("index.json").decodeToString())
        try {
            GitHubRepo("o/r", "tok", github.url).file("faces.json")
            fail()
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.startsWith("HTTP 404"))
        }
        try {
            GitHubRepo("o/gone", "tok", github.url).file("index.json")
            fail()
        } catch (e: GitHubRepo.GitHubError) {
            assertEquals(404, e.status)
        }
        try {
            GitHubRepo("o/r", "wrong", github.url).file("index.json")
            fail()
        } catch (e: GitHubRepo.GitHubError) {
            assertEquals(401, e.status)
        }
    }

    /** Just enough of GitHub's REST API for one repo, o/r, on branch main, as [GitHubRepo] uses it. */
    private class FakeGitHub {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url = "http://127.0.0.1:${server.address.port}"
        private var n = 0
        private val blobs = HashMap<String, String>()
        private val trees = HashMap<String, Map<String, String>>()
        private val commitTree = HashMap<String, String>()
        private val parent = HashMap<String, String?>()
        private var head = ""

        /** Moves main once, before the next ref update, as another phone's commit would. */
        var meanwhile: Map<String, String>? = null

        /** Ref updates that went through. */
        var commits = 0

        var files: Map<String, String>
            get() = trees.getValue(commitTree.getValue(head))
            set(value) {
                head = commit(tree(value), null)
                commits = 0
            }

        private fun tree(files: Map<String, String>) = "t${++n}".also { trees[it] = files }

        private fun commit(
            tree: String,
            parentSha: String?,
        ) = "c${++n}".also {
            commitTree[it] = tree
            parent[it] = parentSha
        }

        init {
            server.createContext("/") { x -> runCatching { handle(x) }.onFailure { reply(x, 500, it.toString()) } }
            server.start()
        }

        fun stop() = server.stop(0)

        private fun handle(x: HttpExchange) {
            if (x.requestHeaders.getFirst("Authorization") != "Bearer tok") return reply(x, 401, "{\"message\": \"Bad credentials\"}")
            val path = x.requestURI.path
            val query = x.requestURI.query.orEmpty()
            if (!path.startsWith("/repos/o/r")) return reply(x, 404, "{\"message\": \"Not Found\"}")
            val rest = path.removePrefix("/repos/o/r").removePrefix("/")
            val body = if (x.requestMethod == "POST") JSONObject(x.requestBody.readBytes().decodeToString()) else null
            when {
                rest.isEmpty() -> reply(x, 200, JSONObject().put("default_branch", "main"))
                rest == "git/ref/heads/main" -> reply(x, 200, JSONObject().put("object", JSONObject().put("sha", head)))
                rest.startsWith("git/commits/") -> reply(x, 200, JSONObject().put("tree", JSONObject().put("sha", commitTree.getValue(rest.substringAfterLast('/')))))
                rest.startsWith("git/trees/") && x.requestMethod == "GET" -> {
                    val items = JSONArray()
                    for (p in trees.getValue(rest.substringAfterLast('/')).keys) items.put(JSONObject().put("path", p).put("type", "blob"))
                    reply(x, 200, JSONObject().put("tree", items))
                }
                rest.startsWith("contents/") -> {
                    val at = query.substringAfter("ref=", head)
                    val text = trees.getValue(commitTree.getValue(at))[rest.removePrefix("contents/")] ?: return reply(x, 404, "{\"message\": \"Not Found\"}")
                    reply(x, 200, text)
                }
                rest == "git/blobs" -> {
                    val sha = "b${++n}"
                    blobs[sha] = Base64.getDecoder().decode(body!!.getString("content")).decodeToString()
                    reply(x, 201, JSONObject().put("sha", sha))
                }
                rest == "git/trees" -> {
                    val files = trees.getValue(body!!.getString("base_tree")).toMutableMap()
                    val items = body.getJSONArray("tree")
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val p = item.getString("path")
                        if (item.isNull("sha")) {
                            if (files.remove(p) == null) return reply(x, 422, "{\"message\": \"tree.sha is not a valid blob\"}")
                        } else {
                            files[p] = blobs.getValue(item.getString("sha"))
                        }
                    }
                    reply(x, 201, JSONObject().put("sha", tree(files)))
                }
                rest == "git/commits" -> reply(x, 201, JSONObject().put("sha", commit(body!!.getString("tree"), body.getJSONArray("parents").getString(0))))
                rest == "git/refs/heads/main" -> {
                    meanwhile?.let { theirs ->
                        meanwhile = null
                        head = commit(tree(files + theirs), head)
                    }
                    val sha = body!!.getString("sha")
                    if (parent[sha] != head) return reply(x, 422, "{\"message\": \"Update is not a fast forward\"}")
                    head = sha
                    commits++
                    reply(x, 200, JSONObject().put("object", JSONObject().put("sha", sha)))
                }
                else -> reply(x, 404, "{\"message\": \"Not Found\"}")
            }
        }

        private fun reply(
            x: HttpExchange,
            status: Int,
            body: Any,
        ) {
            val bytes = body.toString().toByteArray()
            x.sendResponseHeaders(status, bytes.size.toLong())
            x.responseBody.use { it.write(bytes) }
        }
    }
}
