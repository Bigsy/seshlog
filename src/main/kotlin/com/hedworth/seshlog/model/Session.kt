package com.hedworth.seshlog.model

import java.nio.file.Path
import java.time.Instant

data class Session(
    val kind: AgentKind,
    val id: String,
    val title: String,
    val cwd: Path,
    val gitBranch: String?,
    val startedAt: Instant?,
    val lastActivityAt: Instant,
    /** The session's own file on disk, when the agent keeps one per session; null for database-backed agents. */
    val transcriptPath: Path?,
    /** True only when the provider has verified a running process identified by [livePid]. */
    val isLive: Boolean,
    val livePid: Long?,
    /** Title derived from the first user prompt; null when the session has no real prompts. */
    val promptTitle: String?,
    /** Number of real user prompts (excludes tool results, meta records and slash commands). */
    val promptCount: Int,
    /** True when the title came from the agent (a `custom-title`/`ai-title` record, a session name, …) rather than a fallback. */
    val hasExplicitTitle: Boolean,
    /** Original session from which this session was forked, when recorded by the agent. */
    val forkedFromId: String? = null,
    /** What the agent is doing according to its storage; see [Activity]. Shown only while the session runs. */
    val activity: Activity = Activity.UNKNOWN,
    /** When [activity] last changed, when the agent records that. */
    val activitySince: Instant? = null,
) {
    /** Branch worth showing: not empty and not a detached `HEAD`. */
    val displayBranch: String?
        get() = gitBranch?.takeIf { it.isNotBlank() && it != "HEAD" }
}
