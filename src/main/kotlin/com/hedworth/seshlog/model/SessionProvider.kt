package com.hedworth.seshlog.model

import java.nio.file.Path

interface SessionProvider {
    val kind: AgentKind

    /**
     * Whether this provider can ever report a session as live (`Session.isLive`). Restore after
     * restart relies on that signal, so sessions of a provider without one are never remembered
     * for restoring — a resumed session stays remembered until the provider confirms it is running,
     * which for such a provider would be forever.
     */
    val detectsLiveSessions: Boolean get() = true

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

    /**
     * Every visible message text of [session] (user prompts and assistant replies, in order), for
     * content search. Never throws for bad content; I/O errors propagate.
     */
    fun conversationText(session: Session): List<String>

    /** The last [count] visible messages of [session] in chronological order, for the preview pane. */
    fun lastMessages(session: Session, count: Int): List<ConversationMessage>

    /**
     * Cheap token that changes whenever [conversationText] would: the search index re-extracts a
     * session only when its stamp differs. `null` means "unknown, never cache".
     */
    fun contentStamp(session: Session): Any?
}
