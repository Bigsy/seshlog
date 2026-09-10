package com.hedworth.seshlog.claude

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path

data class LiveSession(val pid: Long, val sessionId: String, val cwd: String?, val status: String?)

/**
 * Reads `~/.claude/sessions/<pid>.json`. A session counts as live only when the file exists
 * **and** the pid is still alive — stale files are left behind by hard-killed processes.
 */
object LiveSessionReader {
    private val LOG = logger<LiveSessionReader>()

    fun read(sessionsDir: Path, isAlive: ((Long) -> Boolean)? = null): Map<String, LiveSession> {
        if (!Files.isDirectory(sessionsDir)) return emptyMap()
        val result = HashMap<String, LiveSession>()
        val files = try {
            Files.list(sessionsDir).use { stream -> stream.filter { it.fileName.toString().endsWith(".json") }.toList() }
        } catch (e: Exception) {
            LOG.debug("Cannot list $sessionsDir", e)
            return emptyMap()
        }
        for (file in files) {
            val live = parse(file) ?: continue
            if (!(isAlive?.invoke(live.pid) ?: isCurrent(file, live))) continue
            result[live.sessionId] = live
        }
        return result
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
