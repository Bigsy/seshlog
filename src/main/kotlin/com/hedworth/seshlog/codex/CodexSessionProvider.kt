package com.hedworth.seshlog.codex

import com.hedworth.seshlog.cache.FileBackedParseCache
import com.hedworth.seshlog.cache.FileStamp
import com.hedworth.seshlog.claude.TranscriptTailReader
import com.hedworth.seshlog.claude.TranscriptTextExtractor
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.ConversationMessage
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.model.SessionProvider
import com.hedworth.seshlog.terminal.ShellQuote
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

/** Read-only provider for Codex CLI rollout sessions under `$CODEX_HOME/sessions`. */
class CodexSessionProvider(
    private val dataDir: () -> Path,
    private val executable: () -> String,
    cacheFile: Path? = null,
) : SessionProvider {
    private val LOG = logger<CodexSessionProvider>()

    override val kind: AgentKind = AgentKind.CODEX

    private val cache = FileBackedParseCache(CodexTranscriptInfoStore, cacheFile, CodexTranscriptParser::parse,
        onReadFailure = { path, error -> scanProblem = "Cannot read $path: ${error.message}" },
    )

    override fun dataRoot(): Path = dataDir()
    private fun sessionsDir() = dataDir().resolve("sessions")
    private fun namesFile() = dataDir().resolve("session_index.jsonl")
    private fun locksDir() = dataDir().resolve("thread-writer-locks")

    @Volatile override var scanProblem: String? = null
        private set
    override fun storagePath(): Path = sessionsDir()

    override fun isAvailable(): Boolean = Files.isDirectory(sessionsDir())

    override fun watchRoots(): List<Path> = listOf(sessionsDir(), namesFile(), locksDir())

    override fun resumeCommand(session: Session): String =
        "${ShellQuote.quote(executable())} resume ${ShellQuote.quote(session.id)}"

    override fun forkCommand(session: Session): String =
        "${ShellQuote.quote(executable())} fork ${ShellQuote.quote(session.id)}"

    override fun scan(previous: Map<String, Session>): List<Session> {
        scanProblem = null
        val root = sessionsDir()
        if (!Files.isDirectory(root)) return emptyList()
        val names = CodexSessionIndexReader.read(namesFile())
        val paths = listTranscripts(root)
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
            val info = cache.get(path, attrs) ?: continue
            if (info.isGuardianReview) continue
            val id = info.sessionId ?: idFromName ?: continue
            val cwd = info.cwd?.let { runCatching { Path.of(it) }.getOrNull() } ?: continue
            val promptTitle = info.promptTitle
            val explicitTitle = names[id]
            result += Session(
                kind = kind,
                id = id,
                title = explicitTitle ?: promptTitle ?: UNTITLED,
                cwd = cwd,
                gitBranch = info.gitBranch,
                startedAt = info.startedAt,
                lastActivityAt = modified,
                transcriptPath = path,
                isLive = Files.isRegularFile(locksDir().resolve("$id.lock")),
                livePid = null,
                promptTitle = promptTitle,
                promptCount = info.promptCount,
                hasExplicitTitle = explicitTitle != null,
            )
        }
        cache.persist()
        return result
    }

    override fun conversationText(session: Session): List<String> =
        session.transcriptPath?.let { TranscriptTextExtractor.extract(it, CodexConversationMessages::parseLine) } ?: emptyList()

    override fun conversationMessages(session: Session): List<ConversationMessage> =
        session.transcriptPath?.let { TranscriptTextExtractor.messages(it, parseLine = CodexConversationMessages::parseLine) } ?: emptyList()

    override fun conversationEntries(session: Session): List<com.hedworth.seshlog.model.ConversationEntry> =
        session.transcriptPath?.let { com.hedworth.seshlog.model.ConversationLimits.read(it, CodexConversationEntries::parse) } ?: emptyList()

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

    companion object {
        private val UUID_AT_END = Regex("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\.jsonl$")
        private const val UNTITLED = "Untitled session"

        internal fun idFromFileName(path: Path): String? =
            UUID_AT_END.find(path.fileName.toString())?.groupValues?.get(1)
    }
}
