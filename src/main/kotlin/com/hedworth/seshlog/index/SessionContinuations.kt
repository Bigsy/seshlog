package com.hedworth.seshlog.index

import com.hedworth.seshlog.model.Session

/** Explicit continuation links only: matching titles, branches and fork ancestry are not identity. */
class SessionContinuations(private val sessions: List<Session>) {
    private val byKey = sessions.associateBy { it.kind to it.id }

    /** Keep the last available session; broken links and cycles must never hide an entire chain. */
    fun latest(session: Session): Session {
        var current = byKey[session.kind to session.id] ?: session
        val visited = mutableSetOf<String>()
        while (true) {
            if (!visited.add(current.id)) return session
            // An independently running predecessor must remain visible and addressable.
            if (current.isLive) return current
            val next = current.continuationId?.let { byKey[current.kind to it] } ?: return current
            current = next
        }
    }

    fun current(): List<Session> = sessions.filter { latest(it).id == it.id }

    /** All earlier parts, nearest first, for read-only access from the current row. */
    fun history(session: Session): List<Session> {
        val predecessors = sessions.filter { it.kind == session.kind }.groupBy { it.continuationId }
        val visited = mutableSetOf(session.id)
        val pending = ArrayDeque<String>()
        val result = mutableListOf<Session>()
        pending.add(session.id)
        while (pending.isNotEmpty()) {
            for (previous in predecessors[pending.removeFirst()].orEmpty()) {
                if (visited.add(previous.id)) {
                    result.add(previous)
                    pending.add(previous.id)
                }
            }
        }
        return result
    }
}
