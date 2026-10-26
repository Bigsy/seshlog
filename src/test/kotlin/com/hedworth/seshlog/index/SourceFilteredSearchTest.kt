package com.hedworth.seshlog.index

import com.google.gson.JsonParser
import com.hedworth.seshlog.index.lexical.*
import com.hedworth.seshlog.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Properties
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.sqlite.JDBC

class SourceFilteredSearchTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun session(id: String = "one", title: String = "Unrelated", kind: AgentKind = AgentKind.CLAUDE_CODE) =
        Session(kind, id, title, Path.of("/synthetic/project"), null, null, Instant.EPOCH, null, false, null, null, 1, true)
    private fun entry(id: String, text: String, source: SearchEntrySource) = ConversationEntry(
        ConversationMessage(if (source == SearchEntrySource.USER || source == SearchEntrySource.TOOL_RESULT) Role.USER else Role.ASSISTANT, text, null),
        id, when (source) {
            SearchEntrySource.USER, SearchEntrySource.ASSISTANT -> EntryKind.DIALOGUE
            SearchEntrySource.TOOL_CALL -> EntryKind.TOOL_CALL
            SearchEntrySource.TOOL_RESULT -> EntryKind.TOOL_RESULT
        }, toolName = "heading_marker")
    private fun index(entries: () -> List<ConversationEntry>, stamp: () -> Int = { 1 }, db: Path? = null) =
        ContentSearchIndex({ error("structured") }, { stamp() }, entryExtractor = { entries() }, storeFactory = { LexicalSearchStore.open(db) })

    @Test fun `a source that fails reading and changes stamp is counted once in coverage`() {
        var stamp = 1
        var reads = 0
        ContentSearchIndex({ emptyList() }, { stamp }, characterBudget = 0, entryExtractor = {
            if (reads++ == 0) listOf(entry("u", "needle", SearchEntrySource.USER)) else {
                stamp++
                error("synthetic unavailable source")
            }
        }).use { index ->
            val result = index.searchSnapshot("needle", listOf(session()))
            assertTrue(result.hits.isEmpty())
            assertEquals(1, result.coverage.skipped)
            assertTrue(result.coverage.partial)
        }
    }

    @Test fun `all selections restrict every source with provider collisions and empty corpus`() {
        val sources = SearchEntrySource.entries
        val entries = sources.map { entry(it.name, "marker_${it.name}", it) }
        index({ entries }).use { index ->
            val a = session()
            val b = session(kind = AgentKind.CODEX)
            assertTrue(index.search("marker", emptyList()).isEmpty())
            for (bits in 0..7) {
                val options = SearchSources(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
                for (source in sources) {
                    val hits = index.search("marker_${source.name}", listOf(a, b), options)
                    assertEquals("$options $source", if (options.includes(source)) 2 else 0, hits.size)
                    hits.forEach { hit ->
                        assertEquals(source.matchSource, hit.primary.source)
                        assertEquals(source.name, hit.entryId)
                        assertEquals(hit.range, hit.primary.range)
                        assertEquals(hit.entryHash, hit.primary.entryHash)
                        assertTrue(hit.snippet!!.contains("marker_${source.name}"))
                        assertEquals(options, hit.sources)
                    }
                }
            }
            assertTrue(index.search("heading_marker", listOf(a), SearchSources.ALL).isEmpty())
        }
    }

    @Test fun `frozen fixture primary expectations execute against production retrieval`() {
        val fixture = javaClass.getResourceAsStream("/fixtures/search/source-contract.json")!!.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        val entries = fixture["entries"].asJsonArray.map { row ->
            val e = row.asJsonObject
            ConversationEntry(ConversationMessage(Role.valueOf(e["role"].asString), e["text"].asString, null), e["id"].asString,
                EntryKind.valueOf(e["kind"].asString), toolName = e["tool"]?.asString)
        }
        val options = mapOf("conversation" to SearchSources.DEFAULT, "all" to SearchSources.ALL,
            "user" to SearchSources(true, false, false), "metadata" to SearchSources.METADATA_ONLY)
        index({ entries }).use { index ->
            fixture["cases"].asJsonArray.forEach { row ->
                val c = row.asJsonObject
                val hits = index.search(c["query"].asString, listOf(session()), options.getValue(c["sources"].asString))
                if (c["entries"].asJsonArray.isEmpty) assertTrue(c.toString(), hits.isEmpty()) else {
                    assertEquals(1, hits.size)
                    assertEquals(c["primary"].asString, hits.single().primary.source.name)
                }
            }
        }
    }

    @Test fun `phrase eligibility and technical Unicode stopword and substring lanes share selection`() {
        val entries = listOf(
            entry("user", "cache invalidation", SearchEntrySource.USER),
            entry("assistant", "bounded timeout", SearchEntrySource.ASSISTANT),
            entry("tool", "database locked C++ C# /tmp/example.kt İSTANBUL 你好世界 the and of SearchRequestScope", SearchEntrySource.TOOL_RESULT))
        index({ entries }).use { index ->
            val s = listOf(session())
            assertTrue(index.search("\"database locked\" timeout", s).isEmpty())
            assertEquals(1, index.search("\"database locked\" timeout", s, SearchSources.ALL).size)
            assertEquals(1, index.search("\"cache invalidation\" \"bounded timeout\"", s).size)
            assertTrue(index.search("\"cache invalidation\" \"bounded timeout\"", s, SearchSources(true, false, false)).isEmpty())
            assertTrue(index.search("\"invalidation bounded\"", s, SearchSources.ALL).isEmpty())
            for (query in listOf("C++", "C#", "/tmp/example.kt", "istanbul", "你好", "\"the and of\"", "questsc")) {
                assertTrue(query, index.search(query, s).isEmpty())
                val hit = index.search(query, s, SearchSources.ALL).single()
                assertEquals(query, "tool", hit.entryId)
                assertEquals(SearchMatchSource.TOOL_RESULT, hit.primary.source)
            }
        }
    }

    @Test fun `disabled populations cannot change scores ordering exact tier or evidence`() {
        var revision = 1
        var toolText = "needle"
        val body = listOf(entry("user", "needle orchard", SearchEntrySource.USER), entry("assistant", "needle kestrel", SearchEntrySource.ASSISTANT))
        index({ body + entry("tool", toolText, SearchEntrySource.TOOL_RESULT) }, { revision }).use { index ->
            val sessions = listOf(session("a"), session("b"))
            val before = index.search("needle", sessions)
            toolText = "needle ".repeat(3000) + "C++"
            revision++
            val after = index.search("needle", sessions)
            assertEquals(before, after)
            assertTrue(index.search("C++", sessions).isEmpty())
            assertEquals(2, index.search("C++", sessions, SearchSources.ALL).size)
        }
    }

    @Test fun `protected technical body evidence outranks metadata and incidental results`() {
        val exact = session("exact", "fix")
        val incidental = session("incidental", "fix").copy(lastActivityAt = Instant.EPOCH.plusSeconds(1))
        val entries = mapOf(
            exact.id to listOf(entry("exact-entry", "ERR_42 fix", SearchEntrySource.USER)),
            incidental.id to listOf(entry("incidental-entry", "fix ERR_42", SearchEntrySource.USER)),
        )
        lateinit var store: LexicalSearchStore
        ContentSearchIndex({ emptyList() }, { 1 }, entryExtractor = { entries.getValue(it.id) },
            storeFactory = { LexicalSearchStore.open().also { store = it } }).use { index ->
            val hits = index.search("ERR_42 fix", listOf(incidental, exact))
            assertEquals(SearchMatchSource.TITLE, store.scores(RetrievalQuery.compile("ERR_42 fix"),
                listOf(SearchSessionKey(exact.kind, exact.id))).single().source)
            assertEquals(listOf("exact", "incidental"), hits.map { it.session.id })
            val hit = hits.first()
            assertTrue(hit.exact)
            assertEquals(SearchMatchSource.USER, hit.primary.source)
            assertEquals("exact-entry", hit.entryId)
            assertEquals(0..9, hit.range)
            assertEquals(0 to (0..9), validatedPassage(hit, entries.getValue(exact.id)))
            assertFalse(hits.last().exact)
            assertEquals(SearchMatchSource.TITLE, hits.last().primary.source)
        }
    }

    @Test fun `protected phrase and technical evidence respect enabled body sources`() {
        val s = session(title = "fix")
        val entries = listOf(
            entry("user", "fix orchard", SearchEntrySource.USER),
            entry("tool", "ERR_42 fix", SearchEntrySource.TOOL_RESULT),
        )
        index({ entries }).use { index ->
            val phrase = index.search("\"fix\" orchard", listOf(s)).single()
            assertTrue(phrase.exact)
            assertEquals(SearchMatchSource.USER, phrase.primary.source)
            assertEquals("user", phrase.entryId)
            assertEquals(0..10, phrase.range)

            val excluded = index.search("ERR_42 fix", listOf(s)).single()
            assertFalse(excluded.exact)
            assertEquals(SearchMatchSource.TITLE, excluded.primary.source)
            val included = index.search("ERR_42 fix", listOf(s), SearchSources.ALL).single()
            assertTrue(included.exact)
            assertEquals(SearchMatchSource.TOOL_RESULT, included.primary.source)
            assertEquals("tool", included.entryId)
            assertEquals(0..9, included.range)
            val metadata = index.search("ERR_42 fix", listOf(s), SearchSources.METADATA_ONLY).single()
            assertFalse(metadata.exact)
            assertEquals(SearchMatchSource.TITLE, metadata.primary.source)
            assertNull(metadata.entryId)
        }
    }

    @Test fun `protected body evidence is verified beyond the result limit`() {
        val incidental = (0..100).map {
            session("incidental-$it", "fix").copy(lastActivityAt = Instant.EPOCH.plusSeconds(1))
        }
        val exact = session("exact", "fix")
        ContentSearchIndex({ emptyList() }, { 1 }, entryExtractor = { session ->
            listOf(entry("body", if (session.id == exact.id) "ERR_42 fix" else "fix ERR_42", SearchEntrySource.USER))
        }).use { index ->
            val hits = index.search("ERR_42 fix", incidental + exact)
            assertEquals(100, hits.size)
            assertEquals(exact.id, hits.first().session.id)
            assertTrue(hits.first().exact)
            assertEquals(SearchMatchSource.USER, hits.first().primary.source)
        }
    }

    @Test fun `failed protected evidence read retains metadata with partial coverage`() {
        var reads = 0
        ContentSearchIndex({ emptyList() }, { 1 }, characterBudget = 0, entryExtractor = {
            if (reads++ == 0) listOf(entry("user", "ERR_42 fix", SearchEntrySource.USER))
            else error("synthetic source unavailable")
        }).use { index ->
            val result = index.searchSnapshot("ERR_42 fix", listOf(session(title = "fix")))
            val hit = result.hits.single()
            assertEquals(2, reads)
            assertEquals(1, result.coverage.skipped)
            assertTrue(result.coverage.partial)
            assertTrue(hit.partial)
            assertFalse(hit.exact)
            assertEquals(SearchMatchSource.TITLE, hit.primary.source)
            assertNull(hit.entryId)
        }
    }

    @Test fun `protected exact metadata avoids unnecessary body rereads`() {
        var reads = 0
        ContentSearchIndex({ emptyList() }, { 1 }, characterBudget = 0, entryExtractor = {
            reads++
            listOf(entry("user", "ERR_42 fix", SearchEntrySource.USER))
        }).use { index ->
            val hit = index.search("ERR_42 fix", listOf(session(title = "ERR_42 fix"))).single()
            assertTrue(hit.exact)
            assertEquals(SearchMatchSource.TITLE, hit.primary.source)
            assertEquals(1, reads)
        }
    }

    @Test fun `excluded high scoring tools never consume the result limit`() {
        val sessions = (0..120).map { session(it.toString()) }
        ContentSearchIndex({ emptyList() }, { 1 }, entryExtractor = { s ->
            listOf(entry("e", "needle", if (s.id == "120") SearchEntrySource.USER else SearchEntrySource.TOOL_RESULT))
        }).use { index ->
            assertEquals("120", index.search("needle", sessions).single().session.id)
        }
    }

    @Test fun `best body uses max across populations and deterministic source ties`() {
        val key = SearchSessionKey(AgentKind.PI, "one")
        LexicalSearchStore.open().use { store ->
            store.replace(key, "s", null, searchHash("m"), false, "", "", "", SearchEntrySource.entries.map {
                LexicalPassage(it.name, 0, 6, searchHash("needle"), "needle", it)
            })
            val all = store.scores(RetrievalQuery.compile("needle"), listOf(key), SearchSources.ALL).single()
            assertEquals(1e-6, all.score, 1e-12)
            assertEquals(SearchMatchSource.USER, all.source)
            assertEquals(all.score, store.scores(RetrievalQuery.compile("needle"), listOf(key)).single().score, 0.0)
        }
    }

    @Test fun `metadata only never extracts or marks stale bodies current and labels actual variants`() {
        var reads = 0
        val db = tmp.root.toPath().resolve("metadata.sqlite")
        val s = session(title = "Provider needle").copy(cwd = Path.of("/path_marker/database locked"))
        ContentSearchIndex({ reads++; error("unavailable") }, { 1 }, { "Displayed override" }, storeFactory = { LexicalSearchStore.open(db) }).use { index ->
            val title = index.search("needle", listOf(s), SearchSources.METADATA_ONLY).single()
            assertEquals(SearchMetadataVariant.PROVIDER_TITLE, title.primary.metadataVariant)
            assertTrue(title.snippet!!.contains("Provider needle"))
            assertNull(title.entryId)
            assertNull(title.range)
            assertEquals(SearchMetadataVariant.LOCAL_TITLE, index.search("Displayed", listOf(s), SearchSources.METADATA_ONLY).single().primary.metadataVariant)
            val path = index.searchSnapshot("\"database locked\"", listOf(s), SearchSources.METADATA_ONLY)
            assertFalse(path.coverage.partial)
            assertEquals(SearchMatchSource.PROJECT_PATH, path.hits.single().primary.source)
            val literalPath = s.copy(cwd = Path.of("/the and of/+-"))
            for (query in listOf("\"the and of\"", "+-")) {
                val hit = index.search(query, listOf(literalPath), SearchSources.METADATA_ONLY).single()
                assertEquals(SearchMatchSource.PROJECT_PATH, hit.primary.source)
                assertNull(hit.entryId)
            }
            assertEquals(0, reads)
            assertEquals(1, index.search("needle", listOf(s)).size)
            assertEquals(1, reads)
            assertTrue(index.searchSnapshot("absent", listOf(s)).coverage.partial)
        }
        LexicalSearchStore.open(db).use { assertNull(it.sources().values.single().stamp) }
    }

    @Test fun `toggles reuse all postings and category replacement rollback is atomic`() {
        val key = SearchSessionKey(AgentKind.PI, "one")
        val db = tmp.root.toPath().resolve("atomic.sqlite")
        val q = RetrievalQuery.compile("needle")
        LexicalSearchStore.open(db).use { store ->
            fun replace(source: SearchEntrySource, before: () -> Unit = {}) = store.replace(key, source.name, null, searchHash("m"), false, "", "", "",
                listOf(LexicalPassage("entry", 0, 6, searchHash("needle"), "needle", source)), before)
            replace(SearchEntrySource.USER)
            assertThrows(IllegalStateException::class.java) { replace(SearchEntrySource.TOOL_RESULT) { error("cancel") } }
            assertEquals(SearchMatchSource.USER, store.scores(q, listOf(key)).single().source)
            replace(SearchEntrySource.TOOL_RESULT)
            assertTrue(store.scores(q, listOf(key)).isEmpty())
            assertEquals(SearchMatchSource.TOOL_RESULT, store.scores(q, listOf(key), SearchSources.ALL).single().source)
            assertEquals(1, store.passageCount)
        }
        var reads = 0
        ContentSearchIndex({ reads++; listOf("body") }, { SearchEntrySource.TOOL_RESULT.name }, storeFactory = { LexicalSearchStore.open(db) }).use { index ->
            // Store stamps are hashes in production; a fresh synthetic source is indexed once.
            repeat(2) { index.search("needle", listOf(session()), SearchSources.ALL); index.search("needle", listOf(session())) }
            assertEquals(1, reads)
        }
    }

    @Test fun `schema two and future stores are preserved with filtered temporary fallback and explicit rebuild`() {
        for (version in listOf(2, 999)) {
            val db = tmp.newFolder("v$version").toPath().resolve("lexical.sqlite")
            JDBC.createConnection("jdbc:sqlite:$db", Properties()).use { c -> c.createStatement().use { it.execute("PRAGMA user_version=$version"); it.execute("CREATE TABLE preserved(value TEXT)") } }
            val before = Files.readAllBytes(db)
            index({ listOf(entry("tool", "tool_marker", SearchEntrySource.TOOL_CALL)) }, db = db).use { index ->
                val result = index.searchSnapshot("tool_marker", listOf(session()))
                assertTrue(result.hits.isEmpty())
                assertTrue(result.availability!!.contains(if (version == 2) "rebuild" else "unsupported", ignoreCase = true))
                assertEquals(1, index.search("tool_marker", listOf(session()), SearchSources.ALL).size)
                assertArrayEquals(before, Files.readAllBytes(db))
                index.rebuildSavedIndex { LexicalSearchStore.reset(db) }
                assertEquals(1, index.search("tool_marker", listOf(session()), SearchSources.ALL).size)
            }
        }
    }

    @Test fun `intentional exclusion is complete while shared retention loss remains partial`() {
        val user = entry("user", "needle", SearchEntrySource.USER)
        val tool = entry("tool", "tool_marker", SearchEntrySource.TOOL_CALL)
        var entries = listOf(tool, user)
        var stamp = 1
        index({ entries }, { stamp }).use { index ->
            assertFalse(index.searchSnapshot("absent", listOf(session())).coverage.partial)
            entries = entries + ConversationEntry(ConversationMessage(Role.ASSISTANT, "coverage_marker", null), "coverage", EntryKind.COVERAGE)
            stamp++
            assertTrue(index.searchSnapshot("absent", listOf(session())).coverage.partial)
            assertFalse(index.searchSnapshot("absent", listOf(session()), SearchSources.METADATA_ONLY).coverage.partial)
            assertTrue(index.search("coverage_marker", listOf(session()), SearchSources.ALL).isEmpty())
        }
    }

    @Test fun `imported classified postings validate local sources and retain exact navigation without tokenizing`() {
        val entries = SearchEntrySource.entries.map { entry(it.name, "marker_${it.name}", it) }
        val file = tmp.root.toPath().resolve("synthetic-source.txt")
        Files.writeString(file, entries.joinToString("\n") { "${it.kind} ${it.message.role} ${it.text}" })
        val session = session(title = "Metadata marker").copy(transcriptPath = file)
        var reads = 0
        var store: LexicalSearchStore? = null
        fun index(path: Path) = ContentSearchIndex({ emptyList() }, { 1 }, entryExtractor = { reads++; entries },
            storeFactory = { LexicalSearchStore.open(path).also { store = it } })
        val snapshot = tmp.root.toPath().resolve("classified-export.sqlite")
        val expected = index(tmp.root.toPath().resolve("original.sqlite")).use {
            val hit = it.search("marker_TOOL_RESULT", listOf(session), SearchSources.ALL).single()
            it.export(snapshot)
            hit
        }
        reads = 0
        index(tmp.root.toPath().resolve("imported.sqlite")).use {
            assertEquals(1, it.importSnapshot(snapshot))
            assertEquals(1, it.search("Metadata", listOf(session)).size)
            assertEquals(0, reads)
            val actual = it.search("marker_TOOL_RESULT", listOf(session), SearchSources.ALL).single()
            assertEquals(expected, actual)
            assertEquals(3 to (0 until "marker_TOOL_RESULT".length), validatedPassage(actual, entries))
            assertTrue(it.search("marker_TOOL_RESULT", listOf(session)).isEmpty())
            assertEquals(1, reads)
            assertEquals(0L, store!!.tokenizations)
        }
    }
}
