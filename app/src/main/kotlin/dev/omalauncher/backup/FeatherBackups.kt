package dev.omalauncher.backup

import dev.omalauncher.openHttp
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

/**
 * Phone build: Feather's device backups (its plan 088), one app's snapshots per device, reached with a
 * backups-only token that can't publish or read the store. [base] is the server, e.g. https://feather.example.
 * Blocking; every call throws [IOException] on a network failure and [FeatherError] on a refusal.
 */
class FeatherBackups(
    base: String,
    private val token: String,
    private val app: String,
) {
    private val root = base.trim().trimEnd('/') + "/api/device-backups/" + enc(app)

    data class Device(
        val id: String,
        val label: String,
        val snapshots: Int,
        val lastBackup: String?,
        val size: Long,
    )

    data class Snapshot(
        val name: String,
        val created: String,
        val size: Long,
        val sha256: String,
    )

    /** Feather answered, and said no: [status] and its message, e.g. a revoked token (401) or backups off (404). */
    class FeatherError(
        val status: Int,
        message: String,
    ) : IOException(message)

    fun devices(): List<Device> =
        get(root).getJSONArray("devices").let { a ->
            (0 until a.length()).map { i ->
                val d = a.getJSONObject(i)
                Device(d.getString("id"), d.optString("label", d.getString("id")), d.optInt("snapshots"), d.optStringOrNull("lastBackup"), d.optLong("size"))
            }
        }

    /** Newest first. */
    fun snapshots(device: String): List<Snapshot> =
        get("$root/${enc(device)}").getJSONArray("snapshots").let { a -> (0 until a.length()).map { snapshot(a.getJSONObject(it)) } }

    /** Keeps [zip] as [device]'s newest snapshot; `unchanged` when it matched the newest one already there. */
    fun upload(
        device: String,
        label: String,
        zip: File,
    ): Pair<Snapshot, Boolean> {
        val boundary = "omalauncher-" + UUID.randomUUID()
        val head =
            buildString {
                append("--$boundary\r\nContent-Disposition: form-data; name=\"label\"\r\n\r\n").append(label.replace("\r", " ").replace("\n", " ")).append("\r\n")
                append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"backup.zip\"\r\nContent-Type: application/zip\r\n\r\n")
            }.toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val c = open("$root/${enc(device)}", readTimeout = 120_000)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.setFixedLengthStreamingMode(head.size + zip.length() + tail.size)
            c.outputStream.use { out ->
                out.write(head)
                zip.inputStream().use { it.copyTo(out) }
                out.write(tail)
            }
            val body = answer(c)
            return snapshot(body.getJSONObject("snapshot")) to body.optBoolean("unchanged")
        } finally {
            c.disconnect()
        }
    }

    /** Downloads [snapshot] of [device] to [to], checking its size and SHA-256 against the listing; [to] is gone on a mismatch. */
    fun download(
        device: String,
        snapshot: Snapshot,
        to: File,
    ) {
        val c = open("$root/${enc(device)}/${enc(snapshot.name)}", readTimeout = 120_000)
        try {
            if (c.responseCode != 200) answer(c)
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        size += n
                        if (size > snapshot.size) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (size != snapshot.size || !sha.equals(snapshot.sha256, ignoreCase = true)) {
                to.delete()
                throw IOException("the backup changed on its way here (size or SHA-256 doesn't match)")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun get(url: String): JSONObject {
        val c = open(url)
        try {
            return answer(c)
        } finally {
            c.disconnect()
        }
    }

    private fun open(
        url: String,
        readTimeout: Int = 30_000,
    ): HttpURLConnection =
        openHttp(url, readTimeout = readTimeout).also {
            it.useCaches = false
            it.setRequestProperty("Authorization", "Bearer $token")
            it.setRequestProperty("Accept", "application/json")
        }

    /** The JSON body of a 200; otherwise a [FeatherError] with Feather's own message when it sent one. */
    private fun answer(c: HttpURLConnection): JSONObject {
        val status = c.responseCode
        val text = (if (status in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (status == 200 && json != null && json.optBoolean("success")) return json
        throw FeatherError(status, json?.optStringOrNull("error") ?: describe(status))
    }

    private fun snapshot(o: JSONObject) = Snapshot(o.getString("name"), o.getString("created"), o.getLong("size"), o.getString("sha256"))

    companion object {
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

        /** What a status means here when Feather sent no message of its own, e.g. a proxy's page. */
        fun describe(status: Int): String =
            when (status) {
                401 -> "the token isn't valid (revoked, expired or mistyped)"
                403 -> "the token isn't a backups token for this app"
                404 -> "this Feather has no device backups (DEVICE_BACKUPS is off, or it's too old)"
                413 -> "the backup is too big for this Feather"
                else -> "Feather answered HTTP $status"
            }
    }
}
