package com.hedworth.seshlog.claude

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

        val first = ClaudeCodeSessionProvider({ root }, { "claude" }, cacheFile).scan(emptyMap())
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
        ClaudeCodeSessionProvider({ root }, { "claude" }, cacheFile).scan(emptyMap())
        assertTrue(TranscriptInfoStore.load(cacheFile).isEmpty())
    }
}
