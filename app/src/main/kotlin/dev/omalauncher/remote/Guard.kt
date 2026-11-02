package dev.omalauncher.remote

import dev.omalauncher.widgets.lang.Body
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Decl
import dev.omalauncher.widgets.lang.Expr
import dev.omalauncher.widgets.lang.Item
import dev.omalauncher.widgets.lang.Program
import dev.omalauncher.widgets.lang.Stmt
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.WidgetAst
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Phone build: what a widget from a PC agent may do while settings › PC agent keeps commands off: show things,
 * keep its own values, fetch from servers on the internet and draw what they send. Not run anything in Linux, read
 * files or what Android knows about the user, or act as them. Every check allows what it lists and refuses the rest,
 * so whatever the language gains later is refused until it's added here.
 *
 * It reads the file twice: the compiler's disclosure ([Program.commands], as the store shows it) for what goes over
 * the network, and the file itself ([WidgetAst]) for the actions, sources and pictures, every language's texts too.
 */
class Guard(
    /** A server's addresses; the system's resolver, or a fake one in tests. */
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) {
    /** What [program], compiled from [ast], would do that it may not; empty when it may be saved and checked. */
    fun refusals(
        ast: WidgetAst,
        program: Program,
    ): List<String> {
        val out = LinkedHashSet<String>()
        val hosts = HashMap<String, String?>()
        for (c in program.commands) disclosure(c, hosts)?.let(out::add)
        program.requires.values.flatten().distinct().takeIf { it.isNotEmpty() }?.let { out += "install packages: " + it.joinToString(", ") }
        Walk(ast, out).body(ast.body)
        return out.toList()
    }

    /** Null when [c], one line of the disclosure, is a request to a server on the internet. */
    private fun disclosure(
        c: String,
        hosts: HashMap<String, String?>,
    ): String? {
        if (c.startsWith(Compiler.IF_LINUX)) return "run once Linux is installed: " + c.removePrefix(Compiler.IF_LINUX)
        val what = WEB.firstOrNull { c.startsWith("$it ") } ?: return "run or do: $c"
        val url = c.removePrefix("$what ")
        val host =
            FIXED_HOST.find(url)?.groupValues?.get(2)?.lowercase()?.trimEnd('.')
                ?: return "$what to a server that comes from widget values ($url): write the server out, and put values into the URL with enc(...)"
        val why = hosts.getOrPut(host) { privateHost(host) } ?: return null
        return "$what to $host, $why"
    }

    /** Why [host] isn't a server on the internet, or null when it is: one on the phone or a home network could be read from here. */
    private fun privateHost(host: String): String? {
        val literal = IPV4.matches(host) || host.startsWith('[') || ':' in host
        if (!literal) {
            if (host == "localhost" || host.endsWith(".localhost")) return "which is the phone itself"
            if ('.' !in host || PRIVATE_SUFFIXES.any { host.endsWith(it) }) return "a name only a home network or the tailnet has"
        }
        val addresses =
            runCatching { if (literal) listOf(InetAddress.getByName(host.removePrefix("[").removeSuffix("]"))) else resolve(host) }.getOrNull()
                ?: return "which this phone can't look up right now (try again)"
        if (addresses.isEmpty()) return "which this phone can't look up right now (try again)"
        return addresses.firstOrNull { !isPublic(it) }?.let { if (literal) "on the phone or a home network" else "which is ${it.hostAddress}, on the phone or a home network" }
    }

    /** The checks over the file itself. */
    private class Walk(
        ast: WidgetAst,
        private val out: MutableSet<String>,
    ) {
        /** Top-level lets that are fixed text, and assets: what a picture's src may name. */
        private val lets = ast.body.decls.filterIsInstance<Decl.Let>().associate { it.name to it.value }
        private val assets = ast.body.decls.filterIsInstance<Decl.Asset>().map { it.name }.toSet()

        fun body(b: Body) {
            b.decls.forEach(::decl)
            b.handlers.forEach { h -> h.stmts.forEach(::stmt) }
            b.items.forEach(::item)
        }

        private fun decl(d: Decl) {
            when (d) {
                is Decl.Source -> source(d)
                is Decl.Component -> body(d.body)
                is Decl.Trigger -> d.stmts.forEach(::stmt)
                is Decl.Requires -> if (d.fields.isNotEmpty()) out += "install packages"
                is Decl.State, is Decl.Let, is Decl.Asset, is Decl.Meta, is Decl.Strings -> {}
            }
        }

        private fun source(d: Decl.Source) {
            val e = d.expr
            when {
                e is Expr.Call && e.name in WEB_SOURCES -> {}
                e is Expr.Name && e.name == "clock" -> {}
                e is Expr.Member && (e.e as? Expr.Name)?.name == "android" ->
                    if (e.name !in SAFE_ANDROID) out += "read android.${e.name} (${ANDROID_WHAT[e.name] ?: "what Android knows"})"
                e is Expr.Call -> out += "read ${e.name}(...) (source ${d.name})"
                else -> out += "use source ${d.name}, which isn't a web request"
            }
        }

        private fun stmt(s: Stmt) {
            when (s) {
                is Stmt.Assign -> (s.value as? Expr.Call)?.takeIf { it.name in STATE_ACTIONS }?.let { action(it) }
                is Stmt.Do -> (s.call as? Expr.Call)?.let(::action) ?: run { out += "do something that isn't an action" }
                is Stmt.If -> {
                    s.then.forEach(::stmt)
                    s.orElse.forEach(::stmt)
                }
                is Stmt.Let -> {}
            }
        }

        private fun action(call: Expr.Call) {
            if (call.name !in SAFE_ACTIONS) {
                out += "act as you: ${call.name}(...)" + (ACTION_WHAT[call.name]?.let { " ($it)" } ?: "")
                return
            }
            if (call.name == "sound") call.args.firstOrNull()?.let { src(it, "a sound") }
        }

        private fun item(i: Item) {
            when (i) {
                is Item.Node -> {
                    if (i.type == "Terminal") out += "run a terminal (Terminal)"
                    if (i.type == "Image") i.body.props.firstOrNull { it.name == "src" }?.let { src(it.value, "an Image") }
                    body(i.body)
                }
                is Item.If -> {
                    body(i.then)
                    i.orElse?.let(::body)
                }
                is Item.For -> body(i.body)
                is Item.Call -> {
                    if (i.call.name == "image") i.call.args.firstOrNull()?.let { src(it, "a Canvas image") }
                    i.body?.let(::body)
                }
                is Item.Let -> {}
            }
        }

        /**
         * A picture or sound loads from the web, the file's assets or Android's icons and album art; anything else is
         * a path to a file, which would show the phone's photos or the agents' files.
         */
        private fun src(
            e: Expr,
            what: String,
            depth: Int = 0,
        ) {
            if (e is Expr.Cond) {
                src(e.a, what, depth)
                src(e.b, what, depth)
                return
            }
            if (e is Expr.Lit && e.value !is Str) return
            if (e is Expr.Name && e.name in assets) return
            val start = start(e, depth)
            if (start != null && (start.isEmpty() && e is Expr.Lit || SAFE_SRC.any { start.startsWith(it) })) return
            out += if (start == null || start.isEmpty()) {
                "load $what from a value (it could be a path to a file): start its src with \"https://\", \"asset:\" or \"app:\""
            } else {
                "load $what from \"$start\", a file on the phone: pictures and sounds load from https:// or the file's assets"
            }
        }

        /** The fixed text [e] starts with, as far as it can be known here; null when nothing of it is. */
        private fun start(
            e: Expr,
            depth: Int,
        ): String? =
            when (e) {
                is Expr.Lit -> (e.value as? Str)?.v
                is Expr.Interp -> e.parts.firstOrNull()?.let { start(it, depth) }
                is Expr.Binary -> if (e.op == "+") start(e.a, depth) else null
                is Expr.Name -> if (depth < 8) lets[e.name]?.let { start(it, depth + 1) } else null
                else -> null
            }
    }

    companion object {
        /** The disclosure's lines for requests (Compiler's requestUrl and webFile). */
        private val WEB = listOf("http GET", "http POST", "http PUT", "http PATCH", "http DELETE", "websocket", "image GET", "sound GET", "font GET")

        /** The URL's scheme and its host, written out (Compiler.FIXED_HOST's form). */
        private val FIXED_HOST = Regex("""^(https?|wss?)://([A-Za-z0-9.-]+|\[[0-9A-Fa-f:.]+])(:[0-9]+)?(?=[/?#]|$)""")
        private val IPV4 = Regex("""[0-9]+(\.[0-9]+){0,3}""")

        /** Names that only resolve at home or on the tailnet (MagicDNS), or point at the phone. */
        private val PRIVATE_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".intranet", ".corp", ".localdomain", ".home.arpa", ".arpa", ".ts.net")

        private val WEB_SOURCES = setOf("http", "ws")

        /** What Android tells a widget about the phone, not the person: battery, volume, network, what's playing. */
        private val SAFE_ANDROID = setOf("battery", "device", "media", "volume", "system", "network", "power", "alarm", "modes", "modifiers")

        private val ANDROID_WHAT =
            mapOf(
                "notifications" to "your notifications, their titles and text",
                "apps" to "your installed apps",
                "recent" to "the apps you opened lately",
                "drawer" to "the launcher's apps",
                "speech" to "what you say",
                "agents" to "the coding agents' sessions",
                "sim" to "the SIM and its number",
            )

        /** Actions that change only the widget itself: what it shows, its values, a request to the web, a sound. */
        private val SAFE_ACTIONS = setOf("refresh", "screen", "close", "haptic", "sound", "post", "send")

        /** Actions that put their answer into a state: `x = post(...)`. */
        private val STATE_ACTIONS = setOf("run", "post", "shortcuts")

        private val ACTION_WHAT =
            mapOf(
                "run" to "runs a command", "terminal" to "runs a command in a terminal", "app" to "runs a Linux app",
                "url" to "opens any link or app", "androidApp" to "opens an app", "open" to "starts an agent or app",
                "copy" to "puts text on the clipboard", "notify" to "posts a notification", "share" to "shares text",
                "notification" to "reads, answers or clears your notifications", "ask" to "asks a coding agent",
                "appAction" to "acts on an app", "workApps" to "pauses work apps", "pressKey" to "presses keys",
                "typeText" to "types for you", "latch" to "holds keys down", "pickImages" to "pastes your photos",
                "speech" to "listens through the microphone", "shortcuts" to "reads an app's shortcuts",
                "dnd" to "changes Do Not Disturb", "volume" to "changes the volume", "torch" to "turns the torch on",
                "media" to "controls what's playing", "launcher" to "works the launcher", "settings" to "opens Android's settings",
                "drawerGroup" to "changes the drawer's groups",
            )

        /** The beginnings of a src that isn't a file: the web, the file's assets, Android's icons and album art. */
        private val SAFE_SRC = listOf("https://", "asset:", "app:", "icon:", "res:", "media:")

        /** Whether [a] is on the internet: not the phone, a home network, the tailnet, link-local or multicast. */
        fun isPublic(a: InetAddress): Boolean {
            if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isMulticastAddress) return false
            val b = a.address.map { it.toInt() and 0xff }
            return when (a) {
                // 0/8, the tailnet's and carrier-grade NAT's 100.64/10, 192.0.0/24 and the benchmarking 198.18/15.
                is Inet4Address -> !(b[0] == 0 || (b[0] == 100 && b[1] in 64..127) || (b[0] == 192 && b[1] == 0 && b[2] == 0) || (b[0] == 198 && b[1] in 18..19))
                // Unique local (fc00::/7, the tailnet's fd7a:115c:a1e0::/48 too) and IPv4-compatible or NAT64 addresses.
                is Inet6Address -> !((b[0] and 0xfe) == 0xfc || b.take(12).all { it == 0 } || (b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b))
                else -> false
            }
        }
    }
}
