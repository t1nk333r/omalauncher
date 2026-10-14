package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentsTest {
    /** A missing alias would make opening that agent's window throw. */
    @Test fun everyAgentHasAWindowAlias() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val aliases = Regex("""<activity-alias(.*?)/>""", RegexOption.DOT_MATCHES_ALL).findAll(manifest).map { it.groupValues[1] }.toList()
        for (agent in AGENTS) {
            val alias = aliases.firstOrNull { "android:name=\".${agent.windowAlias}\"" in it }
            assertTrue("${agent.bin}: no .${agent.windowAlias} in the manifest", alias != null)
            assertTrue("${agent.bin}: not labelled \"${agent.name}\"", "android:label=\"${agent.name}\"" in alias!!)
            assertTrue("${agent.bin}: no logo", "android:icon=\"@drawable/agent_${agent.bin.replace('-', '_')}\"" in alias)
        }
    }

    @Test fun ohMyPiUsesReadOnlyToolsForSharedWidgets() {
        assertEquals(
            "omp --allow-home --tools read,grep,glob --approval-mode always-ask " + shellQuote("hi"),
            agentInvocation("omp", "hi", readOnly = true),
        )
    }

    /** Terminals start in /root; without --allow-home Oh My Pi leaves it for a temp dir. */
    @Test fun ohMyPiStaysInHome() {
        assertEquals("omp --allow-home " + shellQuote("hi"), agentInvocation("omp", "hi"))
    }
}
