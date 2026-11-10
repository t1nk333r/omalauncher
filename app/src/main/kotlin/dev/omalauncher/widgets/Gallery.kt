package dev.omalauncher.widgets

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.R
import dev.omalauncher.SecretBox
import dev.omalauncher.openHttp
import dev.omalauncher.upgrade.writeAtomically
import dev.omalauncher.widgets.lang.OwlKind
import dev.omalauncher.widgets.market.Catalog
import dev.omalauncher.widgets.market.CatalogEntry
import dev.omalauncher.widgets.market.Review
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64

/**
 * Phone build: the owner's own widget gallery, a private GitHub repo laid out like the store's (widgets/<author>/<name>/
 * widget.owl with its preview beside it, faces/…, apps/…, and index.json, faces.json and apps.json listing them), which the
 * launcher writes itself with a fine-grained token. Long-pressing a widget the user wrote or changed uploads it (see
 * [WidgetEngine.canUpload]); the store lists the gallery's beside upstream's and installs them the same way, checked
 * against their SHA-256, so the owner's other phones get them too.
 */
class Gallery(
    private val context: Context,
    private val engine: WidgetEngine,
) : MarketSource {
    /** Sealed like the backup token, and like it outside every backup: another install pastes its own. */
    private val tokenFile = File(context.filesDir, TOKEN_FILE)

    /** Not the store screen's: closing it mustn't stop an upload half-way. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val prefix = "gallery"

    /** The repo, as owner/name on GitHub. */
    val repo = REPO

    var hasToken by mutableStateOf(tokenFile.isFile)
        private set

    /** Widgets, faces and apps, as the store has them. */
    val markets = listOf(OwlKind.Widget, OwlKind.Face, OwlKind.App).map { Market(engine, it, this) }

    fun market(kind: OwlKind) = markets.firstOrNull { it.kind == kind }

    /** Files being uploaded, so a second tap doesn't start another. */
    private val uploading = mutableStateListOf<String>()

    @Volatile private var client: GitHubRepo? = null

    /** Blank forgets it. */
    fun setToken(token: String) {
        val t = token.trim()
        if (t.isEmpty()) {
            tokenFile.delete()
        } else {
            val sealed = SecretBox.seal(t.toByteArray()) ?: throw IOException("can't encrypt the token on this phone")
            writeAtomically(tokenFile, sealed)
        }
        client = null
        hasToken = tokenFile.isFile
    }

    private fun client(): GitHubRepo =
        client ?: run {
            val token =
                tokenFile.takeIf { it.isFile }?.let { SecretBox.unseal(it.readBytes()) }?.decodeToString()
                    ?: throw IOException("no GitHub token yet")
            GitHubRepo(repo, token).also { client = it }
        }

    override fun get(path: String) = client().file(path)

    /** Uploads [spec] on the gallery's own scope, saying how it went in a toast. */
    fun upload(spec: WidgetSpec) {
        val name = spec.file.name
        if (name in uploading) return
        uploading += name
        toast(context.getString(R.string.phone_gallery_uploading, spec.name))
        scope.launch {
            val said =
                try {
                    context.getString(R.string.phone_gallery_uploaded, spec.name, put(spec))
                } catch (e: Exception) {
                    context.getString(R.string.phone_gallery_upload_failed, spec.name, reason(e))
                } finally {
                    uploading -= name
                }
            toast(said)
        }
    }

    /**
     * Puts [spec] in the gallery, its folder there replaced whole, and returns the version it's at now: one more than the
     * gallery had, so the owner's other phones see an update. Its file is then the gallery's install of it. Main thread,
     * where its preview draws.
     */
    private suspend fun put(spec: WidgetSpec): String {
        val market = market(spec.kind) ?: throw IOException("only widgets, faces and apps go in the gallery")
        val github = withContext(Dispatchers.IO) { client() }
        val text = withContext(Dispatchers.IO) { spec.file.readText() }
        // Uploaded before, or installed from here: the same entry again.
        val id = market.installed.values.firstOrNull { i -> i.files.any { it.file == spec.file.name } }?.id
        val folder = GalleryCatalog.folder(spec.kind, id ?: GalleryCatalog.id(spec.file.name, github.owner))
        val review = Review(folder + "${spec.kind.word}.owl", text)
        if (review.program == null) throw IOException(review.errors.firstOrNull() ?: "it doesn't compile")
        val preview = engine.galleryPreview(spec)
        val entry =
            withContext(Dispatchers.IO) {
                var put: CatalogEntry? = null
                github.commit("Upload ${spec.name}") { paths, read ->
                    val entries = read(market.catalog)?.let { Catalog.parse(it.decodeToString(), market.list) }.orEmpty()
                    val e = GalleryCatalog.entry(review, entries, preview != null)
                    put = e
                    // Its old files go, an earlier preview with them; a preview is named by its version, so no store keeps an old one.
                    val files = HashMap<String, ByteArray?>()
                    for (p in paths) if (p.startsWith(folder)) files[p] = null
                    files[review.path] = text.toByteArray()
                    if (preview != null) files[e.preview!!] = preview
                    files[market.catalog] = Catalog.write(GalleryCatalog.upsert(entries, e), Instant.now().toString(), market.list).toByteArray()
                    files
                }
                put!!
            }
        market.adopt(entry, spec.file.name)
        market.refresh()
        return entry.version
    }

    /** Takes [e] out of the gallery, its folder with it; a copy on home stays there, the user's own again. Returns an error, or null. */
    suspend fun remove(
        market: Market,
        e: CatalogEntry,
    ): StoreMessage? {
        val folder = GalleryCatalog.folderOf(e.path) ?: return StoreMessage(R.string.phone_gallery_remove_failed, e.path)
        market.busy[e.id] = R.string.phone_gallery_removing
        try {
            withContext(Dispatchers.IO) {
                client().commit("Remove ${e.name}") { paths, read ->
                    val entries = read(market.catalog)?.let { Catalog.parse(it.decodeToString(), market.list) }.orEmpty()
                    val files = HashMap<String, ByteArray?>()
                    for (p in paths) if (p.startsWith(folder)) files[p] = null
                    files[market.catalog] = Catalog.write(entries.filter { it.id != e.id }, Instant.now().toString(), market.list).toByteArray()
                    files
                }
            }
            market.forget(e.id)
            market.refresh()
            return null
        } catch (x: Exception) {
            return StoreMessage(R.string.phone_gallery_remove_failed, reason(x))
        } finally {
            market.busy.remove(e.id)
        }
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    companion object {
        /** Outside the backup and Android's (backup_rules.xml), as the backup token is. */
        const val TOKEN_FILE = "gallery-token"
        const val REPO = "t1nk333r/omalauncher-gallery"

        fun reason(e: Exception): String =
            when (e) {
                is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException -> "can't reach GitHub"
                else -> e.message ?: e.javaClass.simpleName
            }
    }
}

/** The gallery's catalog, as uploads and removals change it. Plain functions, for tests. */
object GalleryCatalog {
    private val PART = Regex("[a-z0-9][a-z0-9-]{0,38}")
    private val FOLDER = Regex("""(widget|face|app)s/[a-z0-9][a-z0-9-]{0,38}/[a-z0-9][a-z0-9-]{0,38}/""")

    /** A name the store's ids take: lowercase letters, digits and dashes, at most 39, as GitHub names are. */
    fun part(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(39).trimEnd('-').takeIf { PART.matches(it) } ?: "widget"

    /** The id a file gets on its first upload: the repo's [owner], and its name less `.owl` and any store's or gallery's prefix. */
    fun id(
        file: String,
        owner: String,
    ): String {
        val author = part(owner)
        val name = file.removeSuffix(".owl").removePrefix("gallery-").removePrefix("$author-").removePrefix("market-")
        return "$author/${part(name)}"
    }

    /** Where [id]'s files go, as the store lays them out: widgets/<author>/<name>/. */
    fun folder(
        kind: OwlKind,
        id: String,
    ) = "${kind.word}s/$id/"

    /** The folder of an entry's [path], when it's one an upload made; null for anything else, which nothing deletes. */
    fun folderOf(path: String): String? = (path.substringBeforeLast('/', "") + "/").takeIf { FOLDER.matches(it) }

    /** One more than [old]'s, which the gallery wrote: 1, 2, … */
    fun nextVersion(old: CatalogEntry?) = ((old?.version?.toIntOrNull() ?: 0) + 1).toString()

    /** [review]'s entry as uploaded over [entries]: the next version, the day it first came, and a preview beside it when there is one. */
    fun entry(
        review: Review,
        entries: List<CatalogEntry>,
        preview: Boolean,
    ): CatalogEntry {
        val old = entries.firstOrNull { it.id == review.id }
        val version = nextVersion(old)
        val folder = review.path.substringBeforeLast('/')
        return review.entry(if (preview) "$folder/preview-$version.png" else null)
            .copy(version = version, added = old?.added ?: LocalDate.now(ZoneOffset.UTC).toString(), new = false)
    }

    /** [e] first, the last uploaded at the top, in place of the one it replaces. */
    fun upsert(
        entries: List<CatalogEntry>,
        e: CatalogEntry,
    ) = listOf(e) + entries.filter { it.id != e.id }
}

/**
 * The few GitHub REST calls the gallery makes on [repo] (owner/name) with [token], a fine-grained one with Contents read
 * and write there. Blocking; failures are [GitHubError]s with a reason to show.
 */
class GitHubRepo(
    val repo: String,
    private val token: String,
    private val api: String = "https://api.github.com",
) {
    class GitHubError(
        val status: Int,
        message: String,
    ) : IOException(message)

    val owner get() = repo.substringBefore('/')

    /** The repo answered once: a 404 after that is a missing file. */
    @Volatile private var seen = false

    /** [path] on the default branch, or at commit [ref]. A missing file is an IOException starting "HTTP 404", as the store's is. */
    fun file(
        path: String,
        ref: String? = null,
    ): ByteArray {
        val c = open("contents/$path" + (ref?.let { "?ref=$it" } ?: ""), RAW)
        try {
            val status = c.responseCode
            if (status == 200) return c.inputStream.use { it.readBytes() }
            // A repo the token can't see says 404 too, and that's no empty catalog.
            if (status == 404 && visible()) throw IOException("HTTP 404 for $path")
            throw failure(c)
        } finally {
            c.disconnect()
        }
    }

    private fun visible() = seen || runCatching { call("GET", "") }.isSuccess.also { seen = it }

    /**
     * One commit on the default branch, of what [edit] makes of it as it is: given the paths in its tree and a reader for
     * files at its head, it returns the files to write, null deleting one. Starts again when the branch moved meanwhile.
     */
    fun commit(
        message: String,
        edit: (paths: Set<String>, read: (String) -> ByteArray?) -> Map<String, ByteArray?>,
    ) {
        val branch = call("GET", "").getString("default_branch")
        seen = true
        repeat(ATTEMPTS) {
            val head = call("GET", "git/ref/heads/$branch").getJSONObject("object").getString("sha")
            val tree = call("GET", "git/commits/$head").getJSONObject("tree").getString("sha")
            val items = call("GET", "git/trees/$tree?recursive=1").getJSONArray("tree")
            val paths = (0 until items.length()).map { items.getJSONObject(it) }.filter { it.optString("type") == "blob" }.map { it.getString("path") }.toSet()
            val changes = edit(paths) { p -> if (p in paths) file(p, head) else null }
            val entries = JSONArray()
            for ((path, bytes) in changes) {
                val sha: Any =
                    when {
                        bytes != null -> call("POST", "git/blobs", JSONObject().put("content", Base64.getEncoder().encodeToString(bytes)).put("encoding", "base64")).getString("sha")
                        path in paths -> JSONObject.NULL
                        // Deleting what isn't there: GitHub would refuse the tree.
                        else -> continue
                    }
                entries.put(JSONObject().put("path", path).put("mode", "100644").put("type", "blob").put("sha", sha))
            }
            val newTree = call("POST", "git/trees", JSONObject().put("base_tree", tree).put("tree", entries)).getString("sha")
            val commit = call("POST", "git/commits", JSONObject().put("message", message).put("tree", newTree).put("parents", JSONArray().put(head))).getString("sha")
            try {
                // GitHub takes POST for PATCH on a ref, which HttpURLConnection can't send everywhere.
                call("POST", "git/refs/heads/$branch", JSONObject().put("sha", commit).put("force", false))
                return
            } catch (e: GitHubError) {
                // Not a fast-forward: another phone committed meanwhile. Again, from there.
                if (e.status != 422) throw e
            }
        }
        throw IOException("the gallery kept changing while this was saved; try again")
    }

    private fun call(
        method: String,
        endpoint: String,
        body: JSONObject? = null,
    ): JSONObject {
        val c = open(endpoint, JSON)
        try {
            c.requestMethod = method
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            if (c.responseCode !in 200..299) throw failure(c)
            return JSONObject(c.inputStream.use { it.readBytes().decodeToString() })
        } finally {
            c.disconnect()
        }
    }

    private fun open(
        endpoint: String,
        accept: String,
    ): HttpURLConnection =
        openHttp("$api/repos/$repo" + (if (endpoint.isEmpty()) "" else "/$endpoint")).also {
            it.useCaches = false
            it.setRequestProperty("Authorization", "Bearer $token")
            it.setRequestProperty("Accept", accept)
            it.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            it.setRequestProperty("User-Agent", "omalauncher")
        }

    private fun failure(c: HttpURLConnection): GitHubError {
        val status = c.responseCode
        val said = runCatching { c.errorStream?.use { JSONObject(it.readBytes().decodeToString()).optString("message") } }.getOrNull()?.ifEmpty { null }
        val reason =
            when (status) {
                401 -> "the token isn't valid (expired, revoked or mistyped)"
                403 -> "the token can't do that in $repo: give it Contents read and write there" + (said?.let { " ($it)" } ?: "")
                404 -> "$repo isn't there, or the token doesn't cover it"
                else -> "GitHub answered HTTP $status" + (said?.let { ": $it" } ?: "")
            }
        return GitHubError(status, reason)
    }

    private companion object {
        const val ATTEMPTS = 3
        const val JSON = "application/vnd.github+json"
        const val RAW = "application/vnd.github.raw+json"
    }
}
