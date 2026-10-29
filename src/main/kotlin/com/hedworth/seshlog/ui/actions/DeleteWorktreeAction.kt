package com.hedworth.seshlog.ui.actions

import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.Checkout
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.terminal.OwnedTerminalTabs
import com.hedworth.seshlog.ui.SeshlogDataKeys
import com.hedworth.seshlog.worktree.WorktreeDeletionOptions
import com.hedworth.seshlog.worktree.WorktreeDeletionPresentation
import com.hedworth.seshlog.worktree.WorktreeProjects
import com.hedworth.seshlog.worktree.deletableBranch
import com.hedworth.seshlog.worktree.worktreeSessions
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import java.nio.file.Path
import javax.swing.JComponent

/** Folder rows only. The tree's cached checkout decides visibility; the delete itself revalidates. */
class DeleteWorktreeAction : DumbAwareAction("Delete Worktree…", "Remove this worktree's folder with git; its conversations are kept", null) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val state = target(e)?.let { (checkout, sessions) ->
            val projects = WorktreeProjects.projectSet()
            WorktreeDeletionPresentation.of(checkout, sessions,
                projects.flatMapTo(HashSet()) { OwnedTerminalTabs.getInstance(it).sessionIds },
                projects.mapNotNull { project -> project.basePath?.let { Path.of(it) } },
                SessionIndex.getInstance().protectedDataRoots)
        }
        e.presentation.isVisible = e.project != null && state?.visible == true
        e.presentation.isEnabled = e.project?.isDisposed == false && state?.enabled == true
        state?.let { e.presentation.description = it.description }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (checkout, sessions) = target(e) ?: return
        val dialog = DeleteWorktreeDialog(project, checkout, sessions.size)
        if (dialog.showAndGet()) WorktreeProjects.delete(project, checkout, dialog.options)
    }

    private fun target(e: AnActionEvent): Pair<Checkout, List<Session>>? {
        val group = e.getData(SeshlogDataKeys.PROJECT_GROUP) ?: return null
        val index = SessionIndex.getInstance()
        val checkout = index.resolvedPaths.checkout(group.cwd) ?: group.sessions.firstNotNullOfOrNull { it.checkout } ?: return null
        return checkout to worktreeSessions(checkout, index.sessions, index.resolvedPaths)
    }
}

/** Names exactly what goes and what stays; git still refuses uncommitted or untracked changes. */
internal class DeleteWorktreeDialog(project: Project, private val checkout: Checkout, private val sessions: Int) :
    DialogWrapper(project, false) {
    private val hide = JBCheckBox("Hide its $sessions ${if (sessions == 1) "session" else "sessions"} from the list", true)
    private val branch = checkout.deletableBranch?.let { JBCheckBox("Also delete branch $it if it is fully merged", false) }
    val options get() = WorktreeDeletionOptions(hide.isSelected && sessions > 0, branch?.isSelected == true)

    init {
        title = "Delete Worktree"
        setOKButtonText("Delete Worktree")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Folder:") { label(checkout.root.toString()) }
        row("Branch:") { label(checkout.branchLabel) }
        row {
            comment("Git deletes the folder only if it has no uncommitted or untracked changes. Committed work stays " +
                "on the branch, and conversations stay readable in Seshlog; hidden sessions can be shown again.")
        }
        if (sessions > 0) row { cell(hide) }
        branch?.let { row { cell(it) } }
    }
}
