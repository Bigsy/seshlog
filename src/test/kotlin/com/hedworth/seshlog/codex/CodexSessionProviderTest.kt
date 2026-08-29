package com.hedworth.seshlog.codex

import com.hedworth.seshlog.model.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths

class CodexSessionProviderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `scans rollouts applies names and detects writer locks`() {
        val root = tmp.root.toPath()
        val day = root.resolve("sessions/2026/08/29")
        Files.createDirectories(day)
        val id = "019a1111-2222-7333-8444-555555555555"
        val rollout = day.resolve("rollout-2026-08-29T10-00-00-$id.jsonl")
        Files.copy(Paths.get(javaClass.getResource("/fixtures/codex_session.jsonl")!!.toURI()), rollout)
        Files.writeString(root.resolve("session_index.jsonl"), """{"id":"$id","thread_name":"Codex integration","updated_at":"2026-08-29T10:00:07Z"}
""")
        Files.createDirectories(root.resolve("thread-writer-locks"))
        Files.createFile(root.resolve("thread-writer-locks/$id.lock"))
        // A JSONL index beneath sessions must not be mistaken for a rollout.
        Files.createDirectories(root.resolve("sessions/index/by-dir"))
        Files.writeString(root.resolve("sessions/index/by-dir/project.jsonl"), "{}")

        val provider = CodexSessionProvider({ root }, { "codex" })
        assertTrue(provider.isAvailable())
        val session = provider.scan(emptyMap()).single()
        assertEquals(AgentKind.CODEX, session.kind)
        assertEquals(id, session.id)
        assertEquals("Codex integration", session.title)
        assertEquals(Paths.get("/Users/tester/workspace/acme"), session.cwd)
        assertEquals("feature/codex", session.displayBranch)
        assertEquals(2, session.promptCount)
        assertTrue(session.hasExplicitTitle)
        assertTrue(session.isLive)

        // Metadata is reused when the rollout is unchanged, but renamed sessions update immediately.
        Files.writeString(root.resolve("session_index.jsonl"), """{"id":"$id","thread_name":"Renamed session","updated_at":"2026-08-29T10:00:08Z"}
""")
        val renamed = provider.scan(mapOf(id to session)).single()
        assertEquals("Renamed session", renamed.title)
        Files.delete(root.resolve("thread-writer-locks/$id.lock"))
        assertFalse(provider.scan(mapOf(id to renamed)).single().isLive)
    }

    @Test
    fun `resume and fork commands quote executable and id`() {
        val root = tmp.root.toPath()
        val provider = CodexSessionProvider({ root }, { "/opt/Codex CLI/codex" })
        val session = com.hedworth.seshlog.model.Session(
            kind = AgentKind.CODEX,
            id = "x;echo nope",
            title = "t",
            cwd = root,
            gitBranch = null,
            startedAt = null,
            lastActivityAt = java.time.Instant.EPOCH,
            transcriptPath = root.resolve("x.jsonl"),
            isLive = false,
            livePid = null,
            promptTitle = null,
            promptCount = 1,
            hasExplicitTitle = false,
        )
        assertEquals("'/opt/Codex CLI/codex' resume 'x;echo nope'", provider.resumeCommand(session))
        assertEquals("'/opt/Codex CLI/codex' fork 'x;echo nope'", provider.forkCommand(session))
    }
}
