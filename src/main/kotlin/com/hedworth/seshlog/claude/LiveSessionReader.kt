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

    /** Missing markers are normal; failed enumeration or inspection is incomplete liveness evidence. */
    internal fun readWithDiagnostics(
        sessionsDir: Path,
        isAlive: ((Long) -> Boolean)? = null,
        listFiles: (Path) -> List<Path> = { directory ->
            Files.list(directory).use { stream -> stream.filter { it.fileName.toString().endsWith(".json") }.toList() }
        },
        readText: (Path) -> String = { Files.readString(it) },
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
        var problem: String? = null
        val onFailure: (Path) -> Unit = { problem = "Cannot inspect live-session marker: $it" }
        for (file in files) {
            val live = readMarker(file, readText, onFailure) ?: continue
            if (!(isAlive?.invoke(live.pid) ?: isCurrent(file, live, readText, onFailure))) continue
            result[live.sessionId] = live
        }
        return LiveSessionScan(result, problem)
    }

    fun parse(file: Path): LiveSession? = readMarker(file, { Files.readString(it) }, {})

    private fun readMarker(file: Path, readText: (Path) -> String, onFailure: (Path) -> Unit): LiveSession? {
        val text = try {
            readText(file)
        } catch (e: Exception) {
            markerFailure(file, e, onFailure)
            return null
        }
        return parseText(file, text)
    }

    // Malformed records remain tolerant; distinguish them from failures to read the file itself.
    private fun parseText(file: Path, text: String): LiveSession? = try {
        val obj = JsonParser.parseString(text).asJsonObject
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
        LOG.debug("Skipping malformed live-session file $file", e)
        null
    }

    /** A reused PID starts after the old marker was written and must not be shown or killed. */
    fun isCurrent(file: Path, expected: LiveSession): Boolean =
        isCurrent(file, expected, { Files.readString(it) }, {})

    private fun isCurrent(file: Path, expected: LiveSession, readText: (Path) -> String,
                          onFailure: (Path) -> Unit): Boolean =
        try {
            if (readMarker(file, readText, onFailure) != expected) false else {
                val process = ProcessHandle.of(expected.pid).orElse(null)
                val started = process?.info()?.startInstant()?.orElse(null)
                process?.isAlive == true && (started == null ||
                    !started.isAfter(Files.getLastModifiedTime(file).toInstant()))
            }
        } catch (e: Exception) {
            markerFailure(file, e, onFailure)
            false
        }

    private fun markerFailure(file: Path, error: Exception, onFailure: (Path) -> Unit) {
        // A marker removed during scanning is an ordinary exit. Unknown accessibility and broken
        // symlinks must not be mistaken for removal (notExists returns false for either).
        if (error is NoSuchFileException && Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return
        LOG.debug("Cannot inspect live-session marker $file", error)
        onFailure(file)
    }
}
