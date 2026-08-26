package com.hedworth.seshlog.restore

import com.hedworth.seshlog.model.Session

/** What to do with the sessions remembered at shutdown. */
data class RestorePlan(
    /** Resume these (dead, or orphaned — see [orphans]). In remembered order. */
    val restore: List<Session>,
    /** Subset of [restore] whose old `claude` process still lingers without a terminal; stop it first. */
    val orphans: List<Session>,
    /** Still running in a terminal that is alive (this IDE or another one): leave alone. */
    val running: List<Session>,
)

/** Pure logic for "which sessions should come back after a restart", kept free of IntelliJ types. */
object RestoreCandidates {

    /**
     * Sessions worth remembering for this project right now: live ones that Seshlog launched from
     * this project, plus live ones whose `claude` process runs under one of this project's own
     * terminal shells ([shellPids]). Sessions running in another IDE window or a standalone
     * terminal are deliberately *not* ours to restore.
     */
    fun snapshot(sessions: List<Session>, launchedIds: Set<String>, shellPids: Set<Long>, tree: ProcessTree): List<String> =
        sessions.asSequence()
            .filter { it.isLive }
            .filter { it.id in launchedIds || (it.livePid != null && tree.isDescendantOf(it.livePid, shellPids)) }
            .map { it.id }
            .toList()

    /**
     * Resolve the ids remembered at shutdown against the current index. A session whose process
     * is still alive is skipped when that process sits under a live shell (it is really running
     * somewhere), but restored when it is an orphan of a tab that no longer exists — closing a
     * terminal tab does not reliably kill `claude`.
     */
    fun plan(pendingIds: List<String>, sessions: List<Session>, tree: ProcessTree): RestorePlan {
        val byId = sessions.associateBy { it.id }
        val restore = ArrayList<Session>()
        val orphans = ArrayList<Session>()
        val running = ArrayList<Session>()
        for (id in pendingIds.distinct()) {
            val session = byId[id] ?: continue
            val pid = session.livePid
            when {
                !session.isLive || pid == null -> restore += session
                tree.isOrphan(pid) -> { restore += session; orphans += session }
                else -> running += session
            }
        }
        return RestorePlan(restore, orphans, running)
    }
}
