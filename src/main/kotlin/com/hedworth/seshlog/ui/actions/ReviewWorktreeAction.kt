package com.hedworth.seshlog.ui.actions

import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.Checkout
import com.hedworth.seshlog.ui.SeshlogDataKeys
import com.hedworth.seshlog.ui.WorktreeReviewDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class ReviewWorktreeAction : DumbAwareAction("Review Worktree Changes…", "Compare checkout-wide Git changes with a local base", null) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val checkout = checkoutFrom(e)
        e.presentation.isVisible = e.project != null && checkout != null
        e.presentation.isEnabled = checkout?.available == true && e.project?.isDisposed == false
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val checkout = checkoutFrom(e)?.takeIf { it.available } ?: return
        WorktreeReviewDialog(project, checkout).show()
    }
}

/** Cached metadata only; callers revalidate on a worker before touching Git. */
internal fun checkoutFrom(e: AnActionEvent): Checkout? {
    val index = SessionIndex.getInstance()
    sessionFrom(e)?.let { session ->
        val latest = index.sessionById(session.id)?.takeIf { it.kind == session.kind } ?: session
        return latest.checkout ?: index.resolvedPaths.checkout(latest.cwd)
    }
    val group = e.getData(SeshlogDataKeys.PROJECT_GROUP) ?: return null
    return index.resolvedPaths.checkout(group.cwd) ?: group.sessions.firstNotNullOfOrNull { it.checkout }
}
