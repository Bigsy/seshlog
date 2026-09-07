package com.hedworth.seshlog.pi

import com.hedworth.seshlog.index.SessionWatcher
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.AgentFilterMode
import com.hedworth.seshlog.settings.AgentSessionFilter
import com.hedworth.seshlog.settings.SeshlogSettings
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.time.Instant

class PiIntegrationTest {
    @Test fun `settings resolution uses explicit root then session env then agent env then default`() {
        val env = mapOf("PI_CODING_AGENT_SESSION_DIR" to "/session/../sessions", "PI_CODING_AGENT_DIR" to "/agent")
        assertEquals(Path.of("/override"), SeshlogSettings.resolvePiSessionsDir(" /override ", env))
        assertEquals(Path.of("/sessions"), SeshlogSettings.resolvePiSessionsDir("", env))
        assertEquals(Path.of("/agent/sessions"), SeshlogSettings.resolvePiSessionsDir("", env + ("PI_CODING_AGENT_SESSION_DIR" to " ")))
        assertEquals(Path.of(System.getProperty("user.home"), ".pi/agent/sessions"),
            SeshlogSettings.resolvePiSessionsDir(" ", mapOf("PI_CODING_AGENT_DIR" to " ")))
        assertEquals(Path.of(System.getProperty("user.home"), "pi-sessions"), SeshlogSettings.resolvePiSessionsDir("~/pi-sessions", env))
    }

    @Test fun `existing settings load Pi defaults and custom values persist`() {
        val settings = SeshlogSettings()
        settings.loadState(SeshlogSettings.State())
        assertEquals("pi", settings.piExecutable)
        assertEquals("", settings.piSessionsDir)
        settings.piExecutable = "/custom/pi"
        settings.piSessionsDir = "/custom/sessions"
        val restored = SeshlogSettings()
        restored.loadState(settings.state)
        assertEquals("/custom/pi", restored.piExecutable)
        assertEquals(Path.of("/custom/sessions"), restored.resolvedPiSessionsDir())
        restored.piExecutable = " "
        assertEquals("pi", restored.piExecutable)
    }

    @Test fun `Pi filter roundtrips and preserves Auto tie order and distinct identities`() {
        val pi = Session(AgentKind.PI, "pi:same-id", "Pi", Path.of("/project"), null, null, Instant.EPOCH,
            Path.of("/sessions/pi.jsonl"), false, null, "Pi", 1, false)
        val codex = pi.copy(kind = AgentKind.CODEX, id = "same-id")
        val all = listOf(pi, codex)
        val mode = AgentFilterMode.Only(AgentKind.PI)
        assertTrue(AgentFilterMode.entries.contains(mode))
        assertEquals("Pi", mode.displayName)
        assertEquals(mode, AgentFilterMode.parse(mode.serialize()))
        assertEquals(listOf(pi), AgentSessionFilter.apply(all, mode))
        assertEquals(all, AgentSessionFilter.apply(all, AgentFilterMode.All))
        assertEquals(listOf(codex), AgentSessionFilter.apply(all, AgentFilterMode.Auto))
        assertEquals(2, all.associateBy { it.id }.size)
    }

    @Test fun `search organisation and terminal ownership isolate the same raw ID and Pi stays out of restore`() {
        val pi = Session(AgentKind.PI, "pi:same-id", "Pi", Path.of("/project"), null, null, Instant.EPOCH,
            Path.of("/sessions/pi.jsonl"), false, null, "Pi", 1, false)
        val codex = pi.copy(kind = AgentKind.CODEX, id = "same-id", title = "Codex")
        val all = listOf(pi, codex)
        val index = com.hedworth.seshlog.index.ContentSearchIndex(
            extractor = { if (it.kind == AgentKind.PI) listOf("Pi needle") else listOf("Codex haystack") },
            contentStamp = { 1 },
        )
        val hit = index.search("needle", all).single()
        assertEquals(pi.id, hit.session.id)
        assertEquals("Pi needle", hit.snippet)
        assertEquals(codex.id, index.search("haystack", all).single().session.id)
        val organisation = com.hedworth.seshlog.settings.SessionOrganisation()
        organisation.edit(pi.id) { it.title = "Local Pi"; it.pinned = true }
        assertEquals("Local Pi", organisation.title(pi))
        assertEquals("Codex", organisation.title(codex))
        assertFalse(organisation.metadata(codex.id).pinned)
        val tabs = com.hedworth.seshlog.terminal.TabRegistry<String>()
        tabs.register(pi.id, "pi-tab")
        tabs.register(codex.id, "codex-tab")
        assertEquals("pi-tab", tabs.tabFor(pi.id))
        assertEquals("codex-tab", tabs.tabFor(codex.id))
        val tree = object : com.hedworth.seshlog.restore.ProcessTree {
            override fun isAlive(pid: Long) = false
            override fun parentPid(pid: Long): Long? = null
        }
        tabs.sync(all, mapOf("pi-tab" to null, "codex-tab" to null), tree) { it }
        assertEquals("pi-tab", tabs.tabFor(pi.id))
        val provider = PiSessionProvider({ Path.of("/sessions") }, { "pi" })
        val plan = com.hedworth.seshlog.restore.RestoreCandidates.plan(all.map { it.id }, all, tree) {
            it.kind != AgentKind.PI || provider.detectsLiveSessions
        }
        assertEquals(listOf(codex), plan.restore)
        assertTrue(com.hedworth.seshlog.restore.RestoreCandidates.snapshot(listOf(pi), setOf(pi.id), emptySet(), tree).isEmpty())
    }

    @Test fun `watcher matches Pi append rename and new project paths and excludes previous root`() {
        val roots = listOf("/pi/sessions")
        assertTrue(SessionWatcher.isUnderRoots("/pi/sessions", roots))
        assertTrue(SessionWatcher.isUnderRoots("/pi/sessions/new-project/session.jsonl", roots))
        assertFalse(SessionWatcher.isUnderRoots("/pi/sessions-other/session.jsonl", roots))
        assertFalse(SessionWatcher.isUnderRoots("/pi/sessions/session.jsonl", listOf("/new/root")))
    }
}
