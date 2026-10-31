package com.hedworth.seshlog.review

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.diff.util.Side
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import javax.swing.KeyStroke

/** D1 only: exercised through the real 262 extension point; never registered in the shipped plugin. */
internal class NativeDiffCommentProbe : DiffExtension() {
    data class Selection(val side: Side, val firstLine: Int, val lastLine: Int)
    class Context {
        val selections = mutableListOf<Selection>()
        val attachments = mutableListOf<Attachment>()
    }
    class Attachment(val editor: Editor, val action: AnAction, val marker: RangeHighlighter)

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val review = request.getUserData(REVIEW) ?: return
        // Unified mapping and other presentations require their own D4 proof; never guess a side.
        if (viewer !is SimpleDiffViewer) return
        for (side in Side.entries) {
            val editor = viewer.getEditor(side)
            val lifetime = Disposer.newDisposable("seshlog-diff-comment-probe")
            Disposer.register(viewer, lifetime)
            val action = object : DumbAwareAction("Add Review Comment") {
                override fun actionPerformed(e: AnActionEvent) {
                    val document = editor.document
                    if (document.textLength == 0) return
                    val selection = editor.selectionModel
                    val first = if (selection.hasSelection()) selection.selectionStart else editor.caretModel.offset
                    // An end offset at the next line's start does not select that next line.
                    val last = if (selection.hasSelection()) (selection.selectionEnd - 1).coerceAtLeast(first) else first
                    if (first == document.textLength && document.charsSequence.last() == '\n') return
                    review.selections += Selection(side, document.getLineNumber(first) + 1, document.getLineNumber(last) + 1)
                }
            }
            action.registerCustomShortcutSet(SHORTCUT, editor.contentComponent, lifetime)
            // A synthetic marker proves that native markup can own a clickable gutter action.
            val marker = editor.markupModel.addRangeHighlighter(0, 0, HighlighterLayer.ADDITIONAL_SYNTAX,
                TextAttributes(), HighlighterTargetArea.LINES_IN_RANGE)
            marker.gutterIconRenderer = object : GutterIconRenderer() {
                override fun getIcon() = AllIcons.General.Add
                override fun getClickAction() = action
                override fun getTooltipText() = "Add Review Comment (D1 probe)"
                override fun equals(other: Any?) = other === this
                override fun hashCode() = System.identityHashCode(this)
            }
            val attachment = Attachment(editor, action, marker)
            review.attachments += attachment
            Disposer.register(lifetime) {
                editor.markupModel.removeHighlighter(marker)
                review.attachments.remove(attachment)
            }
        }
    }

    companion object {
        val REVIEW: Key<Context> = Key.create("seshlog.test.diff.review")
        val SHORTCUT = CustomShortcutSet(KeyStroke.getKeyStroke("control alt shift K"))
    }
}
