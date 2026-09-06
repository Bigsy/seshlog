package com.hedworth.seshlog.opencode

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.ConversationMessage
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.model.SessionProvider
import com.hedworth.seshlog.terminal.ShellQuote
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Read-only provider for [opencode](https://opencode.ai) sessions, read from its SQLite database.
 * Unlike Claude Code and Codex there is no per-session file (so `transcriptPath` is null and no
 * on-disk parse cache is needed) and no liveness signal (so sessions are never live).
 *
 * @param dataDir      resolves the opencode data directory, the one holding `opencode.db`
 * @param executable   resolves the `opencode` executable name/path
 * @param showArchived whether sessions the user archived inside opencode are listed
 */
class OpenCodeSessionProvider(
    private val dataDir: () -> Path,
    private val executable: () -> String,
    private val showArchived: () -> Boolean = { false },
) : SessionProvider {
    private val LOG = logger<OpenCodeSessionProvider>()

    override val kind: AgentKind = AgentKind.OPENCODE

    /** No lock or pid file to read, so no session is ever live and none takes part in restore. */
    override val detectsLiveSessions: Boolean = false

    private fun databaseFile(): Path = dataDir().resolve(DATABASE_FILE)
    private fun writeAheadLogFile(): Path = dataDir().resolve(WAL_FILE)
    private fun database() = OpenCodeDatabase(databaseFile())

    override fun dataRoot(): Path = dataDir()

    override fun isAvailable(): Boolean = Files.isRegularFile(databaseFile())

    /**
     * The database and its write-ahead log, never the data directory. In WAL mode opencode's writes
     * land in `opencode.db-wal` and the main file's mtime only moves at a checkpoint, so both are
     * needed. The directory itself must be left alone: every SQLite reader — this one included —
     * updates the wal-index in `opencode.db-shm`, so watching the directory would make each scan
     * trigger the next one forever. The directory also holds opencode's logs and snapshots, which
     * say nothing about sessions.
     */
    override fun watchRoots(): List<Path> = listOf(databaseFile(), writeAheadLogFile())

    override fun resumeCommand(session: Session): String =
        "${ShellQuote.quote(executable())} --session ${ShellQuote.quote(session.id)}"

    override fun forkCommand(session: Session): String = "${resumeCommand(session)} --fork"

    override fun scan(previous: Map<String, Session>): List<Session> {
        if (!isAvailable()) return emptyList()
        return try {
            val db = database()
            db.read { conn ->
                val rows = db.sessions(conn, includeArchived = showArchived())
                val result = ArrayList<Session>(rows.size)
                for (row in rows) {
                    val cwd = row.directory.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it) }.getOrNull() } ?: continue
                    val updated = Instant.ofEpochMilli(row.timeUpdated)
                    // Prompt stats only change together with time_updated: reuse the last scan's.
                    val prev = previous[row.id]?.takeIf { it.kind == kind && it.lastActivityAt == updated }
                    val stats = if (prev != null) {
                        OpenCodeDatabase.PromptStats(prev.promptCount, prev.promptTitle)
                    } else {
                        // One session with unreadable message JSON loses its prompt stats rather
                        // than emptying the whole list.
                        try {
                            db.promptStats(conn, row.id)
                        } catch (e: Exception) {
                            LOG.debug("Cannot read prompts of opencode session ${row.id}", e)
                            OpenCodeDatabase.PromptStats(0, null)
                        }
                    }
                    val explicitTitle = row.title.takeIf { it.isNotBlank() && !isPlaceholderTitle(it) }
                    result += Session(
                        kind = kind,
                        id = row.id,
                        title = explicitTitle ?: stats.promptTitle?.takeIf { it.isNotBlank() } ?: UNTITLED,
                        cwd = cwd,
                        gitBranch = null,
                        startedAt = Instant.ofEpochMilli(row.timeCreated),
                        lastActivityAt = updated,
                        transcriptPath = null,
                        isLive = false,
                        livePid = null,
                        promptTitle = stats.promptTitle,
                        promptCount = stats.promptCount,
                        hasExplicitTitle = explicitTitle != null,
                    )
                }
                result
            }
        } catch (e: Exception) {
            LOG.warn("Cannot read opencode database ${databaseFile()}", e)
            emptyList()
        }
    }

    override fun conversationText(session: Session): List<String> {
        val db = database()
        return db.read { conn -> db.conversationText(conn, session.id) }
    }

    override fun conversationMessages(session: Session): List<ConversationMessage> {
        val db = database()
        return db.read { conn -> db.conversationMessages(conn, session.id) }
    }

    override fun lastMessages(session: Session, count: Int): List<ConversationMessage> {
        val db = database()
        return db.read { conn -> db.lastMessages(conn, session.id, count) }
    }

    /** `time_updated` moves with every message, so it is the change token. */
    override fun contentStamp(session: Session): Any = session.lastActivityAt

    companion object {
        const val DATABASE_FILE = "opencode.db"
        const val WAL_FILE = "$DATABASE_FILE-wal"
        private const val UNTITLED = "Untitled session"

        /** Until opencode generates a title from the first exchange, the row holds `New session - <ISO timestamp>`. */
        internal fun isPlaceholderTitle(title: String): Boolean = title.startsWith("New session - ")
    }
}
