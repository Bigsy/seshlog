package com.hedworth.seshlog.claude

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import com.hedworth.seshlog.model.LocationSource
import com.hedworth.seshlog.cache.InfoStore

class ClaudeLocationTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun lines() = javaClass.getResource("/fixtures/locations/claude_moves.jsonl")!!.readText().lines().filter { it.isNotBlank() }

    @Test fun `worktree state switches before another prompt and tool envelopes report exit`() {
        val records = lines()
        assertEquals("/synthetic/topic-a", TranscriptParser.parseLines(records.take(2).asSequence()).cwd)
        assertEquals("/synthetic/topic-b", TranscriptParser.parseLines(records.take(5).asSequence()).cwd)
        val all = TranscriptParser.parseLines(records.asSequence())
        assertEquals("/synthetic/main", all.cwd)
        assertEquals(1, all.promptCount)
    }

    @Test fun `tool results report context without contributing prompts`() {
        val records = lines().filterNot { it.contains("worktree-state") }
        assertEquals("/synthetic/topic-a", TranscriptParser.parseLines(records.take(2).asSequence()).cwd)
    }

    @Test fun `clear state is uncertain until a recorded context and absent fields do not erase it`() {
        val records = lines()
        val cleared = TranscriptParser.parseLines(records.take(6).asSequence())
        assertEquals("/synthetic/topic-b", cleared.cwd)
        assertTrue(cleared.location!!.uncertain)
        val restored = TranscriptParser.parseLines(records.asSequence())
        assertFalse(restored.location!!.uncertain)
        assertEquals(LocationSource.CLAUDE_CONTEXT, restored.location.source)
        val invalid = listOf("{bad", "{\"type\":\"worktree-state\",\"worktreeSession\":{}}",
            "{\"type\":\"user\",\"cwd\":\"relative\",\"message\":{}}")
        assertEquals(restored.cwd, TranscriptParser.parseLines((records + invalid).asSequence()).cwd)
    }

    @Test fun `cold appended and persisted metadata agree at every record boundary`() {
        val path = tmp.newFile("session.jsonl").toPath()
        var offset = 0L
        var previous: TranscriptInfo? = null
        for (line in lines()) {
            Files.writeString(path, line + "\n", StandardOpenOption.APPEND)
            val parsed = TranscriptParser.parseIncremental(path, previous, offset)
            assertEquals(TranscriptParser.parse(path), parsed.info)
            val json = TranscriptInfoStore.toJson(mapOf(path to InfoStore.Entry(Files.size(path), 1, parsed.info)))
            assertFalse(json.contains("tool_result"))
            previous = TranscriptInfoStore.fromJson(json).values.single().info
            offset = parsed.completedOffset
        }
    }

    @Test fun `failed transition and child evidence cannot change parent directory`() {
        val initial = lines().first()
        val failed = "{\"type\":\"user\",\"cwd\":\"/synthetic/main\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"is_error\":true,\"content\":\"EnterWorktree /attempt failed\"}]}}"
        val child = "{\"type\":\"worktree-state\",\"isSidechain\":true,\"worktreeSession\":{\"worktreePath\":\"/child\"}}"
        val result = TranscriptParser.parseLines(sequenceOf(initial, failed, child))
        assertEquals("/synthetic/main", result.cwd)
        assertEquals(1, result.promptCount)
    }

    @Test fun `relocated transcripts select latest recorded context regardless of mtime and marker cwd`() {
        val root = tmp.root.toPath()
        val old = Files.createDirectories(root.resolve("projects/z-old")).resolve("claude-moves.jsonl")
        val next = Files.createDirectories(root.resolve("projects/a-new")).resolve("claude-moves.jsonl")
        Files.writeString(old, lines().first() + "\n")
        Files.writeString(next, lines().take(3).joinToString("\n", postfix = "\n"))
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10000))
        val pid = ProcessHandle.current().pid()
        val marker = Files.createDirectories(root.resolve("sessions")).resolve("$pid.json")
        Files.writeString(marker, "{\"pid\":$pid,\"sessionId\":\"claude-moves\",\"cwd\":\"/synthetic/main\",\"status\":\"busy\"}")
        val cache = root.resolve("system/cache.json")
        val provider = ClaudeCodeSessionProvider({ root }, { "claude" }, cache)
        val session = provider.scan(emptyMap()).single()
        assertEquals(next, session.transcriptPath)
        assertEquals("/synthetic/topic-a", session.cwd.toString())
        assertTrue(session.isLive)
        provider.flush()
        assertEquals(session, ClaudeCodeSessionProvider({ root }, { "claude" }, cache).scan(emptyMap()).single())
    }
}
