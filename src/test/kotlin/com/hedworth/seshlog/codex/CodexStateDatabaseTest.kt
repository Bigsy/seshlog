package com.hedworth.seshlog.codex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.sqlite.JDBC
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

class CodexStateDatabaseTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `reads top level metadata, keeps mcp and excludes subagent sources`() {
        val db = create("codex_state_5.sql")
        val rows = CodexStateDatabase(db).read()!!

        assertEquals(listOf(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        ), rows.map { it.id })
        assertEquals("CLI title", rows[0].displayTitle)
        assertEquals("MCP name", rows[1].displayTitle)
        assertEquals("main", rows[0].gitBranch)
        assertEquals(1788000060000L, rows[1].updatedAtMillis)
    }

    @Test
    fun `missing table or required column asks caller to fall back`() {
        val missing = temporary.root.toPath().resolve("missing.sqlite")
        assertNull(CodexStateDatabase(missing).read())

        val db = temporary.root.toPath().resolve("partial.sqlite")
        JDBC.createConnection("jdbc:sqlite:$db", Properties()).use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE threads (rollout_path text)") }
        }
        assertNull(CodexStateDatabase(db).read())
    }

    @Test
    fun `malformed rows are skipped while valid metadata remains`() {
        val db = temporary.root.toPath().resolve("malformed.sqlite")
        JDBC.createConnection("jdbc:sqlite:$db", Properties()).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE threads (rollout_path text, source text, cwd text, title text, name text, archived integer, git_branch text, updated_at_ms integer)")
                statement.execute("INSERT INTO threads VALUES ('not-a-rollout.txt', 'cli', '/bad', 'Bad', NULL, 0, NULL, 1)")
                statement.execute("INSERT INTO threads VALUES ('/synthetic/rollout-2026-09-27T10-00-00-eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee.jsonl', 'cli', '/good', NULL, 'Good', 1, NULL, 2)")
            }
        }
        val rows = CodexStateDatabase(db).read()!!
        assertEquals(1, rows.size)
        assertEquals("Good", rows.single().displayTitle)
        assertTrue(rows.single().archived)
    }

    @Test
    fun `uses an optional database id when the schema provides one`() {
        val db = temporary.root.toPath().resolve("with-id.sqlite")
        JDBC.createConnection("jdbc:sqlite:$db", Properties()).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE threads (id text, rollout_path text, source text, cwd text, title text, name text, archived integer, git_branch text, updated_at_ms integer)")
                statement.execute("INSERT INTO threads VALUES ('database-id', '/synthetic/rollout-2026-09-27T10-00-00-eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee.jsonl', 'cli', '/project', 'Title', NULL, 0, NULL, 1)")
            }
        }

        val row = CodexStateDatabase(db).read()!!.single()
        assertEquals("database-id", row.id)
    }

    private fun create(resource: String): Path {
        val script = Paths.get(javaClass.getResource("/fixtures/$resource")!!.toURI())
        val statements = Files.readString(script)
            .lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(Regex(";\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val db = temporary.root.toPath().resolve("state_5.sqlite")
        JDBC.createConnection("jdbc:sqlite:$db", Properties()).use { connection ->
            connection.createStatement().use { statement -> statements.forEach { statement.execute(it) } }
        }
        return db
    }
}
