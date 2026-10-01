package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.DateBounds
import com.hedworth.seshlog.index.GitRepository
import com.hedworth.seshlog.index.SessionFilter
import com.hedworth.seshlog.index.SessionContinuations
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.AgentFilterMode
import com.hedworth.seshlog.settings.AgentSessionFilter
import java.nio.file.Path

/**
 * The non-search part of the session list scope, shared by attention surfaces that live outside
 * the tool window. Filesystem lookups are injected so the policy remains unit-testable.
 */
internal object AttentionProjectFilter {
    fun apply(
        sessions: List<Session>,
        roots: Collection<Path>,
        showAllProjects: Boolean,
        includeWorktrees: Boolean,
        agentMode: AgentFilterMode,
        hiddenIds: Set<String>,
        showHidden: Boolean = false,
        minPrompts: Int,
        dateBounds: DateBounds? = null,
        canonical: (Path) -> Path = SessionFilter::canonical,
        repository: (Path) -> Path? = GitRepository::commonDir,
    ): List<Session> {
        val canonicalRoots = roots.map(canonical)
        val repositories = if (includeWorktrees && !showAllProjects) {
            roots.mapNotNull(repository).toSet()
        } else emptySet()
        val scoped = SessionContinuations(sessions).current().asSequence()
            .filter { showHidden || it.id !in hiddenIds }
            .filter { SessionFilter.isWorthShowing(it, minPrompts) }
            .filter { session ->
                showAllProjects || canonical(session.cwd).startsWithAny(canonicalRoots) ||
                    (includeWorktrees && repository(session.cwd) in repositories)
            }
            .toList()
        return AgentSessionFilter.apply(scoped, agentMode).filter { dateBounds == null || dateBounds.contains(it.lastActivityAt) }
    }

    private fun Path.startsWithAny(roots: Collection<Path>): Boolean = roots.any { startsWith(it) }
}
