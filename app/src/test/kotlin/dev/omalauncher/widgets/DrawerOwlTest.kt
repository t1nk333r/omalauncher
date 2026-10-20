package dev.omalauncher.widgets

import dev.omalauncher.widgets.lang.Budget
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Env
import dev.omalauncher.widgets.lang.Global
import dev.omalauncher.widgets.lang.Host
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Program
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.WNode
import dev.omalauncher.widgets.lang.bool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The phone's drawer, drawer.owl, run on made-up apps: what it lists, in what order, and what its letter rail, search and picking do. */
class DrawerOwlTest {
    private val program: Program = Compiler.compile(File("src/main/assets/widgets/drawer.owl").readText())

    private fun app(
        name: String,
        kind: String = "android",
        score: Double = 0.0,
        installed: Double = 0.0,
        work: Boolean = false,
        hidden: Boolean = false,
        category: String? = null,
        actions: List<String> = listOf("pin"),
        smart: String? = if (kind == "android") "utilities" else null,
    ): Value =
        Obj(
            mapOf(
                "key" to Str("${kind.first()}:$name"),
                "kind" to Str(kind),
                "name" to Str(name),
                "label" to Str(name),
                "icon" to Str("icon:$name"),
                "hasIcon" to bool(true),
                "initials" to Str(name.take(2)),
                "profile" to (if (work) Str("work") else Null),
                "alias" to Null,
                "paused" to bool(false),
                "actions" to Lst(actions.map(::Str)),
                "score" to Num(score),
                "last" to Null,
                "work" to bool(work),
                "hidden" to bool(hidden),
                "docked" to bool(false),
                "letter" to Str(name.first().uppercaseChar().takeIf { it.isLetter() }?.toString() ?: "#"),
                "installed" to Num(installed),
                "isNew" to bool(false),
                "category" to (category?.let(::Str) ?: Null),
                "smart" to (smart?.let(::Str) ?: Null),
            ),
        )

