package com.hedworth.seshlog.claude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant

class TranscriptInfoStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val info = TranscriptInfo(
        sessionId = "abc", cwd = "/Users/tester/my project", gitBranch = "main", version = "1.0.0",
        promptTitle = "Fix the \"flaky\" test", aiTitle = "Fix flaky test", customTitle = null,
        startedAt = Instant.ofEpochMilli(1_700_000_000_000), promptCount = 3,
    )

    @Test
    fun `round trips through json`() {
        val entries = mapOf(
            Paths.get("/a/1.jsonl") to TranscriptInfoStore.Entry(10, 20, info),
            Paths.get("/a/2.jsonl") to TranscriptInfoStore.Entry(1, 2, info.copy(startedAt = null, aiTitle = null)),
        )
        assertEquals(entries, TranscriptInfoStore.fromJson(TranscriptInfoStore.toJson(entries)))
    }

    @Test
    fun `save and load, missing or corrupt file yields empty`() {
        val file = tmp.root.toPath().resolve("nested/index.json")
        assertTrue(TranscriptInfoStore.load(file).isEmpty())
        val entries = mapOf(Paths.get("/a/1.jsonl") to TranscriptInfoStore.Entry(10, 20, info))
        TranscriptInfoStore.save(file, entries)
        assertEquals(entries, TranscriptInfoStore.load(file))
        Files.writeString(file, "{not json")
        assertTrue(TranscriptInfoStore.load(file).isEmpty())
    }

    @Test
    fun `other versions and bad entries are ignored`() {
        assertTrue(TranscriptInfoStore.fromJson("""{"version": 99, "entries": []}""").isEmpty())
        // v1 stored the raw first prompt; those caches must be dropped rather than migrated.
        assertTrue(
            TranscriptInfoStore.fromJson(
                """{"version": 1, "entries": [{"path": "/a/1.jsonl", "size": 1, "mtime": 2, "firstPrompt": "raw"}]}""",
            ).isEmpty(),
        )
        val loaded = TranscriptInfoStore.fromJson(
            """{"version": ${TranscriptInfoStore.VERSION}, "entries": [{"path": "/a/1.jsonl", "size": 1}, {"path": "/a/2.jsonl", "size": 1, "mtime": 2}, 7]}""",
        )
        assertEquals(setOf(Paths.get("/a/2.jsonl")), loaded.keys)
        assertEquals(0, loaded.values.single().info.promptCount)
    }
}
