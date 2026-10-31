package com.hedworth.seshlog.review

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.diff.util.Side
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import javax.swing.KeyStroke

internal data class ReviewDiffContext(
    val draftId: String, val path: String, val revision: String,
    val add: (ReviewSide, Int, Int) -> Unit, val focus: ReviewComment? = null,
)
internal data class ReviewEditorBinding(val context: ReviewDiffContext, val side: ReviewSide)

/** Only requests explicitly carrying Seshlog review context acquire comments or listeners. */
class ReviewDiffExtension : DiffExtension() {
    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val review = request.getUserData(REVIEW_CONTEXT) ?: return
        if (viewer !is SimpleDiffViewer) return
        val service = ReviewDraftService.getInstance()
        for (side in Side.entries) {
            val editor = viewer.getEditor(side)
            val lifetime = Disposer.newDisposable("seshlog-review-diff")
            Disposer.register(viewer, lifetime)
            val binding = ReviewEditorBinding(review, if (side == Side.LEFT) ReviewSide.BASE else ReviewSide.CURRENT)
            editor.putUserData(EDITOR_BINDING, binding)
            val action = object : DumbAwareAction("Add Review Comment", null, AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) = add(editor, binding)
            }
            action.registerCustomShortcutSet(SHORTCUT, editor.contentComponent, lifetime)
            val markers = mutableListOf<RangeHighlighter>()
            fun render() {
                markers.forEach { if (it.isValid) editor.markupModel.removeHighlighter(it) }
                markers.clear()
                if (editor.isDisposed) return
                val draft = runCatching { service.store.get(review.draftId) }.getOrNull() ?: return
                fun marker(line: Int, comment: ReviewComment? = null) {
                    if (line !in 0 until editor.document.lineCount || editor.document.textLength == 0) return
                    val value = editor.markupModel.addLineHighlighter(line, HighlighterLayer.ADDITIONAL_SYNTAX, TextAttributes())
                    value.gutterIconRenderer = object : GutterIconRenderer() {
                        override fun getIcon() = if (comment == null) AllIcons.General.Add else AllIcons.General.Balloon
                        override fun getTooltipText() = if (comment == null) "Add Review Comment" else
                            if (comment.validation == ReviewValidation.NEEDS_RECHECK) "Review comment — Needs recheck" else "Review comment"
                        override fun getClickAction(): AnAction = if (comment == null) action else object : DumbAwareAction("Edit Review Comment") {
                            override fun actionPerformed(e: AnActionEvent) {
                                val project = viewer.project ?: return
                                ReviewNoteDialog(project, "Edit Review Comment", comment.selected, comment.note) {
                                    service.store.edit(review.draftId, comment.id, it); service.changed()
                                }.show()
                            }
                        }
                        override fun equals(other: Any?) = other === this
                        override fun hashCode() = System.identityHashCode(this)
                    }
                    markers += value
                }
                draft.comments.filter { it.path == review.path && it.side == binding.side && it.fileRevision == review.revision }
                    .forEach { marker(it.firstLine - 1, it) }
                marker(editor.caretModel.logicalPosition.line)
            }
            editor.caretModel.addCaretListener(object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) = render()
            }, lifetime)
            val subscription = service.listen { if (!viewer.isDisposed) render() }
            Disposer.register(lifetime) {
                subscription.close()
                editor.putUserData(EDITOR_BINDING, null)
                markers.forEach { if (it.isValid) editor.markupModel.removeHighlighter(it) }
                markers.clear()
            }
            render()
            review.focus?.takeIf { it.side == binding.side }?.let { comment ->
                val line = (comment.firstLine - 1).coerceIn(0, editor.document.lineCount - 1)
                editor.caretModel.moveToOffset(editor.document.getLineStartOffset(line))
                val last = (comment.lastLine - 1).coerceIn(line, editor.document.lineCount - 1)
                editor.selectionModel.setSelection(editor.document.getLineStartOffset(line), editor.document.getLineEndOffset(last))
            }
        }
    }
    internal companion object {
        val REVIEW_CONTEXT: Key<ReviewDiffContext> = Key.create("seshlog.review.diff")
        val EDITOR_BINDING: Key<ReviewEditorBinding> = Key.create("seshlog.review.editor")
        val SHORTCUT = CustomShortcutSet(KeyStroke.getKeyStroke("control alt shift K"))
        fun add(editor: Editor, binding: ReviewEditorBinding) {
            val document = editor.document
            if (document.textLength == 0) return
            val selection = editor.selectionModel
            val first = if (selection.hasSelection()) selection.selectionStart else editor.caretModel.offset
            val last = if (selection.hasSelection()) (selection.selectionEnd - 1).coerceAtLeast(first) else first
            if (first == document.textLength && document.charsSequence.last() == '\n') return
            binding.context.add(binding.side, document.getLineNumber(first) + 1, document.getLineNumber(last) + 1)
        }
    }
}

class AddReviewCommentAction : DumbAwareAction("Add Review Comment") {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR)?.getUserData(ReviewDiffExtension.EDITOR_BINDING) != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val binding = editor.getUserData(ReviewDiffExtension.EDITOR_BINDING) ?: return
        ReviewDiffExtension.add(editor, binding)
    }
}
