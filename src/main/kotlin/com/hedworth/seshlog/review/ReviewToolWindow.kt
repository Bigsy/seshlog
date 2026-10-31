package com.hedworth.seshlog.review

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory

/** Let the platform place/remap this tab in Commit, or Changes with the classic VCS layout. */
internal object ReviewToolWindow {
    private val draftKey = Key.create<String>("seshlog.review.draft")
    fun open(project: Project, draftId: String): Content {
        val window = ChangesViewContentManager.getToolWindowFor(project, ChangesViewContentManager.LOCAL_CHANGES)
        check(window?.isAvailable == true) {
            "The Commit/Changes tool window is unavailable in this project. Open the review in the session's checkout window with VCS enabled."
        }
        val content = content(project, draftId)
        ChangesViewContentManager.getInstance(project).setSelectedContent(content, true)
        window.activate(null)
        return content
    }
    internal fun content(project: Project, draftId: String): Content {
        val manager = ChangesViewContentManager.getInstance(project)
        val content = manager.findContents { it.getUserData(draftKey) == draftId }.firstOrNull() ?: run {
            lateinit var tab: Content
            val panel = ReviewCommentsPanel(project, draftId) { if (tab.isValid) manager.removeContent(tab) }
            val recipient = ReviewDraftService.getInstance().store.get(draftId).recipient
            tab = ContentFactory.getInstance().createContent(panel, "Review · ${recipient.kind.displayName} ${recipient.sessionId.takeLast(8)}", true)
            tab.putUserData(draftKey, draftId)
            tab.putUserData(ChangesViewContentManager.IS_IN_COMMIT_TOOLWINDOW_KEY, true)
            tab.setDisposer(panel)
            tab.setPreferredFocusableComponent(panel)
            manager.addContent(tab)
            tab
        }
        return content
    }
}
