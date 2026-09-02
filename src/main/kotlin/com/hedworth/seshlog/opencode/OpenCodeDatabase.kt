package com.hedworth.seshlog.opencode

import com.hedworth.seshlog.claude.TranscriptParser
import com.hedworth.seshlog.claude.TranscriptTextExtractor
import com.hedworth.seshlog.model.ConversationMessage
import com.hedworth.seshlog.model.Role
import org.sqlite.JDBC
import org.sqlite.SQLiteConfig
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant

/**
 * Read-only access to opencode's SQLite database (`<data>/opencode.db`, WAL mode). Owns the JDBC
 * URL and every SQL statement so the provider stays readable and the queries are testable against
 * a fixture database.
 *
 * Schema (opencode 1.18): `session(id, parent_id, directory, title, time_created, time_updated,
 * time_archived, …)`, `message(id, session_id, time_created, data)` with `data.role`, and
 * `part(id, message_id, session_id, data)` with `data.type` — only `text` parts carry conversation
 * text, in `data.text`. A text part flagged `synthetic` was injected by opencode itself (a file it
 * read back into the prompt, a tool-call echo) rather than typed by the user, so it is skipped —
 * the same treatment tool results get in the Claude Code and Codex transcripts. Times are epoch
 * millis.
 *
 * Connections are opened per call and closed: opencode may replace the file on upgrade, and WAL
 * mode lets a reader coexist with opencode writing. The database is never opened with
 * `immutable=1`, which would skip locking and risk inconsistent reads from a live file.
 */
class OpenCodeDatabase(private val file: Path) {

    data class SessionRow(
        val id: String,
        val directory: String,
        val title: String,
        val timeCreated: Long,
        val timeUpdated: Long,
    )

    /** Real prompts of a session: how many, and the first one reduced to a title line. */
    data class PromptStats(val promptCount: Int, val promptTitle: String?)

    /** Run [block] against a fresh read-only connection, then close it. SQL errors propagate. */
    fun <T> read(block: (Connection) -> T): T {
        val config = SQLiteConfig().apply {
            setReadOnly(true)
            setBusyTimeout(BUSY_TIMEOUT_MS)
        }
        // Instantiated directly rather than through DriverManager: plugin classloaders do not
        // reliably surface ServiceLoader-registered drivers.
        val connection = JDBC.createConnection("jdbc:sqlite:$file", config.toProperties())
            ?: throw SQLException("Not a SQLite JDBC URL for $file")
        return connection.use(block)
    }

