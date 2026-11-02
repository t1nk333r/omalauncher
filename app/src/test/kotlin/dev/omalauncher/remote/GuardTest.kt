package dev.omalauncher.remote

import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class GuardTest {
    /** example.com is on the internet; home.example.org is a name that points into a home network. */
    private val guard =
        Guard { host ->
            when (host) {
                "example.com" -> listOf(InetAddress.getByAddress(host, byteArrayOf(93, -72, -41, 14)))
                "home.example.org" -> listOf(InetAddress.getByAddress(host, byteArrayOf(10, 0, 0, 40)))
                else -> throw java.net.UnknownHostException(host)
            }
        }

    private fun refusals(body: String): List<String> {
        val src = "widget \"t\" {\n  meta { owl: ${Compiler.LEVEL} }\n$body\n}\n"
        return guard.refusals(Parser.parse(src), Compiler.compile(src))
    }

    private fun allowed(body: String) = assertEquals(body, emptyList<String>(), refusals(body))

    private fun refused(
        body: String,
        why: String,
    ) {
        val r = refusals(body)
        assertTrue("$body: $r", r.any { why in it })
    }

    @Test
    fun allowsWhatOnlyShowsThingsAndUsesTheInternet() {
        allowed("  state n = 0\n  Text { text: \"${'$'}{n}\"; onTap: n = n + 1 }")
        allowed("  source w = http(\"https://example.com/x\") every 10m\n  Text { text: w }")
        allowed("  source f = ws(\"wss://example.com/feed\")\n  Text { text: f }")
        allowed("  Button { text: \"go\"; onTap: post(\"https://example.com/x\", {a: 1}, method: \"PUT\") }")
        allowed("  source b = android.battery\n  Text { text: b }\n  Button { text: \"r\"; onTap: { haptic(); refresh() } }")
        allowed("  source w = http(\"https://example.com/x\") json\n  Image { src: \"https://example.com/i/${'$'}{w.icon}.png\" }")
        allowed("  Image { src: \"app:com.example\" }\n  Image { src: \"\" }")
        allowed("  let base = \"https://example.com/img\"\n  source w = http(\"https://example.com/x\") json\n  Image { src: \"${'$'}{base}/${'$'}{w.id}\" }")
    }

    @Test
    fun refusesCommandsFilesAndPackages() {
        refused("  source m = cmd(\"cat /root/.claude/.credentials.json\") every 5s\n  Text { text: m }", "run or do")
        refused("  source k = file(\"~/.config/omp/key\")\n  Text { text: k }", "read file")
        refused("  source p = cmd(\"pacman -Qq\") every 10m optional\n  Text { text: p }", "once Linux is installed")
        refused("  Terminal { }", "terminal")
        refused("  Button { text: \"x\"; onTap: run(\"rm -rf ~\") }", "run")
    }

    @Test
    fun refusesWhatAndroidKnowsAboutYouAndActingAsYou() {
        refused("  source n = android.notifications\n  Text { text: \"${'$'}{n}\" }", "notifications")
        refused("  source a = android.apps\n  Text { text: \"${'$'}{a}\" }", "android.apps")
        refused("  every 1m { notification(\"clear\") }\n  Text { text: \"x\" }", "notification(...)")
        for (action in listOf("url(\"tel:123\")", "copy(\"rm -rf ~\")", "notify(\"hi\")", "androidApp(\"com.x\")", "torch(\"on\")", "launcher(\"ask\")")) {
            refused("  Button { text: \"x\"; onTap: $action }", "act as you: ${action.substringBefore('(')}")
        }
    }

    @Test
    fun refusesPicturesFromFilesOrValues() {
        refused("  Image { src: \"/sdcard/DCIM/a.jpg\" }", "a file on the phone")
        refused("  Image { src: \"~/secret.png\" }", "a file on the phone")
        refused("  source w = http(\"https://example.com/x\") json\n  Image { src: w.thumb }", "from a value")
        refused("  Canvas { image(\"/sdcard/a.png\", 0, 0, 10, 10) }", "a file on the phone")
        refused("  Button { text: \"x\"; onTap: sound(\"/sdcard/a.ogg\") }", "a file on the phone")
    }

    @Test
    fun refusesServersOnThePhoneOrAHomeNetwork() {
        refused("  source w = http(\"http://192.168.1.1/admin\") every 10m\n  Text { text: w }", "on the phone or a home network")
        refused("  source w = http(\"http://127.0.0.1:8080/x\") every 10m\n  Text { text: w }", "127.0.0.1")
        refused("  source w = http(\"https://localhost/x\") every 10m\n  Text { text: w }", "the phone itself")
        refused("  source w = http(\"https://router.lan/x\") every 10m\n  Text { text: w }", "only a home network")
        refused("  source w = http(\"https://phone.tail1234.ts.net/x\") every 10m\n  Text { text: w }", "the tailnet")
        refused("  source w = http(\"https://home.example.org/x\") every 10m\n  Text { text: w }", "10.0.0.40")
        refused("  source w = http(\"https://100.100.1.2/x\") every 10m\n  Text { text: w }", "home network")
        refused("  source w = http(\"https://nowhere.example/x\") every 10m\n  Text { text: w }", "can't look up")
        // A server that comes from a value could be any of these.
        refused("  state host = \"example.com\"\n  source w = http(\"https://${'$'}{host}/x\") every 10m\n  Text { text: w }", "comes from widget values")
    }

    @Test
    fun knowsWhatIsOnTheInternet() {
        for (public in listOf("93.184.215.14", "1.1.1.1", "2606:4700::1111")) assertTrue(public, Guard.isPublic(InetAddress.getByName(public)))
        for (private in listOf("10.0.0.1", "172.16.0.1", "192.168.0.1", "127.0.0.1", "100.64.0.1", "169.254.1.1", "0.0.0.0", "::1", "fe80::1", "fd7a:115c:a1e0::1", "::ffff:192.168.1.1", "64:ff9b::a00:1")) {
            assertTrue(private, !Guard.isPublic(InetAddress.getByName(private)))
        }
    }
}
