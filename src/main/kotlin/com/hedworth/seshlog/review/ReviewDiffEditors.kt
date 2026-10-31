package com.hedworth.seshlog.review

import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.editor.DiffEditorViewerFileEditor
import com.intellij.diff.editor.SimpleDiffVirtualFile
import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile

internal object ReviewDiffEditors {
    private val identity = Key.create<List<String>>("seshlog.review.editor.identity")
    fun open(project: Project, request: SimpleDiffRequest, context: ReviewDiffContext,
             openFiles: () -> List<VirtualFile> = { FileEditorManager.getInstance(project).openFiles.toList() },
             show: (SimpleDiffVirtualFile) -> Array<out FileEditor> = { DiffEditorTabFilesManager.getInstance(project).showDiffFile(it, true) },
    ): SimpleDiffVirtualFile {
        val key = listOf(context.draftId, context.path, context.revision)
        // Only open editor tabs retain their snapshots. Closing the review panel does not retain
        // another cache of every diff ever opened, and reopening can find the same native tab.
        val tab = openFiles().filterIsInstance<SimpleDiffVirtualFile>()
            .firstOrNull { it.getUserData(identity) == key }
            ?: SimpleDiffVirtualFile(request).apply { putUserData(identity, key) }
        val editors = show(tab)
        context.focus?.let { comment ->
            editors.filterIsInstance<DiffEditorViewerFileEditor>().forEach { fileEditor ->
                val viewer = (fileEditor.editorViewer as? DiffRequestProcessor)?.activeViewer as? SimpleDiffViewer ?: return@forEach
                val editor = if (comment.side == ReviewSide.BASE) viewer.editor1 else viewer.editor2
                val first = (comment.firstLine - 1).coerceIn(0, editor.document.lineCount - 1)
                val last = (comment.lastLine - 1).coerceIn(first, editor.document.lineCount - 1)
                editor.caretModel.moveToOffset(editor.document.getLineStartOffset(first))
                editor.selectionModel.setSelection(editor.document.getLineStartOffset(first), editor.document.getLineEndOffset(last))
                editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            }
        }
        return tab
    }
}