    /** Top-level sessions (children with a `parent_id` are subagents, hidden like Claude sidechains). */
    fun sessions(conn: Connection, includeArchived: Boolean): List<SessionRow> {
        val sql = "SELECT id, directory, title, time_created, time_updated FROM session WHERE parent_id IS NULL" +
            (if (includeArchived) "" else " AND time_archived IS NULL")
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val rows = ArrayList<SessionRow>()
                while (rs.next()) {
                    rows += SessionRow(
                        id = rs.getString(1) ?: continue,
                        directory = rs.getString(2) ?: "",
                        title = rs.getString(3) ?: "",
                        timeCreated = rs.getLong(4),
                        timeUpdated = rs.getLong(5),
                    )
                }
                return rows
            }
        }
    }

    /** User messages with at least one non-blank text part, in order; the first one becomes the prompt title. */
    fun promptStats(conn: Connection, sessionId: String): PromptStats {
        val sql = """
            SELECT m.id, json_extract(m.data, '$.role'), m.time_created, json_extract(p.data, '$.text')
            FROM message m JOIN part p ON p.message_id = m.id
            WHERE m.session_id = ?
              AND json_extract(m.data, '$.role') = 'user'
              AND json_extract(p.data, '$.type') = 'text'
              AND ifnull(json_extract(p.data, '$.synthetic'), 0) = 0
            ORDER BY m.time_created, m.id, p.id
        """.trimIndent()
        conn.prepareStatement(sql).use { st ->
            st.setString(1, sessionId)
            st.executeQuery().use { rs ->
                val prompts = fold(rs)
                return PromptStats(prompts.size, prompts.firstOrNull()?.let { TranscriptParser.promptToTitle(it.text) })
            }
        }
    }

    /** Every visible message text of the session, one string per message, oldest first. */
    fun conversationText(conn: Connection, sessionId: String): List<String> {
        val sql = """
            SELECT m.id, json_extract(m.data, '$.role'), m.time_created, json_extract(p.data, '$.text')
            FROM message m JOIN part p ON p.message_id = m.id
            WHERE m.session_id = ?
              AND json_extract(p.data, '$.type') = 'text'
              AND ifnull(json_extract(p.data, '$.synthetic'), 0) = 0
            ORDER BY m.time_created, m.id, p.id
        """.trimIndent()
        conn.prepareStatement(sql).use { st ->
            st.setString(1, sessionId)
            st.executeQuery().use { rs -> return fold(rs, TranscriptTextExtractor.MAX_CHARS_PER_TRANSCRIPT).map { it.text } }
        }
    }

    /** The last [count] messages that have text, oldest first. */
    fun lastMessages(conn: Connection, sessionId: String, count: Int): List<ConversationMessage> {
        if (count <= 0) return emptyList()
        val sql = """
            WITH recent AS (
                SELECT m.id AS id, m.time_created AS time_created, json_extract(m.data, '$.role') AS role
                FROM message m
                WHERE m.session_id = ?
                  AND json_extract(m.data, '$.role') IN ('user', 'assistant')
                  AND EXISTS (
                      SELECT 1 FROM part p
                      WHERE p.message_id = m.id
                        AND json_extract(p.data, '$.type') = 'text'
                        AND ifnull(json_extract(p.data, '$.synthetic'), 0) = 0
                        AND trim(json_extract(p.data, '$.text'), $WHITESPACE) <> ''
                  )
                ORDER BY m.time_created DESC, m.id DESC
                LIMIT ?
            )
            SELECT recent.id, recent.role, recent.time_created, json_extract(p.data, '$.text')
            FROM recent JOIN part p ON p.message_id = recent.id
            WHERE json_extract(p.data, '$.type') = 'text'
              AND ifnull(json_extract(p.data, '$.synthetic'), 0) = 0
            ORDER BY recent.time_created, recent.id, p.id
        """.trimIndent()
        conn.prepareStatement(sql).use { st ->
            st.setString(1, sessionId)
            st.setInt(2, count)
            st.executeQuery().use { rs -> return fold(rs) }
        }
    }

    /**
     * Folds rows `(message id, role, time_created, text)` — ordered by message — into one
     * [ConversationMessage] per message, joining its text parts. Messages with an unknown role or
     * only blank text are dropped. Stops once [maxChars] of text have been collected.
     */
    private fun fold(rs: ResultSet, maxChars: Int = Int.MAX_VALUE): List<ConversationMessage> {
        val out = ArrayList<ConversationMessage>()
        var chars = 0
        var currentId: String? = null
        var role: Role? = null
        var time = 0L
        val parts = ArrayList<String>()

        fun flush() {
            val r = role
            if (r != null && parts.isNotEmpty()) {
                val text = parts.joinToString("\n")
                out += ConversationMessage(r, text, Instant.ofEpochMilli(time))
                chars += text.length
            }
            parts.clear()
        }

        while (rs.next()) {
            val id = rs.getString(1)
            if (id != currentId) {
                flush()
                if (chars >= maxChars) break
                currentId = id
                role = roleOf(rs.getString(2))
                time = rs.getLong(3)
            }
            val text = rs.getString(4)?.takeIf { it.isNotBlank() } ?: continue
            parts += text
        }
        flush()
        return out
    }

    private fun roleOf(value: String?): Role? = when (value) {
        "user" -> Role.USER
        "assistant" -> Role.ASSISTANT
        else -> null
    }

    companion object {
        /** How long a reader waits for opencode to finish a write before the query fails. */
        private const val BUSY_TIMEOUT_MS = 2_000

        /**
         * Characters SQLite's `trim(X, Y)` should strip, since one-argument `trim` removes spaces
         * only. Without tabs and newlines a message whose text is just a line break would pass the
         * "has visible text" filter, take one of the requested slots, and then be dropped by
         * [fold] — leaving the preview short. [fold] stays the authority on what is blank.
         */
        private const val WHITESPACE = "' ' || char(9) || char(10) || char(13)"
    }
}
