package com.hedworth.seshlog.switcher

import com.hedworth.seshlog.index.SessionContinuations
import com.hedworth.seshlog.index.ResolvedPaths
import com.hedworth.seshlog.model.Checkout
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.worktree.OpenProjectSnapshot
import java.nio.file.Path

internal fun switcherRows(sessions: List<Session>, paths: ResolvedPaths, projectNames: Map<Path, List<String>>,
                          associated: Set<String>, unread: Set<String>, title: (Session) -> String,
                          hidden: (Session) -> Boolean): List<SwitcherRow> =
    switcherSessions(sessions, associated).map { session ->
        val observed = session.checkout ?: paths.checkout(session.cwd)
        val directory = paths.canonical(session.cwd)
        val names = if (observed != null) projectNames[observed.root] else
            projectNames.entries.filter { directory.startsWith(it.key) }.maxByOrNull { it.key.nameCount }?.value
        SwitcherRow(session, title(session), names.orEmpty(),
            hidden(session), session.id in associated, session.id in unread, observed)
    }

/** Collapse before hidden filtering, so a hidden successor never exposes its superseded predecessor. */
internal fun switcherSessions(sessions: List<Session>, associated: Set<String>): List<Session> {
    val current = SessionContinuations(sessions.map {
        if (it.id in associated && !it.isLive) it.copy(isLive = true) else it
    }).current().mapTo(HashSet()) { it.kind to it.id }
    return sessions.filter { (it.kind to it.id) in current }
}

/** Paths are already canonical worker observations. Never infer sibling checkout membership. */
internal fun <P> switcherDestination(directory: Path, checkout: Checkout?, projects: OpenProjectSnapshot<P>): P? =
    projects.directories.entries.filter { (project, root) ->
        directory.startsWith(root) && (checkout == null || projects.roots[project] == checkout.root)
    }.maxByOrNull { it.value.nameCount }?.key
