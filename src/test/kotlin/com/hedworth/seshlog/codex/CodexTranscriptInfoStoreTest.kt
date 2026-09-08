package com.hedworth.seshlog.codex

import com.hedworth.seshlog.cache.InfoStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant

class CodexTranscriptInfoStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val info = CodexTranscriptInfo(
        sessionId = "abc",
        cwd = "/Users/tester/project",
        gitBranch = "main",
        promptTitle = "Add Codex support",
        startedAt = Instant.ofEpochMilli(1_700_000_000_000),
        promptCount = 3,
        forkedFromId = "parent",
    )

    @Test
    fun `round trips metadata and ignores corrupt caches`() {
        val entries = mapOf(Paths.get("/rollouts/a.jsonl") to InfoStore.Entry(10, 20, info))
        assertEquals(entries, CodexTranscriptInfoStore.fromJson(CodexTranscriptInfoStore.toJson(entries)))

        val file = tmp.root.toPath().resolve("nested/codex-index.json")
        CodexTranscriptInfoStore.save(file, entries)
        assertEquals(entries, CodexTranscriptInfoStore.load(file))
        Files.writeString(file, "{bad")
        assertTrue(CodexTranscriptInfoStore.load(file).isEmpty())
    }
}
