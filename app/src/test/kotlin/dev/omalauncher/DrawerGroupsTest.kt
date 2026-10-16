package dev.omalauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerGroupsTest {
    private val games = DrawerGroup("games", listOf("a:chess", "a:go"))
    private val tools = DrawerGroup("tools", listOf("l:btop"))

    @Test fun anAppIsInOneGroupAtMost() {
        // Put in tools, it leaves games.
        assertEquals(listOf(DrawerGroup("games", listOf("a:go")), DrawerGroup("tools", listOf("l:btop", "a:chess"))), withApp(listOf(games, tools), "a:chess", "tools"))
    }

    @Test fun aNewGroupComesAfterTheOthers() {
        assertEquals(listOf(games, DrawerGroup("work", listOf("a:mail"))), withApp(listOf(games), "a:mail", "work"))
    }

    @Test fun ungroupingLeavesTheGroup() {
        assertEquals(listOf(DrawerGroup("games", listOf("a:go"))), withoutApp(listOf(games), "a:chess"))
    }

    @Test fun renamingKeepsNamesApart() {
        assertEquals(listOf(DrawerGroup("fun", games.keys), tools), renamed(listOf(games, tools), "games", "fun"))
        // Taken, or blank: unchanged.
        assertEquals(listOf(games, tools), renamed(listOf(games, tools), "games", "tools"))
        assertEquals(listOf(games, tools), renamed(listOf(games, tools), "games", " "))
    }

    @Test fun groupsSurviveBeingSaved() {
        assertEquals(listOf(games, tools), read(write(listOf(games, tools))))
        assertEquals(emptyList<DrawerGroup>(), read(null))
        assertEquals(emptyList<DrawerGroup>(), read("not json"))
    }

    @Test fun namesSurviveBeingSaved() {
        assertEquals(mapOf("a:x" to "Mail"), readNames("""{"a:x":"Mail"}"""))
        assertEquals(emptyMap<String, String>(), readNames("broken"))
    }
}
