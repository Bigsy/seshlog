package com.hedworth.seshlog.claude

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Instant

/**
 * One `~/.claude/sessions/<pid>.json`. [status] is Claude Code's own view of the session, observed
 * as `busy` (generating or running tools) and `idle` (waiting for a prompt); [statusUpdatedAt] is
 * when it last changed.
 */
data class LiveSession(
    val pid: Long,
    val sessionId: String,
    /** Registration cwd; statusUpdatedAt does not date this field. Never overrides transcript context. */
    val cwd: String?,
    val status: String?,
    val statusUpdatedAt: Instant? = null,
)

internal data class LiveSessionScan(val sessions: Map<String, LiveSession>, val problem: String? = null)

/**
 * Reads `~/.claude/sessions/<pid>.json`. A session counts as live only when the file exists
 * **and** the pid is still alive — stale files are left behind by hard-killed processes.
 */
object LiveSessionReader {
    private val LOG = logger<LiveSessionReader>()

    fun read(sessionsDir: Path, isAlive: ((Long) -> Boolean)? = null): Map<String, LiveSession> =
        readWithDiagnostics(sessionsDir, isAlive).sessions

    /** An absent optional directory is normal; failed enumeration is incomplete liveness evidence. */
    internal fun readWithDiagnostics(
        sessionsDir: Path,
        isAlive: ((Long) -> Boolean)? = null,
        listFiles: (Path) -> List<Path> = { directory ->
            Files.list(directory).use { stream -> stream.filter { it.fileName.toString().endsWith(".json") }.toList() }
        },
    ): LiveSessionScan {
        val result = HashMap<String, LiveSession>()
        val files = try {
            listFiles(sessionsDir)
        } catch (e: Exception) {
            if (e is NoSuchFileException && Files.notExists(sessionsDir, LinkOption.NOFOLLOW_LINKS)) {
                return LiveSessionScan(emptyMap())
            }
            LOG.debug("Cannot list $sessionsDir", e)
            return LiveSessionScan(emptyMap(), "Cannot list live-session directory: $sessionsDir")
        }
        for (file in files) {
            val live = parse(file) ?: continue
            if (!(isAlive?.invoke(live.pid) ?: isCurrent(file, live))) continue
            result[live.sessionId] = live
        }
        return LiveSessionScan(result)
    }

    fun parse(file: Path): LiveSession? = try {
        val obj = JsonParser.parseString(Files.readString(file)).asJsonObject
        val pid = obj.get("pid")?.asLong ?: return null
        val sessionId = obj.get("sessionId")?.asString ?: return null
        LiveSession(
            pid = pid,
            sessionId = sessionId,
            cwd = obj.get("cwd")?.takeIf { it.isJsonPrimitive }?.asString,
            status = obj.get("status")?.takeIf { it.isJsonPrimitive }?.asString,
            statusUpdatedAt = (obj.get("statusUpdatedAt") ?: obj.get("updatedAt"))
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong?.let(Instant::ofEpochMilli),
        )
    } catch (e: Exception) {
        LOG.debug("Skipping unreadable live-session file $file", e)
        null
    }

    /** A reused PID starts after the old marker was written and must not be shown or killed. */
    fun isCurrent(file: Path, expected: LiveSession): Boolean =
        try {
            if (parse(file) != expected) false else {
                val process = ProcessHandle.of(expected.pid).orElse(null)
                val started = process?.info()?.startInstant()?.orElse(null)
                process?.isAlive == true && (started == null ||
                    !started.isAfter(Files.getLastModifiedTime(file).toInstant()))
            }
        } catch (_: Exception) {
            false
        }
}
