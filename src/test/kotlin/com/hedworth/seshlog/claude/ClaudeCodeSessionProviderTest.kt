package com.hedworth.seshlog.claude

import com.hedworth.seshlog.model.EntryKind

import java.time.Instant
import com.hedworth.seshlog.model.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths

class ClaudeCodeSessionProviderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `scans project dirs, skips subagent dirs and memory, tolerates missing sessions dir`() {
        val root = tmp.root.toPath()
        val proj = root.resolve("projects/-Users-tester-workspace-acme-api")
        Files.createDirectories(proj.resolve("memory"))
        Files.createDirectories(proj.resolve("some-session/subagents"))
        val fixtures = Paths.get(javaClass.getResource("/fixtures/ai_title_only.jsonl")!!.toURI()).parent
        Files.copy(fixtures.resolve("ai_title_only.jsonl"), proj.resolve("425fc66d-def4-46ed-9d10-10b64b7788d2.jsonl"))
        Files.copy(fixtures.resolve("aborted_start.jsonl"), proj.resolve("cccccccc-0000-0000-0000-000000000003.jsonl"))
        Files.writeString(proj.resolve("sessions-index.json"), "{}")

        val provider = ClaudeCodeSessionProvider({ root }, { "claude" })
        assertTrue(provider.isAvailable())
        val sessions = provider.scan(emptyMap())
        assertEquals(2, sessions.size)
        val titled = sessions.single { it.id == "425fc66d-def4-46ed-9d10-10b64b7788d2" }
        assertEquals("Fix flaky CI test", titled.title)
        val conversation = provider.conversationMessages(titled)
        assertTrue(conversation.isNotEmpty())
        assertEquals(provider.conversationText(titled), conversation.map { it.text })
        assertEquals(provider.lastMessages(titled, 2), conversation.takeLast(2))
        assertEquals(Paths.get("/Users/tester/workspace/acme/api"), titled.cwd)
        assertEquals(null, titled.displayBranch)
        assertTrue(!titled.isLive)

