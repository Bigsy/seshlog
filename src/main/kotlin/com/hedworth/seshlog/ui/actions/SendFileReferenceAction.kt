package com.hedworth.seshlog.ui.actions

import com.hedworth.seshlog.selection.EditorReference
import com.hedworth.seshlog.terminal.TerminalTabs
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction

/** Insert into the highlighted terminal without identifying its program or submitting its input. */
class SendFileReferenceAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = EditorReference.supported(e.project, e.getData(CommonDataKeys.EDITOR))
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        fun unavailable(message: String) = notify(project, message, NotificationType.WARNING)
        val text = try { EditorReference.capture(project, editor).text() } catch (ex: IllegalArgumentException) {
            unavailable(ex.message ?: "Select lines in a local file.")
            return
        }
        val content = TerminalTabs.selectedContent(project)
        val terminal = content?.let { TerminalTabs.terminalOf(project, it) }
        if (content == null || terminal == null) {
            unavailable("Select a terminal tab in this project's Terminal tool window, then try again.")
            return
        }
        // Capture and paste in the same EDT action, without queued delivery or session lookups.
        // Both adapters queue the actual input write; neither sends Enter or clears existing input.
        try {
            if (terminal.paste(text)) TerminalTabs.focusSelected(project, content)
            else {
                unavailable("Could not add the reference: bracketed paste is unavailable in the selected terminal. Check the terminal before trying again.")
            }
        } catch (_: Exception) {
            unavailable("Could not add the file reference. Check the terminal before trying again.")
        }
    }
}
