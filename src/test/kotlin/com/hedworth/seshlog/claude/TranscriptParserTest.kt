package com.hedworth.seshlog.claude

import com.hedworth.seshlog.cache.InfoStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.nio.file.Files
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class TranscriptParserTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixture(name: String): Path =
        Paths.get(javaClass.getResource("/fixtures/$name")!!.toURI())

    @Test
    fun `custom-title beats ai-title, and the last ai-title wins`() {
        val info = TranscriptParser.parse(fixture("custom_and_ai_title.jsonl"))
        assertEquals("database-access-troubleshooting-guide", info.customTitle)
        assertEquals("Database migration rollback procedure", info.aiTitle)
        assertEquals("database-access-troubleshooting-guide", info.title)
        assertTrue(info.hasExplicitTitle)
        assertEquals("0953a942-db19-4fbc-9548-daa0a5c466b7", info.sessionId)
        assertEquals("/Users/tester/workspace/acme/widgets", info.cwd)
        assertEquals("feature/rollback", info.gitBranch)
        assertEquals("2.1.235", info.version)
        assertEquals(Instant.parse("2026-08-20T08:21:22.487Z"), info.startedAt)
        // meta caveat, /clear and the tool_result are not prompts
        assertEquals(2, info.promptCount)
        assertEquals("Write a rollback procedure for the widgets migration.", info.promptTitle)
    }

    @Test
    fun `ai-title only uses the latest ai-title`() {
        val info = TranscriptParser.parse(fixture("ai_title_only.jsonl"))
        assertNull(info.customTitle)
        assertEquals("Fix flaky CI test", info.title)
        assertEquals("HEAD", info.gitBranch)
        assertEquals(1, info.promptCount)
    }

    @Test
    fun `no title falls back to first prompt from array content, skipping sidechain and slash command`() {
        val info = TranscriptParser.parse(fixture("no_title_array_content.jsonl"))
        assertFalse(info.hasExplicitTitle)
        assertEquals("/Users/tester/workspace/acme/api", info.cwd)
        assertEquals(1, info.promptCount)
        val title = info.title
        assertTrue(title, title.startsWith("Please review the PR for the payments service"))
        assertTrue("truncated: $title", title.length <= 80 && title.endsWith("…"))
    }

    @Test
    fun `malformed lines and unknown types are skipped without throwing`() {
        val info = TranscriptParser.parse(fixture("malformed.jsonl"))
        assertEquals("Hello world session", info.title)
        assertEquals("hello world", info.promptTitle)
        assertEquals(1, info.promptCount)
        assertEquals("bbbbbbbb-0000-0000-0000-000000000002", info.sessionId)
    }

    @Test
    fun `aborted start has no prompts and is untitled`() {
        val info = TranscriptParser.parse(fixture("aborted_start.jsonl"))
        assertEquals(0, info.promptCount)
        assertEquals(TranscriptInfo.UNTITLED, info.title)
        assertEquals("/Users/tester/workspace/acme/api", info.cwd)
    }

    @Test
    fun `empty input never throws`() {
        val info = TranscriptParser.parseLines(emptySequence())
        assertNull(info.cwd)
        assertEquals(TranscriptInfo.UNTITLED, info.title)
    }

    @Test
    fun `promptToTitle strips slash commands and truncates`() {
        assertEquals("do the thing", TranscriptParser.promptToTitle("/clear do the thing"))
        assertEquals("second line", TranscriptParser.promptToTitle("\n\n  second line  \nthird"))
        assertEquals("", TranscriptParser.promptToTitle("/clear"))
        val long = "x".repeat(200)
        assertEquals(80, TranscriptParser.promptToTitle(long).length)
    }

    @Test
    fun `only the derived title is kept, never the raw prompt`() {
        val body = "line two is long enough to matter " + "y".repeat(200)
        val json = """{"type":"user","sessionId":"s","cwd":"/w","message":{"role":"user","content":"first line\n$body"}}"""
        val info = TranscriptParser.parseLines(sequenceOf(json))

        assertEquals("first line", info.promptTitle)
        assertFalse("raw prompt body must not survive parsing", info.toString().contains("yyyy"))
        assertFalse(
            "raw prompt body must not reach the on-disk cache",
            TranscriptInfoStore.toJson(mapOf(Paths.get("/a.jsonl") to InfoStore.Entry(1, 2, info)))
                .contains("yyyy"),
        )
    }

    @Test
    fun `counts only human queued prompts`() {
        val info = TranscriptParser.parse(fixture("claude_queued_commands.jsonl"))

        assertEquals(2, info.promptCount)
        assertEquals("Start the queued example", info.promptTitle)
    }

    @Test
    fun `reads the successor from a continued-in record`() {
        val info = TranscriptParser.parse(fixture("claude_subagent_parent.jsonl"))

        assertEquals("successor-session", info.continuationId)
    }

    @Test
    fun `reads Claude continuation field and latest resumed context`() {
        val original = TranscriptParser.parse(fixture("claude_continued_original.jsonl"))
        assertEquals("current-session", original.continuationId)
        val current = TranscriptParser.parse(fixture("claude_continued_current.jsonl"))
        assertEquals("main", current.gitBranch)
        assertEquals("/project/worktree", current.cwd)
        assertEquals(Instant.parse("2026-09-24T10:00:00Z"), current.startedAt)
        assertEquals("Review the rendering proposal", current.promptTitle)
        assertEquals(2, current.promptCount)
    }

    @Test
    fun `queued prompts update context without missing fields or sidechains overwriting it`() {
        val info = TranscriptParser.parse(fixture("claude_resumed_queued_context.jsonl"))
        assertEquals("main", info.gitBranch)
        assertEquals("/project/worktree", info.cwd)
        assertEquals(3, info.promptCount)
    }

    @Test
    fun `appended resume metadata matches full parsing`() {
        val lines = Files.readAllLines(fixture("claude_continued_current.jsonl"))
        val path = tmp.newFile("resumed.jsonl").toPath()
        Files.writeString(path, lines.take(2).joinToString("\n", postfix = "\n"))
        val initial = TranscriptParser.parseIncremental(path, null, 0)
        Files.writeString(path, lines.last() + "\n", java.nio.file.StandardOpenOption.APPEND)
        val resumed = TranscriptParser.parseIncremental(path, initial.info, initial.completedOffset).info
        assertEquals("main", resumed.gitBranch)
        assertEquals("/project/worktree", resumed.cwd)
        assertEquals(TranscriptParser.parse(path), resumed)
    }

    @Test
    fun `incremental parsing retries an unterminated record after append`() {
        val path = tmp.newFile("claude.jsonl").toPath()
        val first = """{"type":"user","sessionId":"s","cwd":"/p","message":{"role":"user","content":"first"}}""" + "\n"
        val partial = """{"type":"user","sessionId":"s","message":{"role":"user","content":"second"}}"""
        Files.writeString(path, first + partial)

        val initial = TranscriptParser.parseIncremental(path, null, 0)
        assertEquals(1, initial.info.promptCount)
        assertEquals(first.toByteArray().size.toLong(), initial.completedOffset)

        Files.writeString(path, "\n", java.nio.file.StandardOpenOption.APPEND)
        val resumed = TranscriptParser.parseIncremental(path, initial.info, initial.completedOffset)
        assertEquals(2, resumed.info.promptCount)
        assertEquals(Files.size(path), resumed.completedOffset)
    }
}
