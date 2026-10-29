package com.hedworth.seshlog.ui.actions

import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Checkout
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.worktree.WorktreeCreationOptions
import com.hedworth.seshlog.worktree.WorktreeProjects
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.panel
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JTextField

class ForkNewWorktreeAction : SessionAction("Fork into New Worktree…", "Create a branch and separate working files for this conversation", null) {
    override fun update(e: AnActionEvent) {
        val session = sessionFrom(e)
        val checkout = checkoutFrom(e)
        e.presentation.isVisible = e.project != null && session?.kind in setOf(AgentKind.CLAUDE_CODE, AgentKind.CODEX) && checkout != null
        e.presentation.isEnabled = checkout?.available == true && session?.location?.uncertain != true
    }
    override fun perform(project: Project, session: Session) {
        val index = SessionIndex.getInstance()
        val latest = index.sessionById(session.id)?.takeIf { it.kind == session.kind } ?: return
        val checkout = latest.checkout ?: index.resolvedPaths.checkout(latest.cwd) ?: return
        val dialog = NewWorktreeDialog(project, checkout)
        if (dialog.showAndGet()) WorktreeProjects.createAndFork(project, latest, checkout, dialog.options)
    }
}

private class NewWorktreeDialog(project: Project, checkout: Checkout) : DialogWrapper(project, false) {
    private val base = JTextField("HEAD", 30)
    private val branch = JTextField("", 30)
    private val destination = JTextField(checkout.root.resolveSibling("${checkout.root.fileName}-fork").toString(), 45)
    val options get() = WorktreeCreationOptions(base.text.trim(), branch.text.trim(), Path.of(destination.text.trim()))
    init { title = "Fork into New Worktree"; setOKButtonText("Create Worktree and Fork"); init() }
    override fun createCenterPanel(): JComponent = panel {
        row("Starting ref:") { cell(base) }
        row("New branch:") { cell(branch) }
        row("New folder:") { cell(destination) }
        row { comment("Starts from committed files at the selected local ref. Uncommitted and ignored files stay in the source checkout. No dependency setup or file copying is performed.") }
        row { comment("If opening or launching fails, the new branch and worktree are kept. The original session stays running.") }
    }
    override fun doValidate(): ValidationInfo? = when {
        base.text.isBlank() -> ValidationInfo("Enter a local branch, tag or commit", base)
        branch.text.isBlank() || branch.text.startsWith("-") -> ValidationInfo("Enter a new branch name", branch)
        runCatching { Path.of(destination.text.trim()).isAbsolute }.getOrDefault(false).not() -> ValidationInfo("Enter an absolute destination path", destination)
        else -> null
    }
}
