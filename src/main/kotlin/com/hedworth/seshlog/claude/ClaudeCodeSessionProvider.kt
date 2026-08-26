package com.hedworth.seshlog.claude

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.model.SessionProvider
import com.hedworth.seshlog.terminal.ShellQuote
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    private val cacheFile: Path? = null,
) : SessionProvider {
    private val LOG = logger<ClaudeCodeSessionProvider>()

    override val kind: AgentKind = AgentKind.CLAUDE_CODE

    /** transcript path → parsed info, keyed by (size, mtime) so unchanged files are never re-read. */
    private val cache = ConcurrentHashMap<Path, TranscriptInfoStore.Entry>()

    /** Set when [cache] differs from what is on disk in [cacheFile]. */
    private val cacheDirty = AtomicBoolean(false)

    init {
        if (cacheFile != null) {
            val loaded = TranscriptInfoStore.load(cacheFile)
            cache.putAll(loaded)
            LOG.debug("Loaded ${loaded.size} cached transcripts from $cacheFile")
        }
    }

    override fun dataRoot(): Path = dataDir()

    private fun projectsDir(): Path = dataDir().resolve("projects")
    private fun sessionsDir(): Path = dataDir().resolve("sessions")

    override fun isAvailable(): Boolean = Files.isDirectory(projectsDir())

    override fun watchRoots(): List<Path> = listOf(projectsDir(), sessionsDir())

    override fun resumeCommand(session: Session): String =
        "${ShellQuote.quote(executable())} --resume ${ShellQuote.quote(session.id)}"

    override fun forkCommand(session: Session): String = "${resumeCommand(session)} --fork-session"

    override fun scan(previous: Map<String, Session>): List<Session> {
        val projects = projectsDir()
        if (!Files.isDirectory(projects)) return emptyList()
        val live = LiveSessionReader.read(sessionsDir())

        val transcripts = listTranscripts(projects)
        if (cache.keys.retainAll(transcripts.toSet())) cacheDirty.set(true)

        val sessions = ArrayList<Session>(transcripts.size)
        for (path in transcripts) {
            val attrs = try {
                Files.readAttributes(path, BasicFileAttributes::class.java)
            } catch (e: Exception) {
                continue // vanished between listing and stat
            }
            val info = cachedInfo(path, attrs) ?: continue
            val id = info.sessionId ?: path.fileName.toString().removeSuffix(".jsonl")
            val cwd = info.cwd ?: continue // no user record yet: nothing to resume into
            val liveEntry = live[id]
            sessions += Session(
                kind = kind,
                id = id,
                title = info.title,
                cwd = Paths.get(cwd),
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
        persistCache()
        return sessions
    }

    private fun persistCache() {
        if (cacheFile == null || !cacheDirty.compareAndSet(true, false)) return
        TranscriptInfoStore.save(cacheFile, HashMap(cache))
    }

    private fun cachedInfo(path: Path, attrs: BasicFileAttributes): TranscriptInfo? {
        val size = attrs.size()
        val mtime = attrs.lastModifiedTime().toMillis()
        cache[path]?.let { if (it.size == size && it.mtimeMillis == mtime) return it.info }
        val info = try {
            TranscriptParser.parse(path)
        } catch (e: Exception) {
            LOG.debug("Failed to parse transcript $path", e)
            return null
        }
        cache[path] = TranscriptInfoStore.Entry(size, mtime, info)
        cacheDirty.set(true)
        return info
    }

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
                        LOG.debug("Cannot list $dir", e)
                    }
                }
            }
        } catch (e: Exception) {
            LOG.debug("Cannot list $projects", e)
        }
        return result
    }
}
