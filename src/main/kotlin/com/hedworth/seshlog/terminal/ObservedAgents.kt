package com.hedworth.seshlog.terminal

/**
 * The process last seen running each tab's session. Only that exact process exiting ends the
 * association: a tab registered before its agent starts (Resume), or whose processes cannot be read
 * for a moment, keeps it. [P] is a process handle in production. Confined to one thread (the EDT).
 */
internal class ObservedAgents<P : Any> {
    private val seen = HashMap<String, P>()

    fun observe(sessionId: String, process: P) { seen[sessionId] = process }

    /** Copy for a background liveness check. */
    fun snapshot(): Map<String, P> = HashMap(seen)

    /** Of [exited] (checked against [snapshot]), the sessions not seen running another process since. */
    fun ended(snapshot: Map<String, P>, exited: Set<String>): Set<String> =
        exited.filterTo(HashSet()) { id -> seen[id] != null && seen[id] === snapshot[id] }.also { seen.keys.removeAll(it) }

    fun retainOnly(sessionIds: Set<String>) { seen.keys.retainAll(sessionIds) }
}
