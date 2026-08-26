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
    val transcriptPath: Path,
    val isLive: Boolean,
    val livePid: Long?,
    /** Title derived from the first user prompt; null when the session has no real prompts. */
    val promptTitle: String?,
    /** Number of real user prompts (excludes tool results, meta records and slash commands). */
    val promptCount: Int,
    /** True when the title came from a `custom-title`/`ai-title` record rather than a fallback. */
    val hasExplicitTitle: Boolean,
) {
    /** Branch worth showing: not empty and not a detached `HEAD`. */
    val displayBranch: String?
        get() = gitBranch?.takeIf { it.isNotBlank() && it != "HEAD" }
}
