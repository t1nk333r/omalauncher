package dev.omalauncher.remote

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Phone build: the launcher's MCP server, through which a coding agent on a computer (Claude Code, omp) creates,
 * edits and checks widgets and apps ([WidgetTools]) over Wi-Fi or Tailscale. MCP's Streamable HTTP transport at its
 * simplest: one JSON reply to each POST to /mcp, no event streams and no sessions. Every request needs the bearer
 * [token]; only peers on a private network or the tailnet are answered, and never a browser (one sends Origin).
 */
class McpServer(
    private val port: Int,
    private val token: String,
    private val tools: WidgetTools,
    private val serverVersion: String,
) {
    private class HttpError(
        val status: Int,
        message: String,
    ) : IOException(message)

    private class RpcError(
        val code: Int,
        message: String,
    ) : Exception(message)

    private class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
    )

    @Volatile
    private var socket: ServerSocket? = null
    private var pool: ThreadPoolExecutor? = null

    /** One large message is handled at a time: each is held several times over while it's parsed and compiled. */
    private val large = Semaphore(1)

    /** The port it listens on once started (the one asked for, or the one picked for 0). */
    val localPort get() = socket?.localPort

    /** Listens on every interface; throws when the port is taken. */
    @Synchronized
    fun start() {
        if (socket != null) return
        val ss =
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port), BACKLOG)
            }
        val workers = ThreadPoolExecutor(MAX_CONNECTIONS, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, ArrayBlockingQueue(MAX_QUEUED)).apply { allowCoreThreadTimeOut(true) }
        socket = ss
        pool = workers
        thread(name = "pc-agent-mcp", isDaemon = true) {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: IOException) { break }
                try {
                    workers.execute { serve(s) }
                } catch (e: RejectedExecutionException) {
                    runCatching { s.close() }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        pool?.shutdownNow()
        socket = null
        pool = null
    }

    private fun serve(s: Socket) {
        s.use {
            if (!allowedPeer(s.inetAddress) || !allowedInterface(s.localAddress)) return
            s.soTimeout = READ_TIMEOUT_MS
            // A whole request in time, not just each read: one trickling a byte at a time would hold a worker.
            val input = Deadline(BufferedInputStream(s.getInputStream()), System.nanoTime() + HEAD_DEADLINE_MS * 1_000_000)
            val out = s.getOutputStream()
            try {
                val req = readHead(input)
                // Before the token, so a client looking for OAuth (/.well-known/...) learns there's none here.
                if (req.path.substringBefore('?') != PATH) throw HttpError(404, "the MCP endpoint is $PATH")
                // A page in a browser on the same network can reach this too; agents don't send Origin.
                if (req.headers["origin"] != null) throw HttpError(403, "not from a browser")
                val auth = req.headers["authorization"].orEmpty()
                val given = if (auth.startsWith("Bearer ", ignoreCase = true)) auth.substring(7).trim() else ""
                if (!MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) {
                    // Read first, so the agent gets the answer rather than a connection reset.
                    req.headers["content-length"]?.toLongOrNull()?.takeIf { it in 0..MAX_DRAIN }?.let { runCatching { readExactly(input, it.toInt()) } }
                    respond(out, 401, error = "a bearer token is needed: settings › PC agent on the phone shows it", extra = "WWW-Authenticate: Bearer realm=\"omalauncher\"\r\n")
                    return
                }
                if (req.method != "POST") {
                    respond(out, 405, error = "POST JSON-RPC messages to $PATH; this server sends no event stream", extra = "Allow: POST\r\n")
                    return
                }
                req.headers["mcp-protocol-version"]?.let { if (it !in PROTOCOL_VERSIONS) throw HttpError(400, "MCP-Protocol-Version $it isn't one this server speaks (${PROTOCOL_VERSIONS.joinToString()})") }
                // The body gets time by its size: a large widget over a relayed tailnet connection is slow.
                val length = req.headers["content-length"]?.toLongOrNull()
                input.deadline = System.nanoTime() + (BODY_DEADLINE_MS + (length ?: MAX_BODY.toLong()) * 1000 / BODY_BYTES_PER_S) * 1_000_000
                if (req.headers["expect"].equals("100-continue", ignoreCase = true)) {
                    out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                }
                val body = readBody(input, req.headers)
                val heavy = body.size > LARGE_BODY
                if (heavy && !large.tryAcquire(LARGE_WAIT_S, TimeUnit.SECONDS)) throw HttpError(503, "busy with another large message; try again")
                val reply =
                    try {
                        handle(body.decodeToString())
                    } finally {
                        if (heavy) large.release()
                    }
                if (reply == null) respond(out, 202) else respond(out, 200, reply.toByteArray(), "application/json")
            } catch (e: HttpError) {
                runCatching { respond(out, e.status, error = e.message) }
            } catch (e: IOException) {
                // The agent went away or sent nothing in time.
            } catch (e: InterruptedException) {
                // Turned off, or a new token, while a tool waited: the connection just closes.
                Thread.currentThread().interrupt()
            } catch (e: Throwable) {
                // Anything else is this server's fault; on Android it would otherwise end the launcher.
                runCatching { respond(out, 500, error = "the phone failed to answer: $e") }
            }
        }
    }

    /**
     * One JSON-RPC message in, its reply out; null for a notification (or a response), which gets 202 and no body.
     * Internal for tests.
     */
    internal fun handle(body: String): String? {
        if (nesting(body) > MAX_NESTING) return rpcError(null, -32600, "the message nests more than $MAX_NESTING deep")
        val msg =
            try {
                JSONTokener(body).nextValue()
            } catch (e: JSONException) {
                return rpcError(null, -32700, "not JSON: ${e.message}")
            }
        // A batch, as protocol 2025-03-26 has them: each message answered, in one array.
        if (msg is JSONArray) {
            if (msg.length() == 0) return rpcError(null, -32600, "an empty batch")
            val replies = (0 until msg.length()).mapNotNull { i -> (msg.opt(i) as? JSONObject).let { if (it == null) rpcError(null, -32600, "a batch holds JSON-RPC messages") else message(it) } }
            return if (replies.isEmpty()) null else replies.joinToString(",", "[", "]")
        }
        if (msg !is JSONObject) return rpcError(null, -32600, "send a JSON-RPC message")
        return message(msg)
    }

    /** One message's reply: null for a notification or a response. */
    private fun message(msg: JSONObject): String? {
        val method = msg.opt("method") as? String
        if (method == null) {
            // A response to something this server asked: it asks nothing, so there's nothing to do.
            if (msg.has("result") || msg.has("error")) return null
            return rpcError(requestId(msg.opt("id")), -32600, "a request needs a method")
        }
        if (!msg.has("id")) return null
        val id = requestId(msg.opt("id")) ?: return rpcError(null, -32600, "a request's id is a string or an integer")
        val params = msg.optJSONObject("params") ?: JSONObject()
        val result =
            try {
                when (method) {
                    "initialize" -> initialize(params)
                    "ping" -> JSONObject()
                    "tools/list" -> JSONObject().put("tools", toolList())
                    "tools/call" -> call(params)
                    else -> throw RpcError(-32601, "no method $method")
                }
            } catch (e: RpcError) {
                return rpcError(id, e.code, e.message.orEmpty())
            }
        return JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()
    }

    private fun initialize(params: JSONObject): JSONObject {
        val asked = params.optString("protocolVersion")
        return JSONObject()
            .put("protocolVersion", if (asked in PROTOCOL_VERSIONS) asked else PROTOCOL_VERSIONS.first())
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put("serverInfo", JSONObject().put("name", "omalauncher").put("title", "Omalauncher widgets").put("version", serverVersion))
            .put("instructions", INSTRUCTIONS)
    }

    private fun call(params: JSONObject): JSONObject {
        val name = params.opt("name") as? String ?: throw RpcError(-32602, "tools/call needs a tool name")
        val args = params.optJSONObject("arguments") ?: JSONObject()

        fun arg(key: String): String = args.opt(key) as? String ?: throw WidgetTools.ToolError("$name needs \"$key\" (a string)")
        val result =
            try {
                when (name) {
                    "list_widgets" -> tools.list()
                    "read_file" -> tools.read(args.opt("path") as? String ?: "")
                    "write_widget" -> tools.write(arg("name"), arg("content"))
                    "delete_widget" -> tools.delete(arg("name"))
                    "check_widget" -> tools.check(arg("name"))
                    else -> throw RpcError(-32602, "no tool $name")
                }
            } catch (e: WidgetTools.ToolError) {
                WidgetTools.Result(e.message.orEmpty(), isError = true)
            } catch (e: IOException) {
                WidgetTools.Result("$name failed: ${e.message ?: e}", isError = true)
            }
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", result.text))
        for (image in result.images) {
            content.put(JSONObject().put("type", "image").put("data", Base64.getEncoder().encodeToString(image.bytes)).put("mimeType", image.mimeType))
        }
        return JSONObject().put("content", content).put("isError", result.isError)
    }

    /** Fails a read once [deadline] (System.nanoTime) has passed. */
    private class Deadline(
        private val input: InputStream,
        var deadline: Long,
    ) : InputStream() {
        private fun check() {
            if (System.nanoTime() > deadline) throw IOException("the request took too long")
        }

        override fun read(): Int {
            check()
            return input.read()
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            check()
            return input.read(b, off, len)
        }
    }

    companion object {
        const val DEFAULT_PORT = 8765
        const val PATH = "/mcp"

        /** Newest first: the first is the answer to a version this doesn't know. */
        private val PROTOCOL_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26")
        private const val BACKLOG = 16
        private const val MAX_CONNECTIONS = 4
        private const val MAX_QUEUED = 8
        private const val READ_TIMEOUT_MS = 10_000
        private const val HEAD_DEADLINE_MS = 10_000L

        /** The body's time: this much, and a second for each [BODY_BYTES_PER_S]. */
        private const val BODY_DEADLINE_MS = 20_000L
        private const val BODY_BYTES_PER_S = 64L shl 10
        private const val MAX_DRAIN = 64L shl 10
        private const val MAX_HEAD = 16 shl 10
        private const val LARGE_BODY = 256 shl 10
        private const val LARGE_WAIT_S = 20L

        /** Deeper than any message an agent sends; parsing recurses per level, and Android's stack is small. */
        private const val MAX_NESTING = 64

        /** A widget at its largest, as JSON-escaped text, with room for the rest of the message. */
        private const val MAX_BODY = (WidgetTools.MAX_WRITE * 2) + (64 shl 10)

        private val INSTRUCTIONS =
            """
            This is Omalauncher, an Android home-screen launcher, on the user's phone. Its home-screen widgets and its
            apps are .owl files in one folder, written in OWL, a small declarative language; these tools read and write
            that folder. Saved files show up on the phone within a second.

            Before writing one, read README.md (read_file "README.md"): it explains the language, and docs/ next to it
            goes deeper (docs/apps.md for apps, docs/screens.md, docs/panels.md and the others). examples/ holds more
            widgets to learn from. A file's first word says what it is: widget "name" { } is a home-screen tile,
            app "name" { } an app in the app drawer that opens full screen, face "name" { } a whole home page,
            panel "name" { } a view a swipe on home opens, keybar "name" { } the terminal key bar. drawer.owl is the
            app drawer itself; .stock-drawer.owl and .stock-keybar.owl are those files as the launcher ships them.

            After each write_widget, call check_widget: it waits until the phone has loaded the file, then returns its
            errors and a picture of the tile (and of its screen, or an app's whole view) as the phone draws it, with
            live data. You can't see the phone otherwise, so look at the picture to check that text fits and the
            layout works, and fix what's wrong.

            Unless the user allows commands (list_widgets says whether, as commands_allowed), a file from here may only
            show things, keep its own values and use servers on the internet: no Linux commands, files, notifications
            or apps, nothing done as the user, no servers on the phone or a home network. Then write_widget refuses
            anything else and says why, and check_widget checks only files written from here (written_from_here), since
            its pictures show what a widget keeps. drawer.owl and keybar.owl act as the user, so they need commands
            allowed. Overwritten and deleted files are kept on the phone in .pc-agent-history.
            """.trimIndent()

        private fun toolList(): JSONArray {
            fun schema(vararg props: Pair<String, String>) =
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject().apply { for ((k, d) in props) put(k, JSONObject().put("type", "string").put("description", d)) })
                    .put("required", JSONArray(props.map { it.first }))
                    .put("additionalProperties", false)

            fun tool(
                name: String,
                title: String,
                description: String,
                input: JSONObject,
                readOnly: Boolean,
                destructive: Boolean = false,
            ) = JSONObject()
                .put("name", name)
                .put("title", title)
                .put("description", description)
                .put("inputSchema", input)
                .put("annotations", JSONObject().put("readOnlyHint", readOnly).put("destructiveHint", destructive).put("openWorldHint", false))

            val file = "name" to "The file, directly in the widgets folder, like \"50-weather.owl\" or \"notes.owl\""
            return JSONArray()
                .put(
                    tool(
                        "list_widgets", "List widgets and apps",
                        "Lists the .owl files the launcher loads (widgets, apps, faces, panels, the drawer and the key bar), with each one's kind and " +
                            "whether its current version compiles, plus the docs and examples there are, and whether widgets from here may run commands.",
                        schema(), readOnly = true,
                    ),
                ).put(
                    tool(
                        "read_file", "Read a file",
                        "Reads a file in the widgets folder by its path there: a widget or app (\"drawer.owl\"), the language docs (\"README.md\", " +
                            "\"docs/apps.md\"), an example (\"examples/…\"), or a stock file (\".stock-drawer.owl\"). A folder's path lists it; \"\" lists the folder.",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", JSONObject().put("path", JSONObject().put("type", "string").put("description", "Path inside the widgets folder, like \"docs/apps.md\"")))
                            .put("required", JSONArray().put("path"))
                            .put("additionalProperties", false),
                        readOnly = true,
                    ),
                ).put(
                    tool(
                        "write_widget", "Create or replace a widget or app",
                        "Saves a whole .owl file, creating it or replacing it; the previous version is kept on the phone. Returns whether it compiles. " +
                            "A new widget is placed on home by its page and order; a new app appears in the app drawer. Call check_widget next.",
                        schema(file, "content" to "The whole file, in OWL"), readOnly = false,
                    ),
                ).put(
                    tool(
                        "delete_widget", "Delete a widget or app",
                        "Deletes a .owl file: the widget leaves home (with the values it saved) or the app leaves the drawer. A copy is kept on the phone.",
                        schema(file), readOnly = false, destructive = true,
                    ),
                ).put(
                    tool(
                        "check_widget", "Check a widget or app on the phone",
                        "Waits until the launcher has loaded the file as saved, then returns its compile errors, the problems found while running it, " +
                            "and pictures drawn with live data: a widget's tile at its size on home (and its screen { }, if it has one), or an app's or " +
                            "panel's view at the phone's size. Look at them to check the layout.",
                        schema(file), readOnly = true,
                    ),
                )
        }

        /**
         * A request's id as it may go back: a string, or an integer. Android's JSON reads NaN and 1e999 as numbers
         * it then refuses to write.
         */
        private fun requestId(id: Any?): Any? =
            when (id) {
                is String -> id
                is Int, is Long -> id
                is Number -> id.toDouble().takeIf { it.isFinite() && it == kotlin.math.floor(it) && kotlin.math.abs(it) < 9.007199254740992E15 }?.toLong()
                else -> null
            }

        /** How deep [json]'s arrays and objects nest, outside its strings. */
        internal fun nesting(json: String): Int {
            var depth = 0
            var deepest = 0
            var inString = false
            var escaped = false
            for (c in json) {
                when {
                    escaped -> escaped = false
                    inString && c == '\\' -> escaped = true
                    c == '"' -> inString = !inString
                    inString -> {}
                    c == '[' || c == '{' -> deepest = maxOf(deepest, ++depth)
                    c == ']' || c == '}' -> depth--
                }
            }
            return deepest
        }

        private fun rpcError(
            id: Any?,
            code: Int,
            message: String,
        ) = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()

        /**
         * Home networks and the tailnet: loopback, private and link-local addresses, Tailscale's 100.64.0.0/10 and
         * unique local IPv6 (Tailscale's fd7a:115c:a1e0::/48 among them). Not a mobile network's public address.
         */
        fun allowedPeer(a: InetAddress): Boolean {
            if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
            val b = a.address
            return when (a) {
                is Inet4Address -> (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
                is Inet6Address -> (b[0].toInt() and 0xfe) == 0xfc
                else -> false
            }
        }

        /**
         * Network interfaces of mobile data, whose private (10.x) and carrier-NAT (100.64.x) addresses look like a home
         * network's or the tailnet's: a connection arriving on one isn't answered.
         */
        private val MOBILE = listOf("rmnet", "ccmni", "v4-", "seth", "pdp", "ppp", "wwan", "clat", "rev_rmnet")

        fun isMobile(interfaceName: String) = MOBILE.any { interfaceName.startsWith(it) }

        /** Whether a connection to [local] came in over Wi-Fi, Ethernet, Tailscale or USB, not mobile data. */
        fun allowedInterface(local: InetAddress): Boolean {
            if (local.isLoopbackAddress) return true
            // Unknown: the peer's address is checked anyway.
            val name = runCatching { NetworkInterface.getByInetAddress(local)?.name }.getOrNull() ?: return true
            return !isMobile(name)
        }

        private fun readHead(input: InputStream): Request {
            val head = ByteArrayOutputStream()
            var matched = 0
            while (matched < 4) {
                val c = input.read()
                if (c < 0) throw IOException("closed")
                head.write(c)
                if (head.size() > MAX_HEAD) throw HttpError(431, "headers too large")
                matched = if (c == "\r\n\r\n"[matched].code) matched + 1 else if (c == '\r'.code) 1 else 0
            }
            val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
            val start = lines.firstOrNull()?.split(' ') ?: throw HttpError(400, "no request line")
            if (start.size != 3 || !start[2].startsWith("HTTP/1.")) throw HttpError(400, "not HTTP/1.x")
            val headers = HashMap<String, String>()
            for (l in lines.drop(1)) {
                val colon = l.indexOf(':')
                if (colon <= 0) throw HttpError(400, "bad header")
                headers[l.substring(0, colon).trim().lowercase()] = l.substring(colon + 1).trim()
            }
            return Request(start[0], start[1], headers)
        }

        private fun readBody(
            input: InputStream,
            headers: Map<String, String>,
        ): ByteArray {
            if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
                val out = ByteArrayOutputStream()
                while (true) {
                    val size = readLine(input).substringBefore(';').trim().toIntOrNull(16) ?: throw HttpError(400, "bad chunk")
                    if (size < 0 || out.size().toLong() + size > MAX_BODY) throw HttpError(413, "the message is too large")
                    if (size == 0) {
                        // Trailers, if any, up to the blank line.
                        do {
                            val trailer = readLine(input)
                        } while (trailer.isNotEmpty())
                        return out.toByteArray()
                    }
                    out.write(readExactly(input, size))
                    readLine(input)
                }
            }
            val length = headers["content-length"]?.toLongOrNull() ?: throw HttpError(411, "Content-Length is needed")
            if (length < 0 || length > MAX_BODY) throw HttpError(413, "the message is too large")
            return readExactly(input, length.toInt())
        }

        private fun readLine(input: InputStream): String {
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) throw IOException("closed")
                if (c == '\n'.code) return line.toString().trimEnd('\r')
                if (line.length > 1024) throw HttpError(400, "line too long")
                line.append(c.toChar())
            }
        }

        private fun readExactly(
            input: InputStream,
            n: Int,
        ): ByteArray {
            val buf = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = input.read(buf, read, n - read)
                if (r < 0) throw IOException("closed")
                read += r
            }
            return buf
        }

        private fun respond(
            out: OutputStream,
            status: Int,
            body: ByteArray? = null,
            type: String? = null,
            error: String? = null,
            extra: String = "",
        ) {
            val payload = body ?: error?.let { JSONObject().put("error", it).toString().toByteArray() } ?: ByteArray(0)
            val contentType = type ?: if (payload.isNotEmpty()) "application/json" else null
            val head =
                "HTTP/1.1 $status ${REASONS[status] ?: "Error"}\r\n" +
                    (contentType?.let { "Content-Type: $it\r\n" } ?: "") +
                    "Content-Length: ${payload.size}\r\nCache-Control: no-store\r\nConnection: close\r\n" + extra + "\r\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(payload)
            out.flush()
        }

        private val REASONS =
            mapOf(
                200 to "OK", 202 to "Accepted", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found",
                405 to "Method Not Allowed", 411 to "Length Required", 413 to "Content Too Large", 431 to "Request Header Fields Too Large",
                500 to "Internal Server Error", 503 to "Service Unavailable",
            )
    }
}
