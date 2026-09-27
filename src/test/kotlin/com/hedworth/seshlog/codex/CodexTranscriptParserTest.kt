package com.hedworth.seshlog.codex

import com.hedworth.seshlog.model.Activity
import com.hedworth.seshlog.claude.TranscriptTailReader
import com.hedworth.seshlog.claude.TranscriptTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.nio.file.Files
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class CodexTranscriptParserTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private fun fixture(): Path = Paths.get(javaClass.getResource("/fixtures/codex_session.jsonl")!!.toURI())

    @Test
    fun `extracts metadata and real prompts from a rollout`() {
        val info = CodexTranscriptParser.parse(fixture())

        assertEquals("019a1111-2222-7333-8444-555555555555", info.sessionId)
        assertEquals("/Users/tester/workspace/acme", info.cwd)
        assertEquals("feature/codex", info.gitBranch)
        assertEquals(Instant.parse("2026-08-29T10:00:00Z"), info.startedAt)
        assertEquals("Add Codex session support", info.promptTitle)
        assertEquals(2, info.promptCount)
    }

    @Test
    fun `conversation extraction skips injected context tools and malformed records`() {
        assertEquals(
            listOf(
                "Add Codex session support\nand cover the parser.",
                "I’ll add the provider and tests.",
                "Also support named sessions.",
                "Named sessions now take precedence.",
            ),
            TranscriptTextExtractor.extract(fixture(), CodexConversationMessages::parseLine),
        )
        assertEquals(
            listOf("I’ll add the provider and tests.", "Also support named sessions.", "Named sessions now take precedence."),
            TranscriptTailReader.lastMessages(fixture(), 3, parseLine = CodexConversationMessages::parseLine).map { it.text },
        )
    }

    @Test
    fun `the Claude parser no longer falls through to Codex records`() {
        assertEquals(emptyList<String>(), TranscriptTextExtractor.extract(fixture()))
        assertEquals(emptyList<com.hedworth.seshlog.model.ConversationMessage>(), TranscriptTailReader.lastMessages(fixture(), 3))
    }

    @Test
    fun `empty and malformed input produces empty metadata`() {
        val info = CodexTranscriptParser.parseLines(sequenceOf("", "{bad", "[]"))
        assertNull(info.sessionId)
        assertNull(info.cwd)
        assertEquals(0, info.promptCount)
    }

    private fun event(type: String, at: String, extra: String = "") =
        """{"timestamp":"$at","type":"event_msg","payload":{"type":"$type"$extra}}"""

    @Test
    fun `task events decide the activity and the last one wins`() {
        val meta = """{"timestamp":"2026-08-29T10:00:00Z","type":"session_meta","payload":{"id":"s1","cwd":"/p"}}"""
        assertEquals(Activity.UNKNOWN, CodexTranscriptParser.parseLines(sequenceOf(meta)).activity)

        val working = CodexTranscriptParser.parseLines(sequenceOf(meta, event("task_started", "2026-08-29T10:01:00Z")))
        assertEquals(Activity.WORKING, working.activity)
        assertEquals(Instant.parse("2026-08-29T10:01:00Z"), working.activityAt)

        val waiting = CodexTranscriptParser.parseLines(sequenceOf(
            meta, event("task_started", "2026-08-29T10:01:00Z"),
            event("item_completed", "2026-08-29T10:01:30Z"), event("token_count", "2026-08-29T10:01:31Z"),
            event("task_complete", "2026-08-29T10:02:00Z", ""","last_agent_message":"done"""")))
        assertEquals(Activity.WAITING, waiting.activity)
        assertEquals(Instant.parse("2026-08-29T10:02:00Z"), waiting.activityAt)

        val aborted = CodexTranscriptParser.parseLines(sequenceOf(
            meta, event("task_started", "2026-08-29T10:01:00Z"), event("turn_aborted", "2026-08-29T10:01:10Z")))
        assertEquals(Activity.INTERRUPTED, aborted.activity)
        assertEquals(Instant.parse("2026-08-29T10:01:10Z"), aborted.activityAt)
    }

    @Test
    fun `task events are not mistaken for tool output that quotes them, and malformed events are ignored`() {
        val meta = """{"timestamp":"2026-08-29T10:00:00Z","type":"session_meta","payload":{"id":"s1","cwd":"/p"}}"""
        val quoted = """{"timestamp":"2026-08-29T10:03:00Z","type":"response_item","payload":{"type":"function_call_output","output":"saw event_msg task_complete in a log"}}"""
        val info = CodexTranscriptParser.parseLines(sequenceOf(meta, event("task_started", "2026-08-29T10:01:00Z"), quoted))
        assertEquals(Activity.WORKING, info.activity)
        assertEquals(Instant.parse("2026-08-29T10:01:00Z"), info.activityAt)

        val broken = CodexTranscriptParser.parseLines(sequenceOf(
            meta, event("task_complete", "2026-08-29T10:02:00Z"), """{"type":"event_msg","payload":{"type":"task_started""""))
        assertEquals(Activity.WAITING, broken.activity)

        val noStamp = CodexTranscriptParser.parseLines(sequenceOf(meta, """{"type":"event_msg","payload":{"type":"task_started"}}"""))
        assertEquals(Activity.WORKING, noStamp.activity)
        assertNull(noStamp.activityAt)
    }

    @Test
    fun `subagent rollouts use their own id and are marked for exclusion`() {
        val parent = """{"type":"session_meta","payload":{"id":"parent","session_id":"parent","cwd":"/p","source":"cli"}}"""
        val spawned = """{"type":"session_meta","payload":{"id":"child","session_id":"parent","cwd":"/p","source":{"subagent":{"thread_spawn":{"parent_thread_id":"parent"}}}}}"""
        val review = """{"type":"session_meta","payload":{"id":"review","session_id":"parent","cwd":"/p","source":{"subagent":{"review":{"parent_thread_id":"parent"}}}}}"""

        val parentInfo = CodexTranscriptParser.parseLines(sequenceOf(parent))
        assertEquals("parent", parentInfo.sessionId)
        assertEquals(false, parentInfo.isSubagentRollout)

        val spawnedInfo = CodexTranscriptParser.parseLines(sequenceOf(spawned))
        assertEquals("child", spawnedInfo.sessionId)
        assertEquals(true, spawnedInfo.isSubagentRollout)

        val reviewInfo = CodexTranscriptParser.parseLines(sequenceOf(review))
        assertEquals("review", reviewInfo.sessionId)
        assertEquals(true, reviewInfo.isSubagentRollout)

        val directReview = """{"type":"session_meta","payload":{"id":"direct-review","cwd":"/p","source":"review"}}"""
        assertTrue(CodexTranscriptParser.parseLines(sequenceOf(directReview)).isSubagentRollout)
    }

    @Test
    fun `incremental parsing retains activity and counts across an append`() {
        val path = tmp.newFile("rollout.jsonl").toPath()
        val first = """
            {"type":"session_meta","payload":{"id":"s","cwd":"/p"}}
            {"type":"response_item","payload":{"type":"message","role":"user","content":"first"}}
            {"type":"event_msg","timestamp":"2026-09-27T10:00:00Z","payload":{"type":"task_started"}}
        """.trimIndent() + "\n"
        val partial = """{"type":"response_item","payload":{"type":"message","role":"user","content":"second"}}"""
        Files.writeString(path, first + partial)

        val initial = CodexTranscriptParser.parseIncremental(path, null, 0)
        assertEquals(1, initial.info.promptCount)
        assertEquals(Activity.WORKING, initial.info.activity)

        Files.writeString(path, "\n", java.nio.file.StandardOpenOption.APPEND)
        val resumed = CodexTranscriptParser.parseIncremental(path, initial.info, initial.completedOffset)
        assertEquals(2, resumed.info.promptCount)
        assertEquals(Activity.WORKING, resumed.info.activity)
    }
}