    /** The drawer running with [apps] and style's drawer settings, as android.drawer gives them. */
    private inner class Drawer(
        apps: List<Value>,
        sort: String = "name",
        sections: Boolean = true,
        suggestions: Boolean = false,
        columns: Int = 2,
        groups: List<Pair<String, List<String>>> = emptyList(),
        work: Boolean = false,
        asks: Boolean = true,
        var query: String = "",
        var selection: List<String>? = null,
        var showHidden: Boolean = false,
        layout: String = "list",
        categories: String = "smart",
        private val linux: Boolean = true,
    ) : Host {
        override val budget = Budget(10_000_000)
        val slots = Array<Value>(program.globals.size) { Null }
        val actions = ArrayList<String>()

        init {
            val d =
                Obj(
                    mapOf(
                        "apps" to Lst(apps),
                        "linux" to Str(if (linux) "installed" else "missing"),
                        "monochrome" to bool(false),
                        "work" to (if (work) Obj(mapOf("paused" to bool(false))) else Null),
                        "updates" to Num(0.0),
                        "style" to
                            Obj(
                                mapOf(
                                    "sort" to Str(sort),
                                    "sections" to bool(sections),
                                    "suggestions" to bool(suggestions),
                                    "letters" to bool(sort == "name"),
                                    "labels" to bool(true),
                                    "columns" to Num(columns.toDouble()),
                                    "icon" to Num(52.0),
                                    "layout" to Str(layout),
                                    "categories" to Str(categories),
                                ),
                            ),
                        "groups" to Lst(groups.map { (n, keys) -> Obj(mapOf("name" to Str(n), "keys" to Lst(keys.map(::Str)))) }),
                        "dock" to Obj(mapOf("shown" to bool(true), "full" to bool(false))),
                        "asks" to bool(asks),
                        "web" to Str("duckduckgo"),
                    ),
                )
            val agents = Obj(mapOf("default" to Obj(mapOf("bin" to Str("omp"))), "installed" to Lst(emptyList())))
            program.globals.forEachIndexed { i, g ->
                when {
                    g is Global.State -> slots[i] = g.init(Env.root(this))
                    g.name == "d" -> slots[i] = d
                    g.name == "ag" -> slots[i] = agents
                }
            }
        }

        override fun read(slot: Int): Value = (program.globals[slot] as? Global.Let)?.value?.invoke(Env.root(this)) ?: slots[slot]

        override fun write(
            slot: Int,
            value: Value,
        ) {
            slots[slot] = value
        }

        override fun theme(name: String): Int? = null

        override fun time() = 0.0

        override fun tile(name: String): Value =
            when (name) {
                "query" -> Str(query)
                "picked" -> selection?.let { keys -> Lst(keys.map(::Str)) } ?: Null
                "showHidden" -> bool(showHidden)
                "linux" -> bool(linux)
                "screenWidth" -> Num(400.0)
                "corners" -> Num(1.0)
                else -> Null
            }

        override fun act(
            name: String,
            args: List<Value>,
        ) {
            actions += "$name ${args.joinToString()}"
        }

        operator fun get(name: String): Value = read(program.globals.indexOfFirst { it.name == name })

        /** What the list shows, top to bottom: a title as "# title", a row as its apps' names. */
        fun rows(): List<String> =
            (this["rows"] as Lst).items.map { r ->
                val f = (r as Obj).fields
                f["title"]?.takeIf { it != Null }?.let { "# $it" } ?: (f["cells"] as Lst).items.joinToString(" ") { (it as Obj).fields["name"].toString() }
            }

        fun names(of: String) = (this[of] as Lst).items.map { (it as Obj).fields["name"].toString() }

        fun rail() = (this["rail"] as Lst).items.map { (it as Obj).fields.let { f -> "${f["label"]}>${f["key"]}" } }

        fun submit() = program.onSubmit!!.run(Env.root(this), listOf(Str(query)))

        fun key(k: String) = program.onKey!!.run(Env.root(this), listOf(Str(k)))

        /** The pages, in order, each as "title: its apps' names". */
        fun pages(): List<String> =
            (this["pages"] as Lst).items.map { pg ->
                val f = (pg as Obj).fields
                "${f["title"]}: " + (f["apps"] as Lst).items.joinToString(" ") { (it as Obj).fields["name"].toString() }
            }

        /** Runs Pages' onPage with [index], as a swipe settling on that page does. */
        fun swipeTo(index: Int) = handler("onPage").run(Env.root(this), listOf(Num(index.toDouble())))

        private fun handler(name: String): dev.omalauncher.widgets.lang.Action {
            fun find(nodes: List<WNode>): WNode.Element? =
                nodes.firstNotNullOfOrNull { n ->
                    when (n) {
                        is WNode.Element -> n.takeIf { name in it.handlers } ?: find(n.children)
                        else -> null
                    }
                }
            return find(program.screen ?: program.root)!!.handlers[name]!!
        }

        /** Runs the list's onSwipe with [way], as a sideways swipe on it does. */
        fun swipe(way: String) {
            fun find(nodes: List<WNode>): WNode.Element? =
                nodes.firstNotNullOfOrNull { n ->
                    when (n) {
                        is WNode.Element -> n.takeIf { "onSwipe" in it.handlers } ?: find(n.children)
                        else -> null
                    }
                }
            // A panel is a screen: its nodes are the screen's.
            find(program.screen ?: program.root)!!.handlers["onSwipe"]!!.run(Env.root(this), listOf(Str(way)))
        }
    }

    private val btop = app("btop", "linux", installed = 0.0)
    private val alacritty = app("Alacritty", "linux")
    private val cava = app("cava", "linux")

    @Test fun sortsByNameIgnoringCase() {
        assertEquals(listOf("Alacritty", "btop", "cava"), Drawer(listOf(cava, btop, alacritty)).names("ordered"))
    }

    @Test fun sortsByUseThenName() {
        val apps = listOf(app("Alacritty", "linux"), app("btop", "linux", score = 1.0), app("cava", "linux", score = 3.0))
        assertEquals(listOf("cava", "btop", "Alacritty"), Drawer(apps, sort = "used").names("ordered"))
    }

    @Test fun newestFirstAndAppsThatDontSayByNameUnder() {
        val apps = listOf(app("cava"), app("Signal", installed = 200.0), app("btop"), app("Maps", installed = 300.0))
        assertEquals(listOf("Maps", "Signal", "btop", "cava"), Drawer(apps, sort = "new").names("ordered"))
    }

    @Test fun sectionsAreTitlesAndRowsOfColumns() {
        val apps = listOf(alacritty, btop, cava, app("Signal"), app("Maps"))
        assertEquals(listOf("# linux", "Alacritty btop", "cava", "# android", "Maps Signal"), Drawer(apps).rows())
        // Without headings, one list in the drawer's order.
        assertEquals(listOf("Alacritty btop", "cava Maps", "Signal"), Drawer(apps, sections = false).rows())
    }

    @Test fun mostUsedComesFirstAndNeverHidden() {
        val apps = listOf(app("Signal", score = 2.0), app("Maps", score = 5.0), app("Camera"), app("Secret", score = 9.0, hidden = true))
        assertEquals(listOf("# most used", "Maps Signal", "# android", "Camera Maps", "Signal"), Drawer(apps, suggestions = true).rows())
    }

