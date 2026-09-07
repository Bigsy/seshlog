package com.hedworth.seshlog.pi

import com.hedworth.seshlog.cache.FileBackedParseCache
import com.hedworth.seshlog.cache.FileStamp
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.ConversationMessage
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.model.SessionProvider
import com.hedworth.seshlog.terminal.ShellQuote
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

class PiSessionProvider(
    private val sessionsDir: () -> Path,
    private val executable: () -> String,
    cacheFile: Path? = null,
    parse: (Path) -> PiTranscriptInfo = PiTranscriptParser::parse,
) : SessionProvider {
    override val kind = AgentKind.PI
    override val detectsLiveSessions = false
    @Volatile override var scanProblem: String? = null
        private set
    private val cache = FileBackedParseCache(PiTranscriptInfoStore, cacheFile, parse,
        onReadFailure = { path, error -> scanProblem = "Cannot read $path: ${error.message}" })

    override fun dataRoot(): Path = sessionsDir().toAbsolutePath().normalize()
    override fun isAvailable() = Files.isDirectory(dataRoot())
    override fun watchRoots() = listOf(dataRoot())
    override fun resumeCommand(session: Session) =
        "${ShellQuote.quote(executable())} --session ${ShellQuote.quote(transcript(session).toString())}"
    override fun forkCommand(session: Session): String {
        val path = transcript(session)
        // Pi writes forks directly into --session-dir. Keep them beside the source, even with custom roots.
        return "${ShellQuote.quote(executable())} --fork ${ShellQuote.quote(path.toString())}" +
            " --session-dir ${ShellQuote.quote(path.parent.toString())}"
    }
    private fun transcript(session: Session) = requireNotNull(session.transcriptPath).toAbsolutePath().normalize()

    override fun scan(previous: Map<String, Session>): List<Session> {
        scanProblem = null
        val root = dataRoot()
        val paths = arrayListOf<Path>()
        if (Files.isDirectory(root)) {
            try {
                Files.walk(root, 2).use { stream ->
                    stream.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && it.fileName.toString().endsWith(".jsonl") }
                        .forEach(paths::add)
                }
            } catch (e: Exception) { scanProblem = "Cannot list $root: ${e.message}" }
        }
        cache.retainOnly(paths.toSet())
        val sessions = linkedMapOf<String, Session>()
        // Copied IDs have one stable representative, independent of filesystem enumeration order.
        for (path in paths.sorted()) {
            val attrs = try { Files.readAttributes(path, BasicFileAttributes::class.java) }
                catch (_: Exception) { continue }
            val info = cache.get(path, attrs) ?: continue
            val id = info.sessionId?.let { "pi:$it" } ?: continue
            val cwd = info.cwd?.let { runCatching { Path.of(it).takeIf(Path::isAbsolute) }.getOrNull() } ?: continue
            sessions.putIfAbsent(id, Session(kind, id, info.explicitTitle ?: info.promptTitle ?: "Untitled session",
                cwd, null, info.startedAt, info.lastActivityAt ?: attrs.lastModifiedTime().toInstant(), path,
                false, null, info.promptTitle, info.promptCount, info.explicitTitle != null))
        }
        cache.persist()
        return sessions.values.toList()
    }

    override fun conversationMessages(session: Session): List<ConversationMessage> =
        session.transcriptPath?.let { PiTranscriptParser.read(it).messages } ?: emptyList()
    override fun conversationText(session: Session) = conversationMessages(session).map { it.text }
    override fun lastMessages(session: Session, count: Int) = conversationMessages(session).takeLast(count.coerceAtLeast(0))
    override fun contentStamp(session: Session): Any? = FileStamp.of(session.transcriptPath)
}
