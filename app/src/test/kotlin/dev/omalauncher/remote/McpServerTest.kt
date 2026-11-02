package dev.omalauncher.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.Socket

class McpServerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val token = "t0ken-abc"
    @get:Rule
    val app = TemporaryFolder()

    private val tools by lazy {
        WidgetTools(tmp.root, allowCommands = { false }, AgentLedger(app.root), Guard { emptyList() }, loadWaitMs = 200, previewWaitMs = 200, pollMs = 10)
    }

    private val server by lazy { McpServer(0, token, tools, "test", tickMs = 50) }

    @After
    fun stop() = server.stop()

    private fun rpc(
        method: String,
        params: JSONObject = JSONObject(),
        id: Any = 1,
    ) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params).toString()

    private fun result(reply: String?) = JSONObject(reply!!).getJSONObject("result")

    private fun call(
        tool: String,
        args: JSONObject,
    ) = result(server.handle(rpc("tools/call", JSONObject().put("name", tool).put("arguments", args))))

    @Test
    fun negotiatesTheProtocolVersion() {
        val known = result(server.handle(rpc("initialize", JSONObject().put("protocolVersion", "2025-06-18"))))
        assertEquals("2025-06-18", known.getString("protocolVersion"))
        assertTrue(known.getJSONObject("capabilities").has("tools"))
        assertTrue(known.getJSONObject("capabilities").has("logging"))
        assertTrue(known.getJSONObject("capabilities").getJSONObject("experimental").has("claude/channel"))
        assertTrue(known.getString("instructions").contains("check_widget"))
        val unknown = result(server.handle(rpc("initialize", JSONObject().put("protocolVersion", "1999-01-01"))))
        assertEquals("2025-11-25", unknown.getString("protocolVersion"))
    }

    @Test
    fun answersNoNotificationAndRejectsWhatIsNotOneMessage() {
        assertNull(server.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertEquals(-32700, JSONObject(server.handle("{not json")!!).getJSONObject("error").getInt("code"))
        assertEquals(-32600, JSONObject(server.handle("[]")!!).getJSONObject("error").getInt("code"))
        assertEquals(-32601, JSONObject(server.handle(rpc("resources/list"))!!).getJSONObject("error").getInt("code"))
        assertEquals("x", JSONObject(server.handle(rpc("ping", id = "x"))!!).getString("id"))
        // A request without a method, or with an id that can't go back, is invalid; a response needs nothing.
        assertEquals(-32600, JSONObject(server.handle("""{"jsonrpc":"2.0","id":3}""")!!).getJSONObject("error").getInt("code"))
        assertEquals(-32600, JSONObject(server.handle("""{"jsonrpc":"2.0","id":{"a":1},"method":"ping"}""")!!).getJSONObject("error").getInt("code"))
        assertEquals(-32600, JSONObject(server.handle("""{"jsonrpc":"2.0","id":1.5,"method":"ping"}""")!!).getJSONObject("error").getInt("code"))
        assertNull(server.handle("""{"jsonrpc":"2.0","id":3,"result":{}}"""))
        // Nested deeper than any agent's message: refused before parsing, which recurses per level.
        assertEquals(-32600, JSONObject(server.handle("[".repeat(100_000))!!).getJSONObject("error").getInt("code"))
        assertEquals(1, McpServer.nesting("""{"a":"[[[[\"]]]"}"""))
    }

    @Test
    fun answersBatches() {
        val reply = JSONArray(server.handle("[${rpc("ping", id = 1)},{\"jsonrpc\":\"2.0\",\"method\":\"notifications/x\"},${rpc("ping", id = 2)}]"))
        assertEquals(listOf(1, 2), (0 until reply.length()).map { reply.getJSONObject(it).getInt("id") })
        assertNull(server.handle("[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/x\"}]"))
    }

    @Test
    fun listsItsTools() {
        val tools = result(server.handle(rpc("tools/list"))).getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertEquals(listOf("list_widgets", "read_file", "write_widget", "delete_widget", "check_widget"), names)
    }

    @Test
    fun callsTools() {
        val written = call("write_widget", JSONObject().put("name", "a.owl").put("content", "widget \"a\" {\n  Text { text: \"hi\" }\n}\n"))
        assertFalse(written.getBoolean("isError"))
        assertEquals("text", written.getJSONArray("content").getJSONObject(0).getString("type"))
        val read = call("read_file", JSONObject().put("path", "a.owl"))
        assertTrue(read.getJSONArray("content").getJSONObject(0).getString("text").contains("widget \"a\""))
        // A tool's own failure is the model's to read, not a protocol error.
        val missing = call("write_widget", JSONObject().put("name", "b.owl"))
        assertTrue(missing.getBoolean("isError"))
        assertEquals(-32602, JSONObject(server.handle(rpc("tools/call", JSONObject().put("name", "rm_rf")))!!).getJSONObject("error").getInt("code"))
    }

    @Test
    fun returnsPicturesAsImages() {
        val src = "widget \"p\" {\n  Text { text: \"hi\" }\n}\n"
        call("write_widget", JSONObject().put("name", "p.owl").put("content", src))
        val hash = dev.omalauncher.widgets.lang.sha256Hex(src.toByteArray())
        File(tmp.root, ".check").mkdirs()
        File(tmp.root, ".preview").mkdirs()
        File(tmp.root, ".check/p.owl.txt").writeText("sha256 $hash\nresult ok\npreview $hash 2x2 352x352\n")
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3)
        File(tmp.root, ".preview/p.owl.png").writeBytes(png)
        val content = call("check_widget", JSONObject().put("name", "p.owl")).getJSONArray("content")
        assertEquals("image", content.getJSONObject(1).getString("type"))
        assertEquals("image/png", content.getJSONObject(1).getString("mimeType"))
        assertEquals(java.util.Base64.getEncoder().encodeToString(png), content.getJSONObject(1).getString("data"))
    }

    /** One raw HTTP exchange with the running server: its status code and body. */
    private fun http(request: String): Pair<Int, String> =
        Socket("127.0.0.1", server.localPort!!).use { s ->
            s.getOutputStream().write(request.toByteArray())
            s.getOutputStream().flush()
            val reply = s.getInputStream().readBytes().decodeToString()
            reply.substringAfter(' ').substringBefore(' ').toInt() to reply.substringAfter("\r\n\r\n")
        }

    private fun post(
        body: String,
        headers: String = "Authorization: Bearer $token\r\n",
    ) = http("POST /mcp HTTP/1.1\r\nHost: phone\r\nContent-Type: application/json\r\n${headers}Content-Length: ${body.toByteArray().size}\r\n\r\n$body")

    @Test
    fun servesJsonRpcOverHttpBehindTheToken() {
        server.start()
        val (ok, body) = post(rpc("ping"))
        assertEquals(200, ok)
        assertEquals(1, JSONObject(body).getInt("id"))
        assertEquals(202, post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").first)
        assertEquals(401, post(rpc("ping"), headers = "Authorization: Bearer wrong\r\n").first)
        assertEquals(401, post(rpc("ping"), headers = "").first)
        assertEquals(403, post(rpc("ping"), headers = "Authorization: Bearer $token\r\nOrigin: http://evil.example\r\n").first)
        // A GET is the event stream only; nothing else but POST is served.
        assertEquals(406, http("GET /mcp HTTP/1.1\r\nAuthorization: Bearer $token\r\n\r\n").first)
        assertEquals(405, http("DELETE /mcp HTTP/1.1\r\nAuthorization: Bearer $token\r\n\r\n").first)
        // The stream is behind the same checks as a request.
        val accept = "Accept: text/event-stream\r\n"
        assertEquals(401, http("GET /mcp HTTP/1.1\r\n$accept\r\n").first)
        assertEquals(401, http("GET /mcp HTTP/1.1\r\nAuthorization: Bearer wrong\r\n$accept\r\n").first)
        assertEquals(403, http("GET /mcp HTTP/1.1\r\nAuthorization: Bearer $token\r\nOrigin: http://evil.example\r\n$accept\r\n").first)
        assertEquals(400, http("GET /mcp HTTP/1.1\r\nAuthorization: Bearer $token\r\nMCP-Protocol-Version: 1999-01-01\r\n$accept\r\n").first)
        assertEquals(404, http("POST /other HTTP/1.1\r\nAuthorization: Bearer $token\r\nContent-Length: 0\r\n\r\n").first)
        // Before the token: a client looking for OAuth learns there's none, rather than being asked to sign in.
        assertEquals(404, http("GET /.well-known/oauth-protected-resource HTTP/1.1\r\n\r\n").first)
        assertEquals(411, http("POST /mcp HTTP/1.1\r\nAuthorization: Bearer $token\r\n\r\n").first)
        assertEquals(400, post(rpc("ping"), headers = "Authorization: Bearer $token\r\nMCP-Protocol-Version: 1999-01-01\r\n").first)
        assertEquals(200, post(rpc("ping"), headers = "Authorization: Bearer $token\r\nMCP-Protocol-Version: 2025-06-18\r\n").first)
    }

    @Test
    fun saysContinueBeforeALargeBody() {
        server.start()
        val body = rpc("ping", id = 9).toByteArray()
        Socket("127.0.0.1", server.localPort!!).use { s ->
            val out = s.getOutputStream()
            out.write("POST /mcp HTTP/1.1\r\nAuthorization: Bearer $token\r\nExpect: 100-continue\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
            out.flush()
            val input = s.getInputStream()
            val interim = generateSequence { input.read().takeIf { it >= 0 } }.map { it.toChar() }.runningFold("") { a, c -> a + c }.first { it.endsWith("\r\n\r\n") }
            assertTrue(interim, interim.startsWith("HTTP/1.1 100"))
            out.write(body)
            out.flush()
            val reply = input.readBytes().decodeToString()
            assertTrue(reply, reply.startsWith("HTTP/1.1 200"))
        }
    }

    @Test
    fun stoppingWhileAToolWaitsEndsOnlyItsConnection() {
        // On Android, an exception nothing catches on any thread ends the launcher.
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            val tools = WidgetTools(tmp.root, allowCommands = { false }, AgentLedger(app.root), Guard { emptyList() }, loadWaitMs = 10_000, pollMs = 10)
            val slow = McpServer(0, token, tools, "test")
            slow.start()
            tools.write("w.owl", "widget \"w\" {\n  Text { text: \"hi\" }\n}\n")
            val body = rpc("tools/call", JSONObject().put("name", "check_widget").put("arguments", JSONObject().put("name", "w.owl")))
            val client =
                kotlin.concurrent.thread {
                    runCatching {
                        Socket("127.0.0.1", slow.localPort!!).use { s ->
                            s.getOutputStream().write("POST /mcp HTTP/1.1\r\nAuthorization: Bearer $token\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
                            s.getInputStream().readBytes()
                        }
                    }
                }
            // The check is waiting for the launcher when access is turned off.
            Thread.sleep(300)
            slow.stop()
            client.join(5_000)
            Thread.sleep(200)
            assertEquals(emptyList<Throwable>(), uncaught.toList())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    @Test
    fun setsTheLogLevel() {
        assertEquals(0, result(server.handle(rpc("logging/setLevel", JSONObject().put("level", "warning")))).length())
        assertEquals(-32602, JSONObject(server.handle(rpc("logging/setLevel", JSONObject().put("level", "loud")))!!).getJSONObject("error").getInt("code"))
    }

    /** A GET for the event stream, its head read: the socket stays open for the events. */
    private fun stream(version: String = "2025-11-25"): Socket {
        val s = Socket("127.0.0.1", server.localPort!!)
        s.soTimeout = 5_000
        s.getOutputStream().write("GET /mcp HTTP/1.1\r\nAuthorization: Bearer $token\r\nAccept: text/event-stream\r\nMCP-Protocol-Version: $version\r\n\r\n".toByteArray())
        val head = readUntil(s, "\r\n\r\n")
        assertTrue(head, head.startsWith("HTTP/1.1 200") && head.contains("Content-Type: text/event-stream"))
        val primer = readUntil(s, "\n\n")
        if (version == "2025-11-25") {
            // The first event has an id and no data, and says when to come back.
            assertTrue(primer, primer.contains("retry: ") && primer.contains("id: ") && primer.contains("data:\n"))
        } else {
            // An older client would read empty data as a broken message.
            assertEquals(": open\n\n", primer)
        }
        return s
    }

    private fun readUntil(
        s: Socket,
        end: String,
    ): String {
        val got = StringBuilder()
        while (!got.endsWith(end)) {
            val c = s.getInputStream().read()
            if (c < 0) return got.toString()
            got.append(c.toChar())
        }
        return got.toString()
    }

    @Test
    fun streamsWhatHappensOnThePhoneToTheAgentsFiles() {
        server.start()
        tools.write("a.owl", "widget \"a\" {\n  Text { text: \"hi\" }\n}\n")
        // The first look, so the phone's change is news.
        tools.changes()
        stream().use { s ->
            File(tmp.root, "a.owl").writeText("widget \"a\" {\n  Text { text: \"yo\" }\n}\n")
            val events = generateSequence { readUntil(s, "\n\n").takeIf { it.isNotEmpty() } }.take(2).toList()
            val log = JSONObject(events[0].substringAfter("data: "))
            assertEquals("notifications/message", log.getString("method"))
            assertEquals("notice", log.getJSONObject("params").getString("level"))
            val channel = JSONObject(events[1].substringAfter("data: "))
            assertEquals("notifications/claude/channel", channel.getString("method"))
            assertTrue(channel.getJSONObject("params").getString("content").contains("was changed on the phone"))
            assertEquals("changed", channel.getJSONObject("params").getJSONObject("meta").getString("event"))
        }
    }

    @Test
    fun streamsHoldNoWorkerAndTheOldestGoes() {
        server.start()
        val open = (1..5).map { stream(if (it == 5) "2025-06-18" else "2025-11-25") }
        try {
            // Five streams, more than the four workers: a request is still answered.
            assertEquals(200, post(rpc("ping")).first)
            // The fifth closed the first.
            assertEquals(-1, open[0].getInputStream().read())
            server.stop()
            assertEquals(-1, open[4].getInputStream().read())
        } finally {
            open.forEach { it.close() }
        }
    }

    @Test
    fun readsChunkedBodies() {
        server.start()
        val body = rpc("ping", id = 7)
        val chunked = "%x\r\n%s\r\n0\r\n\r\n".format(body.length, body)
        val (status, reply) = http("POST /mcp HTTP/1.1\r\nAuthorization: Bearer $token\r\nTransfer-Encoding: chunked\r\n\r\n$chunked")
        assertEquals(200, status)
        assertEquals(7, JSONObject(reply).getInt("id"))
    }

    @Test
    fun answersOnlyHomeNetworksAndTheTailnet() {
        for (ok in listOf("127.0.0.1", "192.168.1.20", "10.1.2.3", "172.20.0.5", "100.100.1.2", "169.254.1.1", "fd7a:115c:a1e0::1", "fe80::1", "::1")) {
            assertTrue(ok, McpServer.allowedPeer(InetAddress.getByName(ok)))
        }
        for (no in listOf("8.8.8.8", "100.128.0.1", "2001:db8::1", "2a00:1450::1")) {
            assertFalse(no, McpServer.allowedPeer(InetAddress.getByName(no)))
        }
    }
}