        // second scan hits the (size, mtime) cache and yields the same result
        assertEquals(sessions.map { it.id }.toSet(), provider.scan(sessions.associateBy { it.id }).map { it.id }.toSet())
    }

    @Test
    fun `persisted cache is reused by a new provider without re-parsing`() {
        val root = tmp.root.toPath()
        val proj = root.resolve("projects/-Users-tester-workspace-acme-api")
        Files.createDirectories(proj)
        val fixtures = Paths.get(javaClass.getResource("/fixtures/ai_title_only.jsonl")!!.toURI()).parent
        val transcript = proj.resolve("425fc66d-def4-46ed-9d10-10b64b7788d2.jsonl")
        Files.copy(fixtures.resolve("ai_title_only.jsonl"), transcript)
        val cacheFile = root.resolve("system/seshlog/index.json")

        val firstProvider = ClaudeCodeSessionProvider({ root }, { "claude" }, cacheFile)
        val first = firstProvider.scan(emptyMap())
        firstProvider.flush()
        assertEquals(1, first.size)
        val cached = TranscriptInfoStore.load(cacheFile)
        assertEquals(setOf(transcript), cached.keys)
        assertEquals("Fix flaky CI test", cached.values.single().info.title)

        // Corrupt the transcript but keep size + mtime: a fresh provider must trust the cache, not re-parse.
        val attrs = Files.readAttributes(transcript, java.nio.file.attribute.BasicFileAttributes::class.java)
        Files.write(transcript, ByteArray(attrs.size().toInt()) { 'x'.code.toByte() })
        Files.setLastModifiedTime(transcript, attrs.lastModifiedTime())
        val second = ClaudeCodeSessionProvider({ root }, { "claude" }, cacheFile).scan(emptyMap())
        assertEquals("Fix flaky CI test", second.single().title)

        // Deleted transcripts drop out of the persisted cache on the next scan.
        Files.delete(transcript)
        val deletedProvider = ClaudeCodeSessionProvider({ root }, { "claude" }, cacheFile)
        deletedProvider.scan(emptyMap())
        deletedProvider.flush()
        assertTrue(TranscriptInfoStore.load(cacheFile).isEmpty())
    }

    @Test
    fun `live status maps to activity with its timestamp`() {
        val root = tmp.newFolder("activity").toPath()
        val proj = root.resolve("projects/-Users-tester-workspace-acme-api")
        Files.createDirectories(proj)
        Files.createDirectories(root.resolve("sessions"))
        val id = "425fc66d-def4-46ed-9d10-10b64b7788d2"
        val fixtures = Paths.get(javaClass.getResource("/fixtures/ai_title_only.jsonl")!!.toURI()).parent
        Files.copy(fixtures.resolve("ai_title_only.jsonl"), proj.resolve("$id.jsonl"))
        val pid = ProcessHandle.current().pid()
        val live = root.resolve("sessions/$pid.json")
        val provider = ClaudeCodeSessionProvider({ root }, { "claude" })

        fun scan(): com.hedworth.seshlog.model.Session = provider.scan(emptyMap()).single { it.id == id }

        Files.writeString(live, """{"pid":$pid,"sessionId":"$id","status":"busy","statusUpdatedAt":1000,"updatedAt":2000}""")
        val working = scan()
        assertTrue(working.isLive)
        assertEquals(Activity.WORKING, working.activity)
        assertEquals(Instant.ofEpochMilli(1000), working.activitySince)

        Files.writeString(live, """{"pid":$pid,"sessionId":"$id","status":"idle","statusUpdatedAt":3000}""")
        val waiting = scan()
        assertEquals(Activity.WAITING, waiting.activity)
        assertEquals(Instant.ofEpochMilli(3000), waiting.activitySince)

        Files.writeString(live, """{"pid":$pid,"sessionId":"$id","status":"something-new"}""")
        assertEquals(Activity.UNKNOWN, scan().activity)

        Files.delete(live)
        val gone = scan()
        assertTrue(!gone.isLive)
        assertEquals(Activity.UNKNOWN, gone.activity)
        assertEquals(null, gone.activitySince)
    }

    @Test
    fun `subagent text is searchable as parent tool activity and continuation is linked`() {
        val root = tmp.root.toPath()
        val proj = root.resolve("projects/-Users-tester-project")
        val id = "subagent-session"
        val transcript = proj.resolve("$id.jsonl")
        val subagents = proj.resolve("$id/subagents")
        Files.createDirectories(subagents)
        val fixtures = Paths.get(javaClass.getResource("/fixtures")!!.toURI())
        Files.copy(fixtures.resolve("claude_subagent_parent.jsonl"), transcript)
        val agent = subagents.resolve("agent-a.jsonl")
        Files.copy(fixtures.resolve("claude_subagent_agent.jsonl"), agent)

        val provider = ClaudeCodeSessionProvider({ root }, { "claude" })
        val session = provider.scan(emptyMap()).single()
        assertEquals("successor-session", session.continuationId)
        assertEquals(listOf(
            "Review the implementation", "I will inspect it.",
            "Check the parser edge case", "The parser accepts the queued form.",
        ), provider.conversationText(session))
        val entries = provider.conversationEntries(session)
        assertEquals(2, entries.count { it.kind == com.hedworth.seshlog.model.EntryKind.TOOL_RESULT })
        assertTrue(entries.filter { it.kind == com.hedworth.seshlog.model.EntryKind.TOOL_RESULT }
            .all { it.toolName == "Claude subagent" })
        assertTrue(entries.any { it.text.contains("parser edge case") })

        val firstStamp = provider.contentStamp(session)
        Files.writeString(agent, """
            {"type":"assistant","sessionId":"$id","timestamp":"2026-09-27T11:00:05Z","message":{"role":"assistant","content":[{"type":"text","text":"The edge case is covered."}]}}
        """.trimIndent() + "\n", java.nio.file.StandardOpenOption.APPEND)
        Files.setLastModifiedTime(agent, java.nio.file.attribute.FileTime.fromMillis(session.lastActivityAt.toEpochMilli() + 1_000))
        val refreshed = provider.scan(mapOf(id to session)).single()
        assertTrue(firstStamp != provider.contentStamp(refreshed))
        assertTrue(refreshed.lastActivityAt.isAfter(session.lastActivityAt))
        assertTrue(provider.conversationText(refreshed).last() == "The edge case is covered.")
    }

    @Test
    fun `parent and subagent entries share the conversation budget`() {
        val root = tmp.root.toPath()
        val proj = root.resolve("projects/-Users-tester-project")
        val id = "bounded-subagent-session"
        val transcript = proj.resolve("$id.jsonl")
        val subagents = proj.resolve("$id/subagents")
        Files.createDirectories(subagents)
        val fixtures = Paths.get(javaClass.getResource("/fixtures")!!.toURI())
        Files.copy(fixtures.resolve("claude_subagent_parent.jsonl"), transcript)
        val agent = subagents.resolve("agent-large.jsonl")
        val text = "x".repeat(20_000)
        Files.writeString(agent, (1..40).joinToString("\n") { index ->
            """{"type":"assistant","sessionId":"$id","message":{"role":"assistant","content":[{"type":"text","text":"$index $text"}]}}"""
        } + "\n")

        val provider = ClaudeCodeSessionProvider({ root }, { "claude" })
        val entries = provider.conversationEntries(provider.scan(emptyMap()).single())
        assertEquals(1, entries.count { it.kind == EntryKind.COVERAGE })
        assertTrue(entries.sumOf { it.text.length } <= com.hedworth.seshlog.model.ConversationLimits.TOTAL_CHARS + com.hedworth.seshlog.model.ConversationLimits.NOTICE.length)
    }
}
