package com.hedworth.seshlog.restore

/**
 * Minimal view of the OS process tree, injectable so the restore logic is unit-testable.
 * The default implementation uses [ProcessHandle] (fine on macOS/Linux; no `ps` needed).
 */
interface ProcessTree {
    fun isAlive(pid: Long): Boolean
    /** Parent pid, or null when the process is gone. */
    fun parentPid(pid: Long): Long?

    /** True when [pid] has one of [ancestors] somewhere up its parent chain. */
    fun isDescendantOf(pid: Long, ancestors: Set<Long>): Boolean = firstAncestorIn(pid, ancestors) != null

    /** The nearest ancestor of [pid] that is in [candidates], or null. */
    fun firstAncestorIn(pid: Long, candidates: Set<Long>): Long? {
        if (candidates.isEmpty()) return null
        var p: Long? = parentPid(pid)
        var hops = 0
        while (p != null && p > 1 && hops++ < 64) {
            if (p in candidates) return p
            p = parentPid(p)
        }
        return null
    }

    /**
     * A live process whose parent shell is gone (reparented to launchd/init, or the parent is
     * dead) — what a `claude` left behind by a closed terminal tab looks like.
     */
    fun isOrphan(pid: Long): Boolean {
        if (!isAlive(pid)) return false
        val parent = parentPid(pid) ?: return true
        return parent <= 1 || !isAlive(parent)
    }

    object System : ProcessTree {
        override fun isAlive(pid: Long): Boolean =
            try { ProcessHandle.of(pid).map { it.isAlive }.orElse(false) } catch (_: Exception) { false }

        override fun parentPid(pid: Long): Long? =
            try { ProcessHandle.of(pid).flatMap { it.parent() }.map { it.pid() }.orElse(null) } catch (_: Exception) { null }

        fun terminate(pid: Long): Boolean =
            try { ProcessHandle.of(pid).map { it.destroy() }.orElse(false) } catch (_: Exception) { false }
    }
}
