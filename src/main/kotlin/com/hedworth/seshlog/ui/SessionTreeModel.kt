package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.SearchHit
import com.hedworth.seshlog.model.Session
import java.nio.file.Path
import javax.swing.tree.DefaultMutableTreeNode

/** A project group: all sessions with the same cwd, newest first. */
data class ProjectGroup(val cwd: Path, val sessions: List<Session>) {
    val lastActivityAt get() = sessions.maxOf { it.lastActivityAt }
    val displayName: String get() = cwd.fileName?.toString() ?: cwd.toString()
}

object SessionTreeModel {
    /** Groups by cwd; groups sorted by most recent activity, sessions within a group newest first. */
    fun group(sessions: List<Session>, pinned: (Session) -> Boolean = { false }): List<ProjectGroup> =
        sessions.groupBy { it.cwd }
            .map { (cwd, list) -> ProjectGroup(cwd, list.sortedWith(compareByDescending<Session> { pinned(it) }.thenByDescending { it.lastActivityAt })) }
            .sortedWith(compareByDescending<ProjectGroup> { it.sessions.any(pinned) }.thenByDescending { it.lastActivityAt })

    /**
     * Search results: groups ordered by their best hit, sessions within a group in hit order
     * ([hits] is already ranked best first).
     */
    fun groupRanked(hits: List<SearchHit>, pinned: (Session) -> Boolean = { false }): List<ProjectGroup> {
        val order = hits.withIndex().associate { (i, h) -> h.session.id to i }
        return hits.groupBy { it.session.cwd }
            .map { (cwd, list) -> ProjectGroup(cwd, list.map { it.session }.sortedByDescending(pinned)) }
            .sortedWith(compareByDescending<ProjectGroup> { it.sessions.any(pinned) }.thenBy { g -> g.sessions.minOf { order.getValue(it.id) } })
    }

    fun buildRoot(groups: List<ProjectGroup>): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode()
        for (group in groups) {
            val groupNode = DefaultMutableTreeNode(group)
            group.sessions.forEach { groupNode.add(DefaultMutableTreeNode(it)) }
            root.add(groupNode)
        }
        return root
    }
}
