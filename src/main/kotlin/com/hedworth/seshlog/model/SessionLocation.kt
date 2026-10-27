package com.hedworth.seshlog.model

import java.nio.file.Path
import java.time.Instant

/** Agent evidence only. Git state is resolved separately, never persisted in the parse cache. */
data class SessionLocation(
    val source: LocationSource,
    val record: Long = 0,
    val timestamp: Instant? = null,
    val uncertain: Boolean = false,
    val explanation: String? = null,
)

enum class LocationSource {
    INITIAL, CLAUDE_CONTEXT, CLAUDE_WORKTREE_STATE, CODEX_CONTEXT, CODEX_DATABASE,
}

/** Evidence source/offset/time can advance without changing the recorded directory or confidence. */
internal fun sameLocationEvidence(a: SessionLocation?, b: SessionLocation?): Boolean =
    if (a == null || b == null) a == b else a.uncertain == b.uncertain

/** An immutable, local Git observation. All fields are safe to read while rendering. */
data class Checkout(
    val root: Path,
    val gitDir: Path,
    val commonDir: Path,
    val kind: Kind,
    val branch: String?,
    val commit: String?,
    val available: Boolean = true,
    /** The per-checkout Git directory's filesystem identity catches replacement at the same path. */
    val directoryKey: String? = null,
    val problem: String? = null,
) {
    enum class Kind { MAIN, LINKED }
    val linked: Boolean get() = kind == Kind.LINKED
    val branchLabel: String get() = branch ?: if (commit != null) "detached HEAD (${commit.take(8)})" else "branch unknown"
    fun sameIdentity(other: Checkout): Boolean = root == other.root && gitDir == other.gitDir &&
        commonDir == other.commonDir && kind == other.kind && directoryKey == other.directoryKey
    fun unavailable(reason: String) = copy(available = false, problem = reason)
}
