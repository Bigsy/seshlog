package com.hedworth.seshlog.index

import com.hedworth.seshlog.cache.FileStamp
import com.hedworth.seshlog.model.ConversationEntry
import com.hedworth.seshlog.model.ConversationMessage
import com.hedworth.seshlog.model.EntryKind
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Role
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
    fun `terms span titles paths and messages and title beats repeated body matches`() {
        val body = session("body", "Other", listOf("needle ".repeat(1000), "exact phrase"))
        val title = session("title", "Needle", listOf("exact phrase"))
        val query = "needle \"exact phrase\" " + tmp.root.name
        assertEquals(listOf("title", "body"), index.search(query, listOf(body, title)).map { it.session.id })
        assertTrue(index.search(query + " absent", listOf(body, title)).isEmpty())
    }

    @Test
    fun `recency breaks equal ranking and phrases beat incidental repetition`() {
        val old = session("old", "Other", listOf("word"), "2026-08-19T00:00:00Z")
        val recent = session("recent", "Other", listOf("word"))
        assertEquals(listOf("recent", "old"), index.search("word", listOf(old, recent)).map { it.session.id })
        val pathPhrase = old.copy(cwd = Path.of("/exact phrase"))
        val contentPhrase = recent.copy(id = "phrase")
        contents[contentPhrase.transcriptPath!!] = listOf("word exact phrase")
        assertEquals("phrase", index.search("word \"exact phrase\"", listOf(pathPhrase, contentPhrase)).first().session.id)
    }

    @Test
    fun `local title is searchable without losing the provider title`() {
        val s = session("renamed", "Original", emptyList())
        val renamed = ContentSearchIndex({ emptyList() }, { 1 }, { "Local name" })
        assertEquals(1, renamed.search("original", listOf(s)).size)
        assertEquals(1, renamed.search("local", listOf(s)).size)
        assertEquals("Original", s.title)
    }

    @Test
    fun `transient extraction failure retries without a stamp change`() {
        val s = session("retry", "Title", listOf("needle"))
        var calls = 0
        val retrying = ContentSearchIndex(
            extractor = { if (calls++ == 0) throw java.io.IOException("Temporarily locked") else listOf("needle") },
            contentStamp = { 1 },
        )
        assertTrue(retrying.search("needle", listOf(s)).isEmpty())
        assertEquals(0, retrying.size)
        assertEquals(1, retrying.search("needle", listOf(s)).size)
        retrying.search("needle", listOf(s))
        assertEquals(2, calls)
    }

    @Test
    fun `successful empty extraction is cached`() {
        val s = session("empty", "Title", emptyList())
        var calls = 0
        val empty = ContentSearchIndex(extractor = { calls++; emptyList() }, contentStamp = { 1 })
        repeat(2) { assertTrue(empty.search("needle", listOf(s)).isEmpty()) }
        assertEquals(1, calls)
        assertEquals(1, empty.size)
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
    fun `folded search preserves score and first snippet for mixed case and expanding unicode`() {
        val folded = session("folded", "Other", listOf("prefix İSTANBUL and istanbul"))
        val hits = index.search("iStAnBuL", listOf(folded))

        assertEquals(1, hits.size)
        assertEquals(2, hits.single().score)
        assertEquals("prefix İSTANBUL and istanbul", hits.single().snippet)

        val phrase = index.search("\"istanbul\"", listOf(folded)).single()
        assertEquals(22, phrase.score)
        assertEquals(hits.single().snippet, phrase.snippet)
    }

    @Test
    fun `overlapping terms preserve distinct range count and shortest first match`() {
        val overlapping = session("overlap", "Other", listOf("x".repeat(60) + "AB" + "z".repeat(60)))

        val hit = index.search("ab a", listOf(overlapping)).single()

        assertEquals(2, hit.score)
        assertTrue(hit.snippet!!.endsWith("…"))
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
        val path = s.transcriptPath!!
        contents[path] = listOf("first", "second")
        Files.writeString(path, "first\nsecond")
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
        assertEquals(0, index.retainedCharacters)
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
    fun `global budget evicts the least recently used session and reextracts it`() {
        val calls = HashMap<String, Int>()
        val sessions = listOf(
            session("a", "A", emptyList()),
            session("b", "B", emptyList()),
            session("c", "C", emptyList()),
        )
        val lru = ContentSearchIndex(
            extractor = { current -> calls[current.id] = (calls[current.id] ?: 0) + 1; listOf(current.id) },
            contentStamp = { 1 },
            characterBudget = 4,
        )

        val byId = sessions.associateBy { it.id }
        lru.search("a", listOf(byId.getValue("a")))
        lru.search("b", listOf(byId.getValue("b")))
        lru.search("a", listOf(byId.getValue("a")))
        lru.search("c", listOf(byId.getValue("c")))
        assertEquals(2, lru.size)
        assertEquals(4, lru.retainedCharacters)
        lru.search("b", listOf(byId.getValue("b")))

        assertEquals(2, calls.getValue("b"))
    }

    @Test
    fun `budget includes unsearchable entry text and folded searchable text`() {
        val s = session("tool-budget", "Title", emptyList())
        val entries = listOf(
            ConversationEntry(
                ConversationMessage(Role.ASSISTANT, "tool output", null),
                "tool-result", EntryKind.TOOL_RESULT,
            ),
            ConversationEntry(
                ConversationMessage(Role.ASSISTANT, "coverage text", null),
                "coverage", EntryKind.COVERAGE,
            ),
        )
        val expectedCharacters = "tool output".length * 2L + "coverage text".length
        val indexed = ContentSearchIndex(
            extractor = { error("structured entries are required") },
            contentStamp = { 1 },
            entryExtractor = { entries },
            characterBudget = expectedCharacters,
        )

        val hit = indexed.search("TOOL OUTPUT", listOf(s)).single()

        assertTrue(hit.toolMatch)
        assertTrue(hit.partial)
        assertEquals(expectedCharacters, indexed.retainedCharacters)
    }

    @Test
    fun `an entry larger than the global budget is searched transiently`() {
        var calls = 0
        val s = session("oversize", "Title", emptyList())
        val indexed = ContentSearchIndex(
            extractor = { calls++; listOf("oversized") },
            contentStamp = { 1 },
            characterBudget = 1,
        )

        assertEquals(1, indexed.search("oversized", listOf(s)).size)
        assertEquals(1, indexed.search("oversized", listOf(s)).size)

        assertEquals(2, calls)
        assertEquals(0, indexed.size)
        assertEquals(0, indexed.retainedCharacters)
    }

    @Test
    fun `repeated oversized candidate scan reextracts only the evicted entry`() {
        val calls = HashMap<String, Int>()
        val sessions = listOf(
            session("first", "First", emptyList()),
            session("second", "Second", emptyList()),
            session("third", "Third", emptyList()),
        )
        val indexed = ContentSearchIndex(
            extractor = { current ->
                calls[current.id] = (calls[current.id] ?: 0) + 1
                listOf("hit")
            },
            contentStamp = { 1 },
            characterBudget = 12,
        )

        val first = indexed.search("hit", sessions)
        val second = indexed.search("hit", sessions)

        assertEquals(sessions.map { it.id }, first.map { it.session.id })
        assertEquals(sessions.map { it.id }, second.map { it.session.id })
        assertEquals(4, calls.values.sum())
        assertEquals(2, calls.getValue("first"))
        assertEquals(1, calls.getValue("second"))
        assertEquals(1, calls.getValue("third"))
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
