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
        val conversation = provider.conversationMessages(session)
        assertTrue(conversation.isNotEmpty())
        assertEquals(provider.conversationText(session), conversation.map { it.text })
        assertEquals(provider.lastMessages(session, 2), conversation.takeLast(2))
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
    fun `guardian rollouts sharing a session id are excluded across cache reloads`() {
        val root = tmp.root.toPath()
        val day = Files.createDirectories(root.resolve("sessions/2026/09/08"))
        val id = "019a1111-2222-7333-8444-555555555555"
        val userRollout = day.resolve("rollout-user.jsonl")
        Files.writeString(userRollout,
            """{"type":"session_meta","payload":{"id":"$id","session_id":"$id","cwd":"/project","source":"cli","thread_source":"user"}}""")
        // Either metadata marker is sufficient; old rollouts can lack thread_source.
        val markers = listOf(
            """"thread_source":"guardian_review"""",
            """"source":{"subagent":{"other":"guardian"}}""",
            """"thread_source":"guardian_review","source":{"subagent":{"other":"guardian"}}""",
        )
        markers.forEachIndexed { index, marker ->
            Files.writeString(day.resolve("rollout-guardian-$index.jsonl"),
                """{"type":"session_meta","payload":{"id":"guardian-$index","session_id":"$id","cwd":"/project",$marker}}""")
        }
        Files.writeString(root.resolve("session_index.jsonl"),
            """{"id":"$id","thread_name":"Make error numbers clickable"}""")
        val cacheFile = root.resolve("cache.json")
        repeat(2) {
            val session = CodexSessionProvider({ root }, { "codex" }, cacheFile).scan(emptyMap()).single()
            assertEquals(id, session.id)
            assertEquals("Make error numbers clickable", session.title)
            assertEquals(userRollout, session.transcriptPath)
        }
        assertEquals(4, CodexTranscriptInfoStore.load(cacheFile).size)
    }

    @Test
    fun `previous cache version is discarded so guardian metadata is reparsed`() {
        assertTrue(CodexTranscriptInfoStore.fromJson("""{"version":1,"entries":[{"path":"/rollout.jsonl","size":1,"mtime":1}]}""").isEmpty())
    }

    @Test
    fun `fork lineage distinguishes identical titles across cache reloads`() {
        val root = tmp.root.toPath()
        val day = Files.createDirectories(root.resolve("sessions/2026/09/08"))
        Files.writeString(day.resolve("rollout-parent.jsonl"),
            """{"type":"session_meta","payload":{"id":"parent","cwd":"/project"}}""")
        Files.writeString(day.resolve("rollout-child.jsonl"),
            """{"type":"session_meta","payload":{"id":"child","cwd":"/project","forked_from_id":"parent"}}""")
        Files.writeString(root.resolve("session_index.jsonl"),
            """{"id":"parent","thread_name":"Review access record"}
{"id":"child","thread_name":"Review access record"}""")
        val cacheFile = root.resolve("cache.json")
        repeat(2) {
            val sessions = CodexSessionProvider({ root }, { "codex" }, cacheFile)
                .scan(emptyMap()).associateBy { it.id }
            assertEquals(2, sessions.size)
            assertEquals(sessions.getValue("parent").title, sessions.getValue("child").title)
            assertEquals(null, sessions.getValue("parent").forkedFromId)
            assertEquals("parent", sessions.getValue("child").forkedFromId)
        }
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
