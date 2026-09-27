package com.hedworth.seshlog.codex

import org.sqlite.JDBC
import org.sqlite.SQLiteConfig
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.nio.file.Files

/**
 * Read-only metadata access to Codex CLI's state_5.sqlite. A null result means that this version
 * of Codex has no usable threads table, so callers must fall back to rollout parsing.
 *
 * The database is deliberately used for metadata only. Conversation text, prompt counts and
 * activity still come from the rollout, whose format is shared with older Codex versions.
 */
class CodexStateDatabase(private val file: Path) {
    data class ThreadRow(
        val id: String,
        val rolloutPath: Path,
        val source: String?,
        val cwd: String?,
        val title: String?,
        val name: String?,
        val archived: Boolean,
        val gitBranch: String?,
        val updatedAtMillis: Long?,
    ) {
        val displayTitle: String? get() = title?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }
    }

    /** Returns rows for top-level threads, or null when the schema is unavailable/incompatible. */
    fun read(): List<ThreadRow>? {
        return try {
            if (!Files.isRegularFile(file)) null
            else open().use { connection -> readRows(connection) }
        } catch (_: Exception) {
            null
        }
    }

    private fun open(): Connection {
        val config = SQLiteConfig().apply {
            setReadOnly(true)
            setBusyTimeout(BUSY_TIMEOUT_MS)
        }
        return JDBC.createConnection("jdbc:sqlite:$file", config.toProperties())
            ?: throw SQLException("Not a SQLite JDBC URL for $file")
    }

    private fun readRows(connection: Connection): List<ThreadRow> {
        // The optional id column appeared in some state_5 schemas. Keep the explicit legacy
        // projection as a fallback because the remaining metadata columns are the stable seam.
        return try {
            queryRows(connection, includeId = true)
        } catch (_: SQLException) {
            queryRows(connection, includeId = false)
        }
    }

    private fun queryRows(connection: Connection, includeId: Boolean): List<ThreadRow> {
        // Keep the projection explicit: a missing table or any required column causes a null
        // result and lets the provider use its complete rollout parser.
        val sql = """
            SELECT ${if (includeId) "id, " else ""}rollout_path, source, cwd, title, name, archived, git_branch, updated_at_ms
            FROM threads
            WHERE rollout_path IS NOT NULL
            ORDER BY updated_at_ms, rollout_path
        """.trimIndent()
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                val rows = ArrayList<ThreadRow>()
                while (result.next()) {
                    val rawPath = result.getString("rollout_path") ?: continue
                    val path = runCatching { Path.of(rawPath) }.getOrNull() ?: continue
                    val source = result.getString("source")
                    if (isInternalSource(source)) continue
                    val id = (if (includeId) result.getString("id")?.takeIf { it.isNotBlank() } else null)
                        ?: CodexSessionProvider.idFromFileName(path)
                        ?: continue
                    val updated = result.getLong("updated_at_ms").takeUnless { result.wasNull() }
                    rows += ThreadRow(
                        id = id,
                        rolloutPath = path,
                        source = source,
                        cwd = result.getString("cwd"),
                        title = result.getString("title"),
                        name = result.getString("name"),
                        archived = result.getBoolean("archived"),
                        gitBranch = result.getString("git_branch"),
                        updatedAtMillis = updated,
                    )
                }
                return rows
            }
        }
    }

    private fun isInternalSource(source: String?): Boolean {
        val value = source?.lowercase() ?: return false
        return "thread_spawn" in value || "guardian" in value ||
            "subagent" in value || value == "review" || "\"review\"" in value
    }

    companion object {
        private const val BUSY_TIMEOUT_MS = 2_000
    }
}
