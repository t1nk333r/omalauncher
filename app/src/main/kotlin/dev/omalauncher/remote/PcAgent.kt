package dev.omalauncher.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dev.omalauncher.BuildConfig
import dev.omalauncher.SecretBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Base64

private const val TAG = "pc-agent"

/**
 * Phone build: settings › PC agent. Whether a coding agent on a computer may reach the widgets folder through
 * [McpServer], whether its widgets may run commands, the token it needs, and where it finds the phone. Off until
 * turned on; on, it listens for as long as the launcher runs. Its settings and token are outside every backup
 * (backup_rules.xml): another install gets its own token, and this one's only opens with this install's key.
 */
class PcAgent(
    private val context: Context,
    widgetsDir: File,
) {
    sealed interface Status {
        data object Off : Status

        data class Listening(
            val port: Int,
        ) : Status

        /** The port couldn't be had: another app has it. */
        data class Failed(
            val port: Int,
            val error: String,
        ) : Status

        /** This phone's keystore can't seal a token, or open the one it has: a new token replaces it. */
        data object NoToken : Status
    }

    /** Where the phone is reachable from: its Wi-Fi (or another local network) and Tailscale addresses. */
    data class Address(
        val network: Network,
        val host: String,
    )

    enum class Network { WiFi, Tailscale, Local }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val tokenFile = File(context.filesDir, TOKEN_FILE)
    private val tools = WidgetTools(widgetsDir, allowCommands = { allowCommands }, AgentLedger.of(context.filesDir), fitImage = ::fitImage)
    private var server: McpServer? = null

    /** Starting and stopping happen here, one at a time, off the main thread. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status

    var enabled: Boolean
        get() = prefs.getBoolean(ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(ENABLED, value).apply()
            restart()
        }

    /** Whether a widget written from the computer may run commands, read files or act as the user; read at each write. */
    var allowCommands: Boolean
        get() = prefs.getBoolean(ALLOW_COMMANDS, false)
        set(value) {
            prefs.edit().putBoolean(ALLOW_COMMANDS, value).apply()
        }

    val port get() = prefs.getInt(PORT, McpServer.DEFAULT_PORT)

    /** Listens now if it's on; the launcher's start. */
    fun start() = restart()

    /**
     * The token the agent sends, made the first time; null when the keystore can't keep one, or can't open the one it
     * has. That isn't replaced unasked (a passing keystore error would break the computer's setup): a new token does.
     * Not on the main thread.
     */
    @Synchronized
    fun token(): String? {
        if (tokenFile.isFile) return runCatching { SecretBox.unseal(tokenFile.readBytes())?.decodeToString() }.getOrNull()
        return makeToken()
    }

    /** A new token: the old one stops working at once. */
    @Synchronized
    fun newToken() {
        tokenFile.delete()
        restart()
    }

    private fun makeToken(): String? {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        val sealed = SecretBox.seal(token.toByteArray()) ?: return null
        return runCatching {
            tokenFile.writeBytes(sealed)
            token
        }.getOrNull()
    }

    private fun restart() {
        scope.launch {
            server?.stop()
            server = null
            if (!enabled) {
                _status.value = Status.Off
                return@launch
            }
            val token =
                token() ?: run {
                    _status.value = Status.NoToken
                    return@launch
                }
            val s = McpServer(port, token, tools, BuildConfig.VERSION_NAME)
            _status.value =
                try {
                    s.start()
                    server = s
                    Status.Listening(s.localPort ?: port)
                } catch (e: Exception) {
                    // A failure here would otherwise end the launcher.
                    Log.w(TAG, "can't listen on $port", e)
                    s.stop()
                    Status.Failed(port, e.message ?: e.toString())
                }
        }
    }

    /**
     * A picture small enough for a model to take (Claude's limit is 5 MB): a full screen drawn over a photo
     * wallpaper can be larger as a PNG, so it goes as a JPEG, and smaller if it has to.
     */
    private fun fitImage(png: ByteArray): WidgetTools.Image {
        if (png.size <= MAX_IMAGE) return WidgetTools.Image(png, "image/png")
        return runCatching {
            var bitmap = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return@runCatching null
            repeat(4) {
                val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                if (jpeg.size <= MAX_IMAGE) return@runCatching WidgetTools.Image(jpeg, "image/jpeg")
                bitmap = Bitmap.createScaledBitmap(bitmap, bitmap.width * 3 / 4, bitmap.height * 3 / 4, true)
            }
            null
        }.getOrNull() ?: WidgetTools.Image(png, "image/png")
    }

    /** The phone's addresses a computer at home or on the tailnet can reach, Tailscale's first. */
    fun addresses(): List<Address> =
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !McpServer.isMobile(it.name) }.flatMap { nif ->
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>().filter(McpServer::allowedPeer).map { a ->
                    val b = a.address
                    val network =
                        when {
                            (b[0].toInt() and 0xff) == 100 -> Network.Tailscale
                            nif.name.startsWith("wlan") -> Network.WiFi
                            else -> Network.Local
                        }
                    Address(network, a.hostAddress.orEmpty())
                }
            }.distinct().sortedBy { it.network != Network.Tailscale }
        }.getOrDefault(emptyList())

    fun url(host: String) = "http://$host:$port${McpServer.PATH}"

    /**
     * What adds this phone to Claude Code on the computer, for every project there. The leading space keeps it, and
     * the token in it, out of the shell's history (bash with ignorespace, fish, zsh with hist_ignore_space).
     */
    fun claudeCommand(
        host: String,
        token: String,
    ) = " claude mcp add --scope user --transport http omalauncher ${url(host)} --header \"Authorization: Bearer $token\""

    /** The usual MCP client configuration (.mcp.json and the agents that read its format), for other agents. */
    fun jsonConfig(
        host: String,
        token: String,
    ): String =
        JSONObject()
            .put(
                "mcpServers",
                JSONObject().put(
                    "omalauncher",
                    JSONObject().put("type", "http").put("url", url(host)).put("headers", JSONObject().put("Authorization", "Bearer $token")),
                ),
            ).toString(2)

    companion object {
        /** Outside the backup and Android's (backup_rules.xml), like Feather backups' own settings. */
        const val PREFS = "pc_agent"
        const val TOKEN_FILE = "pc-agent-token"
        private const val ENABLED = "enabled"
        private const val ALLOW_COMMANDS = "allow_commands"
        private const val PORT = "port"

        /** A picture's largest size as sent; Claude takes up to 5 MB, and base64 makes it a third larger. */
        private const val MAX_IMAGE = 3_500_000
    }
}
