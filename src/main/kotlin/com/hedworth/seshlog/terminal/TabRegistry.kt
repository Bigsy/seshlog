package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.restore.ProcessTree

/**
 * Which terminal tab runs which session — the pure part of "owning our tabs", free of IntelliJ
 * types so it can be unit-tested. [T] is the tab handle (a `Content` in production).
 *
 * Entries come from tabs Seshlog opened or reused ([register]) and from tabs adopted because their
 * shell is an ancestor of a live `claude` process ([sync]). They go away when the tab is closed
 * ([forget]) or when the session turns out to be running under some other shell.
 */
class TabRegistry<T : Any> {
    private val bySession = LinkedHashMap<String, T>()

    val sessionIds: Set<String> get() = synchronized(bySession) { bySession.keys.toSet() }

    fun tabFor(sessionId: String): T? = synchronized(bySession) { bySession[sessionId] }

    fun owns(sessionId: String): Boolean = tabFor(sessionId) != null

    /** The session running in [tab], or null when it is not one of ours. */
    fun sessionFor(tab: T): String? = synchronized(bySession) { bySession.entries.firstOrNull { it.value == tab }?.key }

    fun register(sessionId: String, tab: T) {
        synchronized(bySession) {
            bySession.entries.removeAll { it.value == tab && it.key != sessionId }
            bySession[sessionId] = tab
        }
    }

    /** Apply background evidence only if ownership still matches the snapshot it inspected. */
    fun adoptDiscovered(sessionId: String, tab: T, previousSessionId: String?): Boolean = synchronized(bySession) {
        if (sessionFor(tab) != previousSessionId) return@synchronized false
        if (bySession[sessionId]?.let { it != tab } == true) return@synchronized false
        register(sessionId, tab)
        true
    }

    /** [sessionId]'s agent exited in [tab]: end that association only if it is still current. */
    fun release(sessionId: String, tab: T): Boolean = synchronized(bySession) {
        if (bySession[sessionId] != tab) return@synchronized false
        bySession.remove(sessionId)
        true
    }

    /** The tab was closed: drop every session that pointed at it. */
    fun forget(tab: T) {
        synchronized(bySession) { bySession.values.removeAll { it == tab } }
    }

    /** Tabs whose title should change, in `(tab, new title)` form. */
    data class Retitle<T>(val tab: T, val title: String)

    /**
     * Reconcile with a fresh scan. [openTabs] maps each open tab to its shell pid (null when
     * unknown), [titleOf] reads a tab's current title. Returns the retitles the caller should apply.
     *
     * - A live session whose `claude` descends from an open tab's shell is registered to that tab
     *   (adoption — tabs the user or the terminal plugin opened, not us).
     * - A registered live session whose process does *not* run under its tab's shell has moved
     *   elsewhere (or was resumed in another window): the entry is dropped.
     * - Registered, live sessions whose title differs from the tab's title get a retitle.
     */
    fun sync(sessions: List<Session>, openTabs: Map<T, Long?>, tree: ProcessTree, titleOf: (T) -> String?): List<Retitle<T>> {
        val tabByShell = HashMap<Long, T>()
        for ((tab, pid) in openTabs) if (pid != null) tabByShell[pid] = tab
        val retitles = ArrayList<Retitle<T>>()
        synchronized(bySession) {
            for (session in sessions) {
                val pid = session.livePid
                if (!session.isLive || pid == null) continue
                val ancestry = tree.ancestry(pid)
                // Include the process itself: `exec claude` replaces the terminal's shell.
                val shell = ancestry.pids.firstOrNull { it in tabByShell }
                val owner = bySession[session.id]
                when {
                    shell != null -> register(session.id, tabByShell.getValue(shell))
                    // A failed lookup is not proof of a move: processes can exit/restart or
                    // become temporarily unreadable between the index scan and this check.
                    owner != null && openTabs[owner] != null && ancestry.complete &&
                        tree.isAlive(pid) -> bySession.remove(session.id) // verified elsewhere
                }
                val tab = bySession[session.id] ?: continue
                if (titleOf(tab) != session.title) retitles += Retitle(tab, session.title)
            }
        }
        return retitles
    }
}
