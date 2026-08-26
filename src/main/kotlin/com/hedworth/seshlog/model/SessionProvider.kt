package com.hedworth.seshlog.model

import java.nio.file.Path

interface SessionProvider {
    val kind: AgentKind

    /** True when the agent's data directory exists. */
    fun isAvailable(): Boolean

    /** Where the agent keeps its data (used for the empty-state message). */
    fun dataRoot(): Path

    /**
     * Scan the agent's data and return every session found. [previous] is the last result keyed by
     * session id; implementations may use it to avoid re-reading unchanged transcripts.
     * Must never throw for malformed data; runs on a background thread.
     */
    fun scan(previous: Map<String, Session>): List<Session>

    /** Shell command that resumes [session] when run from `session.cwd`. */
    fun resumeCommand(session: Session): String

    /** Shell command that forks [session] into a new session (same history, new id) when run from `session.cwd`. */
    fun forkCommand(session: Session): String

    /** Directories to watch for changes that should trigger a rescan. */
    fun watchRoots(): List<Path>
}
