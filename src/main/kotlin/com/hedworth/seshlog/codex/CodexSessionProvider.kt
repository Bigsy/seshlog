package com.hedworth.seshlog.codex

import com.hedworth.seshlog.cache.FileBackedParseCache
import com.hedworth.seshlog.cache.FileStamp
import com.hedworth.seshlog.claude.TranscriptTailReader
import com.hedworth.seshlog.claude.TranscriptTextExtractor
import com.hedworth.seshlog.copy.LastAssistantReader
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.ConversationEntry
import com.hedworth.seshlog.model.ConversationLimits
import com.hedworth.seshlog.model.ConversationMessage
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.model.SessionProvider
import com.hedworth.seshlog.terminal.ExtraArguments
import com.hedworth.seshlog.terminal.ShellQuote
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

/**
 * Read-only provider for Codex CLI rollout sessions under `$CODEX_HOME/sessions`.
 * [extraArgs] resolves the user's additional arguments, appended verbatim to resume and fork commands.
 */
class CodexSessionProvider(
    private val dataDir: () -> Path,
    private val executable: () -> String,
    cacheFile: Path? = null,
    private val extraArgs: () -> String = { "" },
) : SessionProvider {
    private val LOG = logger<CodexSessionProvider>()

    override val kind: AgentKind = AgentKind.CODEX
    // A writer lock alone does not identify a verified running process for this session.
    override val detectsLiveSessions: Boolean = false

    private val cache = FileBackedParseCache(CodexTranscriptInfoStore, cacheFile, CodexTranscriptParser::parse,
        incrementalParse = CodexTranscriptParser::parseIncremental,
        onReadFailure = { path, error -> scanProblem = "Cannot read $path: ${error.message}" },
    )

    override fun dataRoot(): Path = dataDir()
    private fun sessionsDir() = dataDir().resolve("sessions")
    private fun namesFile() = dataDir().resolve("session_index.jsonl")
    private fun stateFile() = dataDir().resolve("state_5.sqlite")

    @Volatile override var scanProblem: String? = null
        private set
    override fun storagePath(): Path = sessionsDir()

    override fun isAvailable(): Boolean = Files.isDirectory(sessionsDir())

    override fun watchRoots(): List<Path> = listOf(sessionsDir(), namesFile(), stateFile())

    override fun resumeCommand(session: Session): String =
        ExtraArguments.append("${ShellQuote.quote(executable())} resume ${ShellQuote.quote(session.id)}", extraArgs())

    override fun forkCommand(session: Session): String =
        ExtraArguments.append("${ShellQuote.quote(executable())} fork ${ShellQuote.quote(session.id)}", extraArgs())

    override fun scan(previous: Map<String, Session>): List<Session> {
        scanProblem = null
        val root = sessionsDir()
        if (!Files.isDirectory(root)) return emptyList()
        val names = CodexSessionIndexReader.read(namesFile())
        val databaseRows = CodexStateDatabase(stateFile()).read()
        val rowsByPath = databaseRows?.associateBy { resolveRolloutPath(it.rolloutPath) }
        // state_5 is a metadata index and can lag the files being written. Always discover
        // rollouts from disk, then enrich matching paths with database rows.
        val paths = (listTranscripts(root) + rowsByPath.orEmpty().keys)
            .filter { Files.isRegularFile(it) }
            .distinct()
        cache.retainOnly(paths.toSet())
        val result = ArrayList<Session>(paths.size)
        for (path in paths) {
            val attrs = try {
                Files.readAttributes(path, BasicFileAttributes::class.java)
            } catch (_: Exception) {
                continue
            }
            val modified = Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis())
            val idFromName = idFromFileName(path)
            val row = rowsByPath?.get(path)
            // The state DB has no prompt/activity columns. Keep those correct by parsing on a
            // cache miss, while reusing the persisted transcript metadata on warm scans.
            val parsed = cache.get(path, attrs) ?: continue
            val info = row?.let { metadata(it, parsed) } ?: parsed
            if (info.isSubagentRollout) continue
            val id = info.sessionId ?: idFromName ?: continue
            val cwd = info.cwd?.let { runCatching { Path.of(it) }.getOrNull() } ?: continue
            val promptTitle = info.promptTitle
            // Archived state is retained by the reader for future filtering; Codex has no
            // Seshlog setting for it, so archived threads remain visible as before.
            val explicitTitle = names[id] ?: row?.displayTitle
            val lastActivity = row?.updatedAtMillis?.let { Instant.ofEpochMilli(maxOf(it, attrs.lastModifiedTime().toMillis())) }
                ?: modified
            result += Session(
                kind = kind,
                id = id,
                title = explicitTitle ?: promptTitle ?: UNTITLED,
                cwd = cwd,
                gitBranch = info.gitBranch,
                startedAt = info.startedAt,
                lastActivityAt = lastActivity,
                transcriptPath = path,
                isLive = false,
                livePid = null,
                promptTitle = promptTitle,
                promptCount = info.promptCount,
                hasExplicitTitle = explicitTitle != null,
                forkedFromId = info.forkedFromId,
                activity = info.activity,
                activitySince = info.activityAt,
            )
        }
        return result
    }

    private fun resolveRolloutPath(path: Path): Path =
        if (path.isAbsolute) path.normalize() else stateFile().parent.resolve(path).normalize()

    private fun metadata(row: CodexStateDatabase.ThreadRow, parsed: CodexTranscriptInfo): CodexTranscriptInfo =
        parsed.copy(
            // The transcript payload id is authoritative. The database id is a fallback for
            // older or incomplete rollouts and must not recreate the pre-1.1 parent merge.
            sessionId = parsed.sessionId ?: row.id,
            cwd = row.cwd ?: parsed.cwd,
            gitBranch = row.gitBranch ?: parsed.gitBranch,
            // The DB title/name is applied by scan; retain the prompt-derived title for search and fallback.
            startedAt = parsed.startedAt,
        )

    override fun conversationText(session: Session): List<String> =
        session.transcriptPath?.let { TranscriptTextExtractor.extract(it, CodexConversationMessages::parseLine) } ?: emptyList()

    override fun conversationMessages(session: Session): List<ConversationMessage> =
        session.transcriptPath?.let { TranscriptTextExtractor.messages(it, parseLine = CodexConversationMessages::parseLine) } ?: emptyList()

    override fun conversationEntries(session: Session): List<ConversationEntry> =
        session.transcriptPath?.let { ConversationLimits.read(it, CodexConversationEntries::parse) } ?: emptyList()

    override fun latestPlan(session: Session) = CodexPlanReader.read(session)

    override fun lastAssistantMessage(session: Session) =
        LastAssistantReader.read(session.transcriptPath, CodexConversationMessages::parseClipboardLine)

    override fun lastMessages(session: Session, count: Int): List<ConversationMessage> =
        session.transcriptPath?.let { TranscriptTailReader.lastMessages(it, count, parseLine = CodexConversationMessages::parseLine) }
            ?: emptyList()

    override fun contentStamp(session: Session): Any? = FileStamp.of(session.transcriptPath)

    private fun listTranscripts(root: Path): List<Path> {
        val result = ArrayList<Path>()
        try {
            Files.walk(root, 4).use { paths ->
                paths.filter {
                    Files.isRegularFile(it) && it.fileName.toString().let { name ->
                        name.startsWith("rollout-") && name.endsWith(".jsonl")
                    }
                }.forEach(result::add)
            }
        } catch (e: Exception) {
            scanProblem = "Cannot list $root: ${e.message}"
            LOG.debug("Cannot list Codex sessions under $root", e)
        }
        return result
    }

    override fun flush() = cache.persist()

    companion object {
        private val UUID_AT_END = Regex("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\.jsonl$")
        private const val UNTITLED = "Untitled session"

        internal fun idFromFileName(path: Path): String? =
            UUID_AT_END.find(path.fileName.toString())?.groupValues?.get(1)
    }
}
