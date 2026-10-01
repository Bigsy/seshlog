package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.DatePeriod
import com.hedworth.seshlog.index.ResolvedPaths
import com.hedworth.seshlog.index.SessionDateFilter
import com.hedworth.seshlog.index.SessionFilter
import com.hedworth.seshlog.index.SessionContinuations
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.AgentFilterMode
import com.hedworth.seshlog.settings.AgentSessionFilter
import java.nio.file.Path

/** Pure input and output for the session list; the Swing tree only renders this result. */
data class SessionListFilters(
    val showAllProjects: Boolean,
    val includeWorktrees: Boolean,
    val showHidden: Boolean,
    val minPrompts: Int,
    val agentMode: AgentFilterMode,
    val dateFilter: SessionDateFilter = SessionDateFilter(),
)

object SessionListViewModel {
    data class Result(
        val visible: List<Session>,
        val scoped: List<Session>,
        val groups: List<ProjectGroup>,
        val emptyReason: EmptyReason,
    )

    fun build(
        sessions: List<Session>,
        roots: Collection<Path>,
        resolved: ResolvedPaths,
        filters: SessionListFilters,
        hiddenIds: Set<String> = emptySet(),
        pinned: (Session) -> Boolean = { false },
    ): Result {
        val scoped = visible(sessions, roots, resolved, filters.copy(agentMode = AgentFilterMode.All,
            dateFilter = SessionDateFilter()), hiddenIds)
        val visible = visible(sessions, roots, resolved, filters, hiddenIds)
        return Result(visible, scoped, SessionTreeModel.group(visible, pinned),
            emptyReason(sessions, visible, scoped, filters))
    }

    fun visible(
        sessions: List<Session>,
        roots: Collection<Path>,
        resolved: ResolvedPaths,
        filters: SessionListFilters,
        hiddenIds: Set<String> = emptySet(),
    ): List<Session> {
        val scoped = SessionContinuations(sessions).current().asSequence()
            .filter { filters.showHidden || it.id !in hiddenIds }
            .filter { SessionFilter.isWorthShowing(it, filters.minPrompts) }
            .filter {
                filters.showAllProjects || resolved.isUnderAny(it.cwd, roots) ||
                    (filters.includeWorktrees && resolved.belongsToRepository(it.cwd, roots))
            }
            .toList()
        val bounds = filters.dateFilter.bounds()
        return AgentSessionFilter.apply(scoped, filters.agentMode).filter { bounds.contains(it.lastActivityAt) }
    }

    fun emptyReason(
        all: List<Session>,
        visible: List<Session>,
        scoped: List<Session>,
        filters: SessionListFilters,
    ): EmptyReason = when {
        filters.dateFilter.period != DatePeriod.ALL -> EmptyReason.Date
        all.isEmpty() -> EmptyReason.NoSessions
        visible.isEmpty() && scoped.isNotEmpty() -> EmptyReason.Agent
        !filters.showAllProjects -> EmptyReason.Project
        else -> EmptyReason.Hidden
    }

    enum class EmptyReason { Date, NoSessions, Agent, Project, Hidden }
}
