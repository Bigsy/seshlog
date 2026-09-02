package com.hedworth.seshlog.index

import com.hedworth.seshlog.cache.FileStamp
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class ContentSearchIndexTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val contents = HashMap<Path, List<String>>()
    private var extractions = 0
    private val index = ContentSearchIndex(
        // Like the real extractors: a vanished transcript is an I/O error, not empty content.
        extractor = { s ->
            extractions++
            val path = s.transcriptPath!!
            if (!Files.exists(path)) throw java.nio.file.NoSuchFileException(path.toString())
            contents.getValue(path)
        },
        contentStamp = { s -> FileStamp.of(s.transcriptPath) },
    )

    private fun session(id: String, title: String, texts: List<String>, at: String = "2026-08-20T00:00:00Z"): Session {
        val path = tmp.root.toPath().resolve("$id.jsonl")
        Files.writeString(path, texts.joinToString("\n"))
        contents[path] = texts
        return Session(
            kind = AgentKind.CLAUDE_CODE, id = id, title = title, cwd = tmp.root.toPath(), gitBranch = null,
            startedAt = null, lastActivityAt = Instant.parse(at), transcriptPath = path,
            isLive = false, livePid = null, promptTitle = null, promptCount = 1, hasExplicitTitle = true,
        )
    }

    @Test
    fun `ranks by occurrence count, case-insensitively, with a snippet`() {
        val a = session("a", "Alpha", listOf("Fix the Rollback script", "rollback again, ROLLBACK once more"))
        val b = session("b", "Beta", listOf("nothing here"))
        val c = session("c", "Gamma", listOf("one rollback"))
        val hits = index.search("rollback", listOf(b, c, a))
        assertEquals(listOf("a", "c"), hits.map { it.session.id })
        assertEquals(3, hits[0].score)
        assertEquals("Fix the Rollback script", hits[0].snippet)
        assertEquals(1, hits[1].score)
    }

    @Test
    fun `title match counts even without content match and outranks few content hits`() {
        val titled = session("t", "Rollback plan", listOf("unrelated"))
        val content = session("c", "Other", listOf("rollback rollback"))
        val hits = index.search("rollback", listOf(content, titled))
        assertEquals(listOf("t", "c"), hits.map { it.session.id })
        assertTrue(hits[0].titleMatch)
        assertNull(hits[0].snippet)
    }

    @Test
    fun `blank query matches nothing`() {
        val s = session("s", "T", listOf("x"))
        assertEquals(emptyList<SearchHit>(), index.search("   ", listOf(s)))
    }

    @Test
    fun `unchanged transcripts are extracted once, changed ones re-extracted`() {
        val s = session("s", "T", listOf("first"))
        index.search("first", listOf(s))
        index.search("first", listOf(s))
        assertEquals(1, extractions)

        Thread.sleep(20)
        contents[s.transcriptPath!!] = listOf("first", "second")
        Files.writeString(s.transcriptPath!!, "first\nsecond")
        val hits = index.search("second", listOf(s))
        assertEquals(1, hits.size)
        assertEquals(2, extractions)
    }

    @Test
    fun `missing transcript yields no hit and no exception`() {
        val s = session("gone", "Gone", listOf("text"))
        Files.delete(s.transcriptPath!!)
        assertEquals(emptyList<SearchHit>(), index.search("text", listOf(s)))
        assertEquals(0, index.size)
    }

    @Test
    fun `a null stamp disables caching, a changed stamp re-extracts, retainOnly drops by id`() {
        var stamp: Any? = null
        val index = ContentSearchIndex(extractor = { extractions++; listOf("hit") }, contentStamp = { stamp })
        val s = session("s", "T", listOf("hit"))
        index.search("hit", listOf(s))
        index.search("hit", listOf(s))
        assertEquals(2, extractions)
        assertEquals(0, index.size)

        stamp = Instant.parse("2026-08-20T00:00:00Z")
        index.search("hit", listOf(s))
        index.search("hit", listOf(s))
        assertEquals(3, extractions)
        stamp = Instant.parse("2026-08-21T00:00:00Z")
        index.search("hit", listOf(s))
        assertEquals(4, extractions)

        index.retainOnly(listOf("other"))
        assertEquals(0, index.size)
    }

    @Test
    fun `cancellation returns partial results`() {
        val a = session("a", "A", listOf("hit"))
        val b = session("b", "B", listOf("hit"))
        var calls = 0
        val hits = index.search("hit", listOf(a, b)) { calls++ >= 1 }
        assertEquals(1, hits.size)
    }

    @Test
    fun `snippet trims context and adds ellipses`() {
        val text = "a".repeat(100) + " NEEDLE " + "b".repeat(100)
        val snippet = ContentSearchIndex.snippet(text, text.indexOf("NEEDLE"), 6)
        assertTrue(snippet, snippet.startsWith("…") && snippet.endsWith("…") && snippet.contains("NEEDLE"))
        assertEquals("x y", ContentSearchIndex.snippet("x\n\n  y", 0, 1))
        assertEquals(2, ContentSearchIndex.countOccurrences("aaaa", "aa"))
    }
}
