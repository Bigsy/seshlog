package com.hedworth.seshlog.terminal

import java.util.WeakHashMap

/** Weak-key lookups also mutate the map while purging stale keys, so reads need the same lock. */
internal class EndedSessions<T : Any> {
    private val sessions = WeakHashMap<T, String>()
    @Synchronized operator fun get(tab: T): String? = sessions[tab]
    @Synchronized operator fun set(tab: T, sessionId: String) { sessions[tab] = sessionId }
    @Synchronized fun remove(tab: T) { sessions.remove(tab) }
}
