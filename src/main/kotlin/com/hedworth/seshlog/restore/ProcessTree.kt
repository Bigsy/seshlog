package com.hedworth.seshlog.restore

/**
 * Minimal view of the OS process tree, injectable so the restore logic is unit-testable.
 * The default implementation uses [ProcessHandle] (fine on macOS/Linux; no `ps` needed).
 */
interface ProcessTree {
    /** A process identity captured from the OS, rather than a reusable numeric pid. */
    interface Handle {
        val pid: Long
        fun isAlive(): Boolean
        fun destroy(): Boolean
    }

    /** Stop only the exact process identity that was captured earlier. */
    fun terminate(handle: Handle): Boolean = if (handle.isAlive()) handle.destroy() else false

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

    data class Ancestry(val pids: List<Long>, val complete: Boolean)

    /** Keep partial evidence, but distinguish it from a chain verified all the way to init. */
    fun ancestry(pid: Long): Ancestry {
        var p = pid
        val seen = LinkedHashSet<Long>()
        repeat(64) {
            if (p <= 1) return Ancestry(seen.toList(), true)
            if (!seen.add(p)) return Ancestry(seen.toList(), false)
            p = parentPid(p) ?: return Ancestry(seen.toList(), false)
        }
        return Ancestry(seen.toList(), false)
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

        /** Capture the current process identity. A later lookup by pid is deliberately avoided. */
        fun handle(pid: Long): Handle? = try {
            ProcessHandle.of(pid).orElse(null)?.let { process ->
                object : Handle {
                    override val pid = process.pid()
                    override fun isAlive() = process.isAlive
                    override fun destroy() = process.destroy()
                }
            }
        } catch (_: Exception) { null }

    }
}
