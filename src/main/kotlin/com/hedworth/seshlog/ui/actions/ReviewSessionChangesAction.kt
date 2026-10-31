package com.hedworth.seshlog.ui.actions

import com.hedworth.seshlog.review.ReviewCommentsDialog
import com.hedworth.seshlog.review.ReviewDraftService
import com.hedworth.seshlog.review.ReviewRecipient
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages

class ReviewSessionChangesAction : DumbAwareAction("Review Changes and Comment…") {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && sessionFrom(e) != null && checkoutFrom(e)?.available == true
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = sessionFrom(e) ?: return
        val checkout = checkoutFrom(e)?.takeIf { it.available } ?: return
        try {
            val draft = ReviewDraftService.getInstance().store.open(ReviewRecipient(session.kind, session.id, checkout))
            ReviewCommentsDialog(project, draft.id).show()
        } catch (e: Exception) { Messages.showInfoMessage(project, e.message ?: "Could not open review", "Review Changes") }
    }
}
