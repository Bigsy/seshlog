package com.hedworth.seshlog.ui.actions

import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.worktree.WorktreeActionPresentation
import com.hedworth.seshlog.worktree.WorktreeProjects
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

class OpenWorktreeAction(
    private val opener: (Project, Session) -> Unit = WorktreeProjects::open,
) : WorktreeSessionAction(forkSession = false) {
    override fun perform(project: Project, session: Session) = opener(project, session)
}

class OpenWorktreeAndForkAction(
    private val opener: (Project, Session) -> Unit = WorktreeProjects::openAndFork,
) : WorktreeSessionAction(forkSession = true) {
    override fun perform(project: Project, session: Session) = opener(project, session)
}

abstract class WorktreeSessionAction(private val forkSession: Boolean) : SessionAction(
    if (forkSession) "Open Worktree and Fork Session" else "Open Worktree in IntelliJ",
    if (forkSession) "Open the worktree and start an independent conversation fork in its terminal"
    else "Open the session's recorded checkout in a new window", AllIcons.Vcs.Branch,
) {
    override fun update(e: AnActionEvent) {
        val index = SessionIndex.getInstance()
        val session = sessionFrom(e)?.let { index.sessionById(it.id) ?: it }
        val state = WorktreeActionPresentation.of(session,
            index.openProjectRoots.filterKeys { !it.isDisposed }.values, index.protectedDataRoots, forkSession)
        e.presentation.isVisible = e.project != null && state.visible
        e.presentation.isEnabled = e.project?.isDisposed == false && state.enabled
        e.presentation.text = state.text
        e.presentation.description = state.description
    }
}
