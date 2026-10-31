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
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile

internal object ReviewDiffEditors {
    private val identity = Key.create<List<String>>("seshlog.review.editor.identity")
    fun open(project: Project, request: SimpleDiffRequest, context: ReviewDiffContext,
             openFiles: () -> List<VirtualFile> = { FileEditorManager.getInstance(project).openFiles.toList() },
             show: (SimpleDiffVirtualFile) -> Array<out FileEditor> = { DiffEditorTabFilesManager.getInstance(project).showDiffFile(it, true) },
    ): SimpleDiffVirtualFile {
        val key = listOf(context.draftId, context.path, context.revision)
        var retained: RetainedRequest? = null
        val tab = openFiles().filterIsInstance<SimpleDiffVirtualFile>()
            .firstOrNull { it.isValid && it.getUserData(identity) == key }
            ?: run {
                val lease = ReviewDraftService.getInstance().store.retainDiff(context.draftId, context.path, context.revision)
                val owned = RetainedRequest(request, lease).also { retained = it }
                SimpleDiffVirtualFile(owned).apply {
                    putUserData(identity, key)
                    owned.invalidate = { setValid(false) }
                }
            }
        val editors = try { show(tab).also { check(it.isNotEmpty()) { "The native diff editor could not be opened" } } }
            catch (e: Exception) { retained?.release(); throw e }
        retained?.let { owned ->
            // Failed/deferred assignment must release too; normal multi-editor assignment is
            // reference counted by the request, so closing one split cannot release another.
            editors.forEach { editor -> Disposer.register(editor) { owned.releaseIfUnassigned() } }
        }
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

    private class RetainedRequest(request: SimpleDiffRequest, private val lease: AutoCloseable) :
        SimpleDiffRequest(request.title, ArrayList(request.contents), request.contentTitles) {
        var invalidate: () -> Unit = {}
        private var assignments = 0
        private var released = false
        init {
            fun <T> copy(key: Key<T>) { putUserData(key, request.getUserData(key)) }
            copy(ReviewDiffExtension.REVIEW_CONTEXT)
            copy(com.intellij.diff.util.DiffUserDataKeys.FORCE_READ_ONLY)
            copy(com.intellij.diff.util.DiffUserDataKeys.NOTIFICATION_PROVIDERS)
            copy(com.intellij.diff.util.DiffUserDataKeys.SCROLL_TO_LINE)
        }
        override fun onAssigned(value: Boolean) {
            super.onAssigned(value)
            assignments += if (value) 1 else -1
            if (!value) releaseIfUnassigned()
        }
        fun releaseIfUnassigned() { if (assignments <= 0) release() }
        fun release() {
            if (released) return
            released = true
            // A recent-files entry may retain the virtual file after closure. Clear the
            // request as well as the budget lease so it cannot retain old document text.
            (contents as MutableList).clear()
            putUserData(ReviewDiffExtension.REVIEW_CONTEXT, null)
            putUserData(com.intellij.diff.util.DiffUserDataKeys.NOTIFICATION_PROVIDERS, null)
            invalidate()
            lease.close()
        }
    }
}