    @Test fun anAppInAGroupIsListedThereNotUnderItsHeading() {
        val apps = listOf(app("Signal"), app("Maps"), app("Camera"))
        val drawer = Drawer(apps, groups = listOf("chat" to listOf("a:Signal"), "empty" to emptyList()))
        // An empty group keeps its title, for its rename and ungroup.
        assertEquals(listOf("# chat", "Signal", "# empty", "# android", "Camera Maps"), drawer.rows())
    }

    @Test fun hiddenAppsAreListedOnlyWhileShown() {
        val apps = listOf(app("Signal"), app("Secret", hidden = true))
        assertEquals(listOf("Signal"), Drawer(apps).names("shown"))
        assertEquals(listOf("Secret", "Signal"), Drawer(apps, showHidden = true).names("ordered"))
        assertEquals(1.0, (Drawer(apps)["hiddenCount"] as Num).v, 0.0)
    }

    @Test fun theRailJumpsToTheFirstRowWithALetter() {
        val apps = listOf(app("Camera"), app("Maps"), app("Calendar"), app("7zip"), app("btop", "linux"))
        // Rows: android:0 (7zip, Calendar), android:1 (Camera, Maps). Linux's section isn't on the rail; '#' comes last.
        assertEquals(listOf("C>android:0", "M>android:1", "#>android:0"), Drawer(apps).rail())
        // Sorted by use, the letters wouldn't be in order: no rail.
        assertEquals(emptyList<String>(), Drawer(apps, sort = "used").rail())
        assertEquals(emptyList<String>(), Drawer(apps, query = "ca").rail())
    }

    @Test fun swipingSidewaysTurnsTheTab() {
        val apps = listOf(app("Signal"), app("Slack", work = true))
        val drawer = Drawer(apps, work = true)
        assertEquals(listOf("# android", "Signal"), drawer.rows())
        drawer.swipe("left")
        assertEquals("work", drawer["tab"].toString())
        assertEquals(listOf("# work apps", "Slack"), drawer.rows())
        drawer.swipe("right")
        assertEquals("personal", drawer["tab"].toString())
        // Without a work profile there's no other tab.
        val alone = Drawer(listOf(app("Signal")))
        alone.swipe("left")
        assertEquals("personal", alone["tab"].toString())
    }

    @Test fun enterOpensAStrongMatchElseAsksOrSearchesTheWeb() {
        val apps = listOf(app("Signal"), app("Maps"))
        val strong = Drawer(apps, query = "signal")
        strong.submit()
        assertEquals(listOf("open a:Signal"), strong.actions)
        // A sentence: the agent; without one to ask, the web.
        val sentence = Drawer(apps, query = "what is the weather")
        sentence.submit()
        assertEquals(listOf("launcher ask"), sentence.actions)
        val web = Drawer(apps, query = "what is the weather", asks = false)
        web.submit()
        assertEquals(listOf("launcher web"), web.actions)
        // Nothing while apps are picked: the picks stay.
        val picking = Drawer(apps, query = "signal", selection = listOf("a:Maps"))
        picking.submit()
        assertEquals(emptyList<String>(), picking.actions)
    }

    @Test fun upAndTabMoveThePickAwayFromTheBar() {
        val drawer = Drawer(listOf(app("Signal"), app("Slack")), query = "gna")
        // The ask row, the web's, then the apps best first.
        assertEquals(-1.0, (drawer["chosen"] as Num).v, 0.0)
        drawer.key("up")
        assertEquals(-2.0, (drawer["chosen"] as Num).v, 0.0)
        drawer.key("tab")
        assertEquals(0.0, (drawer["chosen"] as Num).v, 0.0)
        drawer.key("down")
        drawer.key("shift-tab")
        drawer.key("down")
        assertEquals(-1.0, (drawer["chosen"] as Num).v, 0.0)
    }

    @Test fun pickedAppsOfferHideUnhideOrBoth() {
        val apps = listOf(app("foot", "linux"), app("btop", "linux", hidden = true), app("Slack", work = true))
        fun offers(vararg keys: String) =
            Drawer(apps, selection = keys.toList(), showHidden = true).let { d ->
                listOf("canHide", "canShow", "canGroup").filter { d[it].truthy }
            }
        assertEquals(listOf("canHide", "canGroup"), offers("l:foot"))
        assertEquals(listOf("canShow", "canGroup"), offers("l:btop"))
        assertEquals(listOf("canHide", "canShow", "canGroup"), offers("l:foot", "l:btop"))
        // Groups are the personal tab's.
        assertEquals(listOf("canHide"), offers("a:Slack"))
        assertFalse(Drawer(apps)["picking"].truthy)
        assertNull((Drawer(apps)["pickedApps"] as Lst).items.firstOrNull())
        assertTrue(Drawer(apps, selection = listOf("l:foot"))["picking"].truthy)
    }

