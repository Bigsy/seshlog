package com.hedworth.seshlog.codex

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.model.SessionProvider
import com.hedworth.seshlog.terminal.ShellQuote
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only provider for Codex CLI rollout sessions under `$CODEX_HOME/sessions`. */
class CodexSessionProvider(
    private val dataDir: () -> Path,
    private val executable: () -> String,
    private val cacheFile: Path? = null,
) : SessionProvider {
    private val LOG = logger<CodexSessionProvider>()

    override val kind: AgentKind = AgentKind.CODEX

    private val cache = ConcurrentHashMap<Path, CodexTranscriptInfoStore.Entry>()
    private val cacheDirty = AtomicBoolean(false)

    init {
        if (cacheFile != null) cache.putAll(CodexTranscriptInfoStore.load(cacheFile))
    }

    override fun dataRoot(): Path = dataDir()
    private fun sessionsDir() = dataDir().resolve("sessions")
    private fun namesFile() = dataDir().resolve("session_index.jsonl")
    private fun locksDir() = dataDir().resolve("thread-writer-locks")

    override fun isAvailable(): Boolean = Files.isDirectory(sessionsDir())

    override fun watchRoots(): List<Path> = listOf(sessionsDir(), namesFile(), locksDir())

    override fun resumeCommand(session: Session): String =
        "${ShellQuote.quote(executable())} resume ${ShellQuote.quote(session.id)}"

    override fun forkCommand(session: Session): String =
        "${ShellQuote.quote(executable())} fork ${ShellQuote.quote(session.id)}"

    override fun scan(previous: Map<String, Session>): List<Session> {
        val root = sessionsDir()
        if (!Files.isDirectory(root)) return emptyList()
        val names = CodexSessionIndexReader.read(namesFile())
        val paths = listTranscripts(root)
        if (cache.keys.retainAll(paths.toSet())) cacheDirty.set(true)
        val result = ArrayList<Session>(paths.size)
        for (path in paths) {
            val attrs = try {
                Files.readAttributes(path, BasicFileAttributes::class.java)
            } catch (_: Exception) {
                continue
            }
            val modified = Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis())
            val idFromName = idFromFileName(path)
            val info = cachedInfo(path, attrs) ?: continue
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
        persistCache()
        return result
    }

    private fun cachedInfo(path: Path, attrs: BasicFileAttributes): CodexTranscriptInfo? {
        val size = attrs.size()
        val mtime = attrs.lastModifiedTime().toMillis()
        cache[path]?.let { if (it.size == size && it.mtimeMillis == mtime) return it.info }
        val info = try {
            CodexTranscriptParser.parse(path)
        } catch (e: Exception) {
            LOG.debug("Failed to parse Codex rollout $path", e)
            return null
        }
        cache[path] = CodexTranscriptInfoStore.Entry(size, mtime, info)
        cacheDirty.set(true)
        return info
    }

    private fun persistCache() {
        if (cacheFile == null || !cacheDirty.compareAndSet(true, false)) return
        CodexTranscriptInfoStore.save(cacheFile, HashMap(cache))
    }

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
