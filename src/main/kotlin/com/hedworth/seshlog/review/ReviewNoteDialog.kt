package com.hedworth.seshlog.review

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

internal class ReviewNoteDialog(project: Project, caption: String, excerpt: String, initial: String, private val commit: (String) -> Unit) : DialogWrapper(project) {
    private val note = JTextArea(initial, 8, 55).apply { lineWrap = true; wrapStyleWord = true; accessibleContext.accessibleName = "Review comment (4,000 characters maximum)" }
    private val code = JTextArea(excerpt, 5, 55).apply { isEditable = false; accessibleContext.accessibleName = "Selected code excerpt" }
    private var disposed = false
    init { title = caption; setOKButtonText("Save Comment"); init() }
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(8, 8)).apply {
        preferredSize = Dimension(650, 330)
        add(JBScrollPane(code), BorderLayout.NORTH)
        add(JBScrollPane(note), BorderLayout.CENTER)
        add(JLabel("Maximum 4,000 characters. Drafts remain in memory until IDE exit."), BorderLayout.SOUTH)
    }
    override fun getPreferredFocusedComponent() = note
    override fun doOKAction() {
        val text = note.text
        if (text.isBlank() || text.length > 4000) { setErrorText("Enter 1–4,000 characters; your note has been kept"); return }
        isOKActionEnabled = false
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val result = runCatching { commit(text) }
            app.invokeLater({
                if (!disposed) {
                    isOKActionEnabled = true
                    result.fold({ close(OK_EXIT_CODE) }, { setErrorText(it.message ?: "Could not save this comment; your note has been kept") })
                }
            }, ModalityState.any())
        }
    }
    override fun dispose() { disposed = true; super.dispose() }
}

internal object ReviewCommentUi {
    fun add(project: Project, context: ReviewDiffContext, side: ReviewSide, first: Int, last: Int) {
        val service = ReviewDraftService.getInstance()
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val excerpt = runCatching {
                val draft = service.store.get(context.draftId)
                check(draft.comments.size < 50) { "This draft already contains 50 comments" }
                val file = draft.files[context.path] ?: error("Reopen the review")
                check(file.revision == context.revision) { "This diff is outdated; reopen the refreshed diff" }
                file.selection(side, first, last)
            }
            app.invokeLater({
                if (!project.isDisposed) excerpt.fold({ text ->
                    ReviewNoteDialog(project, "Add Review Comment — ${context.path} ($first–$last)", text, "") { note ->
                        service.store.add(context.draftId, context.path, side, first, last, note, context.revision)
                        service.changed()
                    }.show()
                }, { Messages.showInfoMessage(project, it.message ?: "Narrow the selection and try again", "Review Comment") })
            }, ModalityState.any())
        }
    }
}
