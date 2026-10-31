package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.restore.ProcessTree
import java.util.WeakHashMap

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
    // Closed Content handles must not be retained solely by stale polling generations.
    private val generationByTab = WeakHashMap<T, Long>()
    private var nextGeneration = 0L
    private val reserved = java.util.Collections.newSetFromMap(WeakHashMap<T, Boolean>())
    val reservedTabs: Set<T> get() = synchronized(bySession) { reserved.toSet() }
    fun isReserved(tab: T): Boolean = synchronized(bySession) { tab in reserved }

    val sessionIds: Set<String> get() = synchronized(bySession) { bySession.keys.toSet() }

    fun tabFor(sessionId: String): T? = synchronized(bySession) { bySession[sessionId] }

    fun owns(sessionId: String): Boolean = tabFor(sessionId) != null

    /** The session running in [tab], or null when it is not one of ours. */
    fun sessionFor(tab: T): String? = synchronized(bySession) { bySession.entries.firstOrNull { it.value == tab }?.key }

    /** Monotonic tab identity used to reject delayed process results after a quick reuse. */
    fun generation(tab: T): Long = synchronized(bySession) { generationByTab[tab] ?: 0L }

    fun generations(tabs: Collection<T>): Map<T, Long> = synchronized(bySession) {
        tabs.associateWith { generationByTab[it] ?: 0L }
    }

    fun snapshot(): Map<String, T> = synchronized(bySession) { LinkedHashMap(bySession) }

    data class Association<T>(val sessionId: String, val tab: T, val generation: Long)

    fun association(sessionId: String): Association<T>? = synchronized(bySession) {
        bySession[sessionId]?.let { Association(sessionId, it, generation(it)) }
    }

    fun matches(expected: Association<T>): Boolean = synchronized(bySession) {
        bySession[expected.sessionId] == expected.tab && generation(expected.tab) == expected.generation
    }

    private fun bump(tab: T) {
        generationByTab[tab] = ++nextGeneration
    }

    fun register(sessionId: String, tab: T) {
        synchronized(bySession) {
            bump(tab)
            reserved.remove(tab)
            bySession.entries.removeAll { it.value == tab && it.key != sessionId }
            bySession[sessionId] = tab
        }
    }

    /** Apply background evidence only if ownership still matches the snapshot it inspected. */
    fun adoptDiscovered(sessionId: String, tab: T, previousSessionId: String?, expectedGeneration: Long? = null): Boolean = synchronized(bySession) {
        if (expectedGeneration != null && generation(tab) != expectedGeneration) return@synchronized false
        if (sessionFor(tab) != previousSessionId) return@synchronized false
        if (bySession[sessionId]?.let { it != tab } == true) return@synchronized false
        register(sessionId, tab)
        true
    }

    data class Transition<T>(val tab: T, val previous: String?, val generation: Long,
                             val next: String?, val reserve: Boolean = next == null)

    /** Validate the entire set before releasing anything: swaps and cycles have no intermediate owner. */
    fun transition(changes: List<Transition<T>>): Boolean = synchronized(bySession) {
        if (changes.map { it.tab }.distinct().size != changes.size) return@synchronized false
        if (changes.any { sessionFor(it.tab) != it.previous || generation(it.tab) != it.generation }) return@synchronized false
        val incoming = changes.mapNotNull { it.next }
        if (incoming.distinct().size != incoming.size) return@synchronized false
        val tabs = changes.mapTo(HashSet()) { it.tab }
        if (incoming.any { bySession[it]?.let { owner -> owner !in tabs } == true }) return@synchronized false
        bySession.entries.removeAll { it.value in tabs }
        for (change in changes) {
            if (change.next != null) bySession[change.next] = change.tab
            if (change.reserve && change.next == null) reserved.add(change.tab) else reserved.remove(change.tab)
            if (change.previous != change.next) bump(change.tab)
        }
        true
    }

    /** [sessionId]'s agent exited in [tab]: end that association only if it is still current. */
    fun release(sessionId: String, tab: T, expectedGeneration: Long? = null): Boolean = synchronized(bySession) {
        if (expectedGeneration != null && generation(tab) != expectedGeneration) return@synchronized false
        if (bySession[sessionId] != tab) return@synchronized false
        bySession.remove(sessionId)
        bump(tab)
        true
    }

    /** The tab was closed: drop every session that pointed at it. */
    fun forget(tab: T) {
        synchronized(bySession) {
            reserved.remove(tab)
            bySession.values.removeAll { it == tab }
            bump(tab)
        }
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
    fun sync(sessions: List<Session>, openTabs: Map<T, Long?>, tree: ProcessTree, sessionTitle: (Session) -> String = { it.title }, titleOf: (T) -> String?): List<Retitle<T>> {
        val tabByShell = HashMap<Long, T>()
        for ((tab, pid) in openTabs) if (pid != null) tabByShell[pid] = tab
        val retitles = ArrayList<Retitle<T>>()
        synchronized(bySession) {
            for (session in sessions) {
                val pid = session.livePid
                if (session.isLive && pid != null) {
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
                }
                val tab = bySession[session.id] ?: continue
                val title = sessionTitle(session)
                if (titleOf(tab) != title) retitles += Retitle(tab, title)
            }
        }
        return retitles
    }
}
