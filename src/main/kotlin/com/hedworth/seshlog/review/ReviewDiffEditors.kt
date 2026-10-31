package com.hedworth.seshlog.review

import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.editor.DiffEditorViewerFileEditor
import com.intellij.diff.editor.DiffViewerVirtualFile
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
             show: (ReviewDiffVirtualFile) -> Array<out FileEditor> = { DiffEditorTabFilesManager.getInstance(project).showDiffFile(it, true) },
    ): ReviewDiffVirtualFile {
        val key = listOf(context.draftId, context.path, context.revision)
        var created: ReviewDiffVirtualFile? = null
        val tab = openFiles().filterIsInstance<ReviewDiffVirtualFile>()
            .firstOrNull { it.isValid && it.getUserData(identity) == key }
            ?: run {
                val lease = ReviewDraftService.getInstance().store.retainDiff(context.draftId, context.path, context.revision)
                ReviewDiffVirtualFile(request, lease).apply {
                    putUserData(identity, key)
                    created = this
                }
            }
        val editors = try { show(tab).also { check(it.isNotEmpty()) { "The native diff editor could not be opened" } } }
            catch (e: Exception) { created?.releaseIfUnused(); throw e }
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

/** A request may be unassigned during presentation changes. Only processor disposal ends ownership. */
internal class ReviewDiffVirtualFile(source: SimpleDiffRequest, private val lease: AutoCloseable) :
    DiffViewerVirtualFile(source.title ?: "Review changes") {
    val request = SimpleDiffRequest(source.title, ArrayList(source.contents), source.contentTitles).apply {
        fun <T> copy(key: Key<T>) { putUserData(key, source.getUserData(key)) }
        copy(ReviewDiffExtension.REVIEW_CONTEXT)
        copy(com.intellij.diff.util.DiffUserDataKeys.FORCE_READ_ONLY)
        copy(com.intellij.diff.util.DiffUserDataKeys.NOTIFICATION_PROVIDERS)
        copy(com.intellij.diff.util.DiffUserDataKeys.SCROLL_TO_LINE)
    }
    private var processors = 0
    private var released = false
    override fun createViewer(project: Project): DiffRequestProcessor = createProcessor(project)
    fun createProcessor(project: Project): DiffRequestProcessor {
        check(!released) { "Reopen this file from the review panel" }
        processors++
        return object : DiffRequestProcessor(project) {
            override fun updateRequest(force: Boolean, scrollToChangePolicy: com.intellij.diff.util.DiffUserDataKeysEx.ScrollToPolicy?) {
                applyRequest(request, force, scrollToChangePolicy)
            }
            override fun dispose() = com.intellij.util.ui.UIUtil.invokeLaterIfNeeded { disposeOnEdt() }
            private fun disposeOnEdt() {
                if (isDisposed) return
                try { super.dispose() } finally { processors--; releaseIfUnused() }
            }
        }
    }
    fun releaseIfUnused() {
        if (released || processors != 0) return
        released = true
        // A recent-files entry may retain the virtual file after closure. Clear the
        // request as well as the budget lease so it cannot retain old document text.
        (request.contents as MutableList).clear()
        request.putUserData(ReviewDiffExtension.REVIEW_CONTEXT, null)
        request.putUserData(com.intellij.diff.util.DiffUserDataKeys.NOTIFICATION_PROVIDERS, null)
        setValid(false)
        lease.close()
    }
}
