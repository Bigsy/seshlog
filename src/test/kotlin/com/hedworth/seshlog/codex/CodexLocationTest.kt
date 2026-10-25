package com.hedworth.seshlog.codex

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import com.hedworth.seshlog.cache.InfoStore
import com.hedworth.seshlog.model.LocationSource
import org.sqlite.JDBC
import java.util.Properties

class CodexLocationTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun lines() = javaClass.getResource("/fixtures/locations/codex_moves.jsonl")!!.readText().lines().filter { it.isNotBlank() }
    @Test fun `turn context replaces launch cwd before any user message`() {
        assertEquals("/synthetic/topic-a/src", CodexTranscriptParser.parseLines(lines().take(2).asSequence()).cwd)
        assertEquals("/synthetic/topic-b", CodexTranscriptParser.parseLines(lines().take(5).asSequence()).cwd)
        assertEquals("/synthetic/main", CodexTranscriptParser.parseLines(lines().asSequence()).cwd)
    }

    @Test fun `header retains launch identity and cache preserves turn context across appends`() {
        val path = tmp.newFile("rollout.jsonl").toPath()
        var previous: CodexTranscriptInfo? = null
        var offset = 0L
        for (line in lines()) {
            Files.writeString(path, line + "\n", StandardOpenOption.APPEND)
            val parsed = CodexTranscriptParser.parseIncremental(path, previous, offset)
            assertEquals(CodexTranscriptParser.parse(path), parsed.info)
            previous = CodexTranscriptInfoStore.fromJson(CodexTranscriptInfoStore.toJson(
                mapOf(path to InfoStore.Entry(Files.size(path), 1, parsed.info)))).values.single().info
            offset = parsed.completedOffset
            val header = CodexTranscriptParser.rolloutHeader(path)!!
            assertEquals("/synthetic/main", header.cwd)
            assertEquals("codex-moves", header.id)
            assertTrue(header.terminal)
        }
        assertEquals(LocationSource.CODEX_CONTEXT, previous!!.location!!.source)
    }

    @Test fun `stale DB cannot roll back context and unordered disagreement is explicit`() {
        val parsed = CodexTranscriptParser.parseLines(lines().take(2).asSequence())
        val provider = CodexSessionProvider({ tmp.root.toPath() }, { "codex" })
        val stale = CodexStateDatabase.ThreadRow("db-id", Path.of("rollout.jsonl"), "cli",
            "/synthetic/main", null, null, false, "main", 1)
        val retained = provider.metadata(stale, parsed)
        assertEquals(parsed.cwd, retained.cwd)
        assertEquals("codex-moves", retained.sessionId)
        assertFalse(retained.location!!.uncertain)
        val conflict = provider.metadata(stale.copy(updatedAtMillis = Long.MAX_VALUE), parsed)
        assertEquals(parsed.cwd, conflict.cwd)
        assertTrue(conflict.location!!.uncertain)
        assertFalse(provider.metadata(stale.copy(cwd = parsed.cwd), parsed).location!!.uncertain)
    }

    @Test fun `database-only location updates are read with an unchanged rollout and WAL is watched`() {
        val root = tmp.root.toPath()
        val rollout = Files.createDirectories(root.resolve("sessions")).resolve("rollout-example.jsonl")
        Files.writeString(rollout, lines().first() + "\n")
        val db = root.resolve("state_5.sqlite")
        JDBC.createConnection("jdbc:sqlite:$db", Properties()).use { connection ->
            connection.createStatement().use {
                it.execute("PRAGMA journal_mode=WAL")
                it.execute("CREATE TABLE threads (id text, rollout_path text, source text, cwd text, title text, name text, archived integer, git_branch text, updated_at_ms integer)")
            }
            connection.prepareStatement("INSERT INTO threads VALUES ('codex-moves', ?, 'cli', '/synthetic/topic-a', NULL, NULL, 0, 'HEAD', 1)").use {
                it.setString(1, rollout.toString()); it.executeUpdate()
            }
            val cache = root.resolve("system/cache.json")
            val provider = CodexSessionProvider({ root }, { "codex" }, cache)
            val first = provider.scan(emptyMap()).single()
            assertEquals("/synthetic/topic-a", first.cwd.toString())
            connection.createStatement().use { it.executeUpdate("UPDATE threads SET cwd = '/synthetic/topic-b'") }
            assertEquals("/synthetic/topic-b", provider.scan(mapOf(first.id to first)).single().cwd.toString())
            provider.flush()
            assertEquals("/synthetic/topic-b", CodexSessionProvider({ root }, { "codex" }, cache).scan(emptyMap()).single().cwd.toString())
            assertTrue(provider.watchRoots().contains(root.resolve("state_5.sqlite-wal")))
            assertFalse(provider.watchRoots().contains(root.resolve("state_5.sqlite-shm")))
        }
        Files.writeString(db, "incompatible")
        assertEquals("/synthetic/main", CodexSessionProvider({ root }, { "codex" }).scan(emptyMap()).single().cwd.toString())
    }

    @Test fun `partial and malformed contexts preserve prior metadata and shell paths are ignored`() {
        val before = lines().take(2)
        val bad = listOf("{bad", "{\"type\":\"turn_context\",\"payload\":{\"cwd\":null}}",
            "{\"type\":\"turn_context\",\"payload\":{\"cwd\":\"relative\"}}", lines()[2])
        val result = CodexTranscriptParser.parseLines((before + bad).asSequence())
        assertEquals("/synthetic/topic-a/src", result.cwd)
        assertEquals(0, result.promptCount)
    }
}