    @Test fun pickedAndroidAppsCanBeUninstalledTogether() {
        val apps = listOf(app("foot", "linux"), app("Signal", actions = listOf("pin", "info", "uninstall")), app("Phone", actions = listOf("pin", "info")))
        assertTrue(Drawer(apps, selection = listOf("l:foot", "a:Signal"))["canUninstall"].truthy)
        // A Linux app and a system app (no uninstall in its menu): nothing to uninstall.
        assertFalse(Drawer(apps, selection = listOf("l:foot", "a:Phone"))["canUninstall"].truthy)
    }

    @Test fun groupSimilarIsOfferedWhileTwoAppsOfAKindAreUngrouped() {
        val apps = listOf(app("Signal", category = "social"), app("WhatsApp", category = "social"), app("Maps", category = "maps"), app("Secret", category = "maps", hidden = true))
        val drawer = Drawer(apps)
        assertTrue(drawer["canGroupSimilar"].truthy)
        val android = (drawer["rows"] as Lst).items.map { (it as Obj).fields }.first { it["title"].toString() == "android" }
        assertTrue(android["similar"]!!.truthy)
        // Grouped already, or alone in its kind (a hidden one doesn't count): nothing left to offer.
        assertFalse(Drawer(apps, groups = listOf("chat" to listOf("a:Signal", "a:WhatsApp")))["canGroupSimilar"].truthy)
    }

    @Test fun pagesAreSmartLaunchersCategoriesWithAppsThenYourGroupsThenTheRest() {
        val apps =
            listOf(
                app("Signal", smart = "communication"), app("Brave", smart = "internet"), app("VLC", smart = "media"),
                app("Calculator"), app("WhatsApp", smart = "communication"), app("btop", "linux"), app("Notes"),
            )
        val drawer = Drawer(apps, layout = "pages", groups = listOf("work stuff" to listOf("a:Notes"), "empty" to emptyList()))
        // Empty categories are left out, groups stay even empty; the list's rows aren't built.
        assertEquals(
            listOf("communication: Signal WhatsApp", "internet: Brave", "media: VLC", "utilities: Calculator", "work stuff: Notes", "empty: ", "linux: btop"),
            drawer.pages(),
        )
        assertEquals(emptyList<String>(), drawer.rows())
    }

    @Test fun aGroupNamedForACategoryAddsItsAppsToThatPage() {
        val apps = listOf(app("Signal", smart = "communication"), app("Notes"), app("Gallery", smart = "media"))
        val drawer = Drawer(apps, layout = "pages", groups = listOf("communication" to listOf("a:Notes")))
        assertEquals(listOf("communication: Notes Signal", "media: Gallery"), drawer.pages())
        // The page is the group's too: it renames and ungroups it.
        assertEquals("communication", ((drawer["pages"] as Lst).items[0] as Obj).fields["group"].toString())
    }

    @Test fun androidsKindsPutAppsThatDeclareNoneUnderOther() {
        val apps = listOf(app("Chess", category = "games"), app("Notes"), app("Maps", category = "maps"))
        assertEquals(listOf("games: Chess", "maps: Maps", "other: Notes"), Drawer(apps, layout = "pages", categories = "android").pages())
    }

    @Test fun workAppsAndTheLinuxOfferGetPagesOfTheirOwn() {
        val apps = listOf(app("Signal", smart = "communication"), app("Slack", work = true))
        assertEquals(listOf("communication: Signal", "linux apps: ", "work apps: Slack"), Drawer(apps, layout = "pages", work = true, linux = false).pages())
    }

    @Test fun swipingOrTappingTurnsThePageAndItStaysAsPagesComeAndGo() {
        val apps = listOf(app("Signal", smart = "communication"), app("Brave", smart = "internet"), app("VLC", smart = "media"))
        val drawer = Drawer(apps, layout = "pages")
        assertEquals(0.0, (drawer["pageAt"] as Num).v, 0.0)
        drawer.swipeTo(2)
        assertEquals("c:media", drawer["at"].toString())
        assertEquals(2.0, (drawer["pageAt"] as Num).v, 0.0)
        assertEquals("media", ((drawer["shownPage"] as Obj).fields["title"]).toString())
        // Its page gone (the last media app uninstalled, say), the first shows.
        val fewer = Drawer(apps.take(2), layout = "pages")
        fewer.swipeTo(1)
        assertEquals(1.0, (fewer["pageAt"] as Num).v, 0.0)
    }
}
