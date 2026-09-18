package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import java.nio.file.Path

/** Resume arguments identify the session; an arbitrary busy shell is not evidence of an agent. */
internal object SessionProcess {
    fun matches(kind: AgentKind, id: String, transcript: String?, arguments: List<String>): Boolean {
        // These commands name the source session, but run a newly minted session.
        if (kind == AgentKind.CLAUDE_CODE && "--fork-session" in arguments) return false
        if (kind == AgentKind.OPENCODE && "--fork" in arguments) return false
        val option = when (kind) {
            AgentKind.CLAUDE_CODE -> "--resume"
            AgentKind.CODEX -> "resume"
            AgentKind.OPENCODE, AgentKind.PI -> "--session"
        }
        val target = if (kind == AgentKind.PI) transcript ?: return false else id
        return arguments.zipWithNext().any { (key, value) -> key == option && value == target }
    }

    internal data class Evidence(val pid: Long, val command: String?, val arguments: List<String>)

    /** A PID marker takes precedence over old resume arguments after an in-agent session switch. */
    internal fun identify(process: Evidence, sessions: List<Session>, executables: Map<AgentKind, String>): Set<String> {
        val live = sessions.filter { it.isLive && it.livePid == process.pid }
        if (live.isNotEmpty()) return live.mapTo(HashSet()) { it.id }
        return sessions.filter { session ->
            val executable = executables[session.kind] ?: return@filter false
            fun basename(value: String?): String? = value?.let { runCatching { Path.of(it).fileName?.toString() }.getOrNull() }
            val expected = basename(executable)
            // Native binaries and Node entry scripts. Do not adopt an arbitrary command that
            // happens to contain a session ID or a shell's unparsed `-c` command string.
            val command = basename(process.command)
            val script = basename(process.arguments.firstOrNull())
            val agent = expected != null && (command == expected ||
                (command in setOf("node", "nodejs", "bun") && script in setOf(expected, "$expected.js")))
            agent && matches(session.kind, session.id, session.transcriptPath?.toString(), process.arguments)
        }.mapTo(HashSet()) { it.id }
    }

    /** Discover manually resumed agents once per shell, entirely off the EDT. */
    fun discover(shell: Long, sessions: List<Session>, executables: Map<AgentKind, String>): Set<String> = try {
        val root = ProcessHandle.of(shell).orElse(null)
        if (root == null) emptySet() else root.descendants().use { descendants ->
            (sequenceOf(root) + descendants.iterator().asSequence()).filter { it.isAlive }.flatMap { process ->
                val info = process.info()
                identify(Evidence(process.pid(), info.command().orElse(null),
                    info.arguments().orElse(emptyArray()).toList()), sessions, executables)
            }.toSet()
        }
    } catch (_: Exception) {
        emptySet()
    }

    /** Called on a pooled thread. Missing process arguments mean unknown, never guessed live. */
    fun isRunning(shell: Long, session: Session): Boolean = try {
        val parent = ProcessHandle.of(shell).orElse(null)
        if (parent == null) false else parent.descendants().use { descendants ->
            (sequenceOf(parent) + descendants.iterator().asSequence()).any { process ->
                process.isAlive && (session.livePid == process.pid() ||
                    matches(session.kind, session.id, session.transcriptPath?.toString(),
                        process.info().arguments().orElse(emptyArray()).toList()))
            }
        }
    } catch (_: Exception) {
        false
    }
}
