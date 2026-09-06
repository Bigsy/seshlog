package com.hedworth.seshlog.claude

import com.hedworth.seshlog.cache.FileBackedParseCache
import com.hedworth.seshlog.cache.FileStamp
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.ConversationMessage
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.model.SessionProvider
import com.hedworth.seshlog.terminal.ShellQuote
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

/**
 * Reads Claude Code's local data (`~/.claude`). Read-only, always.
 *
 * @param dataDir     resolves the Claude data directory (a setting; may change between scans)
 * @param executable  resolves the `claude` executable name/path (a setting)
 * @param cacheFile   where to persist the parsed-transcript cache between IDE runs (null: in-memory only)
 */
class ClaudeCodeSessionProvider(
    private val dataDir: () -> Path,
    private val executable: () -> String,
    cacheFile: Path? = null,
) : SessionProvider {
    private val LOG = logger<ClaudeCodeSessionProvider>()

    override val kind: AgentKind = AgentKind.CLAUDE_CODE

    private val cache = FileBackedParseCache(TranscriptInfoStore, cacheFile, TranscriptParser::parse,
        onReadFailure = { path, error -> scanProblem = "Cannot read $path: ${error.message}" },
    )

    override fun dataRoot(): Path = dataDir()

    private fun projectsDir(): Path = dataDir().resolve("projects")
    private fun sessionsDir(): Path = dataDir().resolve("sessions")

    @Volatile override var scanProblem: String? = null
        private set
    override fun storagePath(): Path = projectsDir()

    override fun isAvailable(): Boolean = Files.isDirectory(projectsDir())

    override fun watchRoots(): List<Path> = listOf(projectsDir(), sessionsDir())

    override fun resumeCommand(session: Session): String =
        "${ShellQuote.quote(executable())} --resume ${ShellQuote.quote(session.id)}"

    override fun forkCommand(session: Session): String = "${resumeCommand(session)} --fork-session"

    override fun scan(previous: Map<String, Session>): List<Session> {
        scanProblem = null
        val projects = projectsDir()
        if (!Files.isDirectory(projects)) return emptyList()
        val live = LiveSessionReader.read(sessionsDir())

        val transcripts = listTranscripts(projects)
        cache.retainOnly(transcripts.toSet())

        val sessions = ArrayList<Session>(transcripts.size)
        for (path in transcripts) {
            val attrs = try {
                Files.readAttributes(path, BasicFileAttributes::class.java)
            } catch (e: Exception) {
                continue // vanished between listing and stat
            }
            val info = cache.get(path, attrs) ?: continue
            val id = info.sessionId ?: path.fileName.toString().removeSuffix(".jsonl")
            val cwd = info.cwd?.let { runCatching { Paths.get(it) }.getOrNull() } ?: continue // no user record yet: nothing to resume into
            val liveEntry = live[id]
            sessions += Session(
                kind = kind,
                id = id,
                title = info.title,
                cwd = cwd,
                gitBranch = info.gitBranch,
                startedAt = info.startedAt,
                lastActivityAt = Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis()),
                transcriptPath = path,
                isLive = liveEntry != null,
                livePid = liveEntry?.pid,
                promptTitle = info.promptTitle,
                promptCount = info.promptCount,
                hasExplicitTitle = info.hasExplicitTitle,
            )
        }
        cache.persist()
        return sessions
    }

    override fun conversationText(session: Session): List<String> =
        session.transcriptPath?.let { TranscriptTextExtractor.extract(it) } ?: emptyList()

    override fun conversationMessages(session: Session): List<ConversationMessage> =
        session.transcriptPath?.let { TranscriptTextExtractor.messages(it) } ?: emptyList()

    override fun lastMessages(session: Session, count: Int): List<ConversationMessage> =
        session.transcriptPath?.let { TranscriptTailReader.lastMessages(it, count) } ?: emptyList()

    override fun contentStamp(session: Session): Any? = FileStamp.of(session.transcriptPath)

    /** `projects/<escaped-cwd>/<uuid>.jsonl` only — subagent dirs, memory/ and index files are skipped. */
    private fun listTranscripts(projects: Path): List<Path> {
        val result = ArrayList<Path>()
        try {
            Files.list(projects).use { dirs ->
                dirs.filter { Files.isDirectory(it) }.forEach { dir ->
                    try {
                        Files.list(dir).use { files ->
                            files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl") }
                                .forEach { result.add(it) }
                        }
                    } catch (e: Exception) {
                        scanProblem = "Cannot list $dir: ${e.message}"
                        LOG.debug("Cannot list $dir", e)
                    }
                }
            }
        } catch (e: Exception) {
            scanProblem = "Cannot list $projects: ${e.message}"
            LOG.debug("Cannot list $projects", e)
        }
        return result
    }
}
