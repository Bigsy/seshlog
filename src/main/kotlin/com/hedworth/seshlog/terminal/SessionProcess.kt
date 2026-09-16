package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session

/** Resume arguments identify the session; an arbitrary busy shell is not evidence of an agent. */
internal object SessionProcess {
    fun matches(kind: AgentKind, id: String, transcript: String?, arguments: List<String>): Boolean {
        val option = when (kind) {
            AgentKind.CLAUDE_CODE -> "--resume"
            AgentKind.CODEX -> "resume"
            AgentKind.OPENCODE, AgentKind.PI -> "--session"
        }
        val target = if (kind == AgentKind.PI) transcript ?: return false else id
        return arguments.zipWithNext().any { (key, value) -> key == option && value == target }
    }

    /** Called on a pooled thread. Missing process arguments mean unknown, never guessed live. */
    fun isRunning(shell: Long, session: Session): Boolean = try {
        val parent = ProcessHandle.of(shell).orElse(null)
        if (parent == null) false else parent.descendants().use { descendants ->
            descendants.anyMatch { process ->
                process.isAlive && (session.livePid == process.pid() ||
                    matches(session.kind, session.id, session.transcriptPath?.toString(),
                        process.info().arguments().orElse(emptyArray()).toList()))
            }
        }
    } catch (_: Exception) {
        false
    }
}
