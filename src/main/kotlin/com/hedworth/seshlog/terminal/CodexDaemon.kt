package com.hedworth.seshlog.terminal

import com.google.gson.JsonParser
import com.hedworth.seshlog.diagnostics.DiagnosticRef
import com.hedworth.seshlog.diagnostics.DiagnosticTrace
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.math.abs

/** Process metadata for diagnostics and grouping wrappers. Daemon-held files never identify a TUI. */
internal object CodexDaemon {
    private val log = logger<CodexDaemon>()
    // Never a TUI. Leaving out a real TUI could pair its thread with another, so list only these.
    private val NON_TUI_COMMANDS = setOf("app-server", "exec", "mcp-server")
    /**
     * The codex processes of one launch: a Node wrapper and its native binary, or just the binary.
     * [startedAt] is the earliest of their starts; [resumed] is any `resume`, with an id or a picker;
     * [forkOf] is the source thread of `fork <id>`.
     */
    data class Instance(val pids: Set<Long>, val resumeId: String?, val startedAt: Instant? = null, val resumed: Boolean = resumeId != null,
                        val forkOf: String? = null)

    /** The daemon's pid from `daemon.pid`; null when absent, unreadable or since reused by another process. */
    fun pid(codexHome: Path, table: ProcessTableSnapshot, trace: DiagnosticTrace = DiagnosticTrace.NONE): Long? {
        val file = codexHome.resolve("app-server-daemon/daemon.pid")
        if (!Files.isRegularFile(file)) {
            trace.event("daemon_marker", "status" to "absent", "home" to DiagnosticRef.path(codexHome))
            return null
        }
        return try {
            val marker = JsonParser.parseString(Files.readString(file)).asJsonObject
            val pid = marker.get("pid").asLong
            val startSeconds = marker.getAsJsonObject("processIdentity").get("startSeconds").asLong
            val started = table.row(pid)?.startedAt
            val valid = started != null && abs(started.epochSecond - startSeconds) <= 1
            trace.event("daemon_marker", "status" to if (valid) "valid" else "identity_mismatch", "pid" to pid,
                "expectedStart" to startSeconds, "actualStart" to started?.epochSecond, "home" to DiagnosticRef.path(codexHome))
            pid.takeIf { valid }
        } catch (e: Exception) {
            trace.failure("daemon_marker_unreadable", e)
            log.debug("Unreadable Codex daemon marker $file", e)
            null
        }
    }

    /**
     * Every codex launch except the daemon, its children, other app servers (editor integrations),
     * `exec` runs and MCP servers.
     */
    fun instances(table: ProcessTableSnapshot, executable: String, daemon: Long): List<Instance> {
        val agents = table.rows().filter { row ->
            row.pid != daemon && SessionProcess.isAgent(row.evidence(), executable) &&
                row.arguments.none { it in NON_TUI_COMMANDS } && !table.isDescendantOf(row.pid, setOf(daemon))
        }.associateBy { it.pid }
        fun launch(pid: Long): Long {
            var current = pid
            repeat(8) { current = table.parentPid(current)?.takeIf { it in agents } ?: return current }
            return current
        }
        return agents.values.groupBy { launch(it.pid) }.values.map { rows ->
            val resumeId = rows.mapNotNull { subcommandTarget(it.arguments, "resume") }.distinct().singleOrNull()
            Instance(rows.mapTo(HashSet()) { it.pid }, resumeId, rows.mapNotNull { it.startedAt }.minOrNull(),
                resumeId != null || rows.any { "resume" in it.arguments },
                rows.mapNotNull { subcommandTarget(it.arguments, "fork") }.distinct().singleOrNull())
        }
    }

    private fun subcommandTarget(arguments: List<String>, subcommand: String): String? =
        arguments.zipWithNext().firstOrNull { it.first == subcommand }?.second?.takeUnless { it.startsWith("-") }
}
