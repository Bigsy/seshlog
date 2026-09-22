package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

class ProcessTranscriptsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `lsof separates processes and accepts only writable descriptors with exact paths`() {
        val data = "p100\u0000\nf3\u0000aw\u0000n/a b/rollout-one.jsonl\u0000\n" +
            "f4\u0000ar\u0000n/read-only.jsonl\u0000\nf5\u0000n/unknown-mode.jsonl\u0000\n" +
            "p200\u0000\nf6\u0000au\u0000n/second.jsonl\u0000\n"
        assertEquals(mapOf(100L to setOf(Path.of("/a b/rollout-one.jsonl")),
            200L to setOf(Path.of("/second.jsonl"))), ProcessTranscripts.parseLsof(data))
        assertTrue(ProcessTranscripts.parseLsof("pbad\u0000f3\u0000aw\u0000n/file\u0000").isEmpty())
    }

    private fun session(id: String, source: String = "\"cli\""): Session {
        val file = temporary.newFile("rollout-$id.jsonl").toPath()
        Files.writeString(file, """{"type":"session_meta","payload":{"id":"$id","source":$source}}""" + "\n")
        return Session(AgentKind.CODEX, id, id, file.parent, null, null, Instant.EPOCH,
            file, false, null, null, 0, false)
    }

    @Test fun `fresh Codex replaces stopped Claude and excludes subagent rollouts in the same process`() {
        val main = session("main")
        val subagent = session("child", """{"subagent":{"thread_spawn":{"parent_thread_id":"main"}}}""")
        val evidence = SessionProcess.Evidence(123, "/bin/codex", emptyList())
        val discovery = SessionProcess.identifyTree(listOf(evidence), listOf(main, subagent),
            mapOf(AgentKind.CODEX to "codex"), mapOf(123L to setOf(main.transcriptPath!!, subagent.transcriptPath!!)))
        assertEquals(setOf("main"), discovery.sessionIds)
        assertEquals(mapOf("main" to 123L), discovery.processes)
        assertEquals("main", discovery.copySession("old-claude"))
        val registry = TabRegistry<String>()
        registry.register("old-claude", "tab")
        assertTrue(registry.adoptDiscovered(discovery.sessionIds.single(), "tab", "old-claude"))
        assertEquals("main", registry.sessionFor("tab"))
        assertFalse(registry.owns("old-claude"))
    }

    @Test fun `writable rollout overrides old resume arguments on native process and node wrapper`() {
        val old = session("old")
        val current = session("current")
        val processes = listOf(SessionProcess.Evidence(122, "/bin/node", listOf("/npm/codex.js", "resume", "old")),
            SessionProcess.Evidence(123, "/bin/codex", listOf("resume", "old")))
        val files = mapOf(123L to setOf(current.transcriptPath!!))
        val executables = mapOf(AgentKind.CODEX to "codex")
        assertEquals(setOf("current"), SessionProcess.identifyTree(processes, listOf(old, current), executables, files).sessionIds)
        // The transcript exists before the index sees it: never fall back to the old resume ID.
        assertNull(SessionProcess.identifyTree(processes, listOf(old), executables, files).copySession("old"))
        assertNull(SessionProcess.identifyTree(processes, listOf(old, current), executables,
            mapOf(123L to setOf(old.transcriptPath!!, current.transcriptPath))).copySession("old"))
    }

    @Test fun `unknown running agent cannot copy previous session and unrelated readers cannot adopt`() {
        val current = session("current")
        val files = mapOf(123L to setOf(current.transcriptPath!!))
        val executables = mapOf(AgentKind.CODEX to "codex")
        val unrelated = SessionProcess.Evidence(123, "/usr/bin/cat", emptyList())
        assertTrue(SessionProcess.identifyTree(listOf(unrelated), listOf(current), executables, files).sessionIds.isEmpty())
        val unknown = SessionProcess.identifyTree(listOf(unrelated.copy(command = "/bin/codex")), listOf(current), executables, emptyMap())
        assertNull(unknown.copySession("old-claude"))
        assertEquals("old-claude", SessionProcess.identifyTree(emptyList(), listOf(current), executables, emptyMap()).copySession("old-claude"))
    }

    @Test fun `real process writable descriptor discovers a fresh session without resume arguments`() {
        val current = session("with spaces")
        val process = ProcessBuilder("/bin/sh", "-c", "exec 3>>\"$1\"; echo ready; read line", "test", current.transcriptPath.toString()).start()
        try {
            assertEquals("ready", process.inputStream.bufferedReader().readLine())
            val actual = process.toHandle().info().command().orElseThrow()
            assertEquals(setOf("with spaces"), SessionProcess.discover(process.pid(), listOf(current), mapOf(AgentKind.CODEX to actual)))
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }
}
