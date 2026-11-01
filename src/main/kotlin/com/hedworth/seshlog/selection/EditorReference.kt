package com.hedworth.seshlog.selection

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

/** A reference to the selected lines; selected source text is never copied or saved. */
internal data class EditorReference(val path: String, val firstLine: Int, val lastLine: Int, val unsaved: Boolean) {
    fun text(): String {
        require(path.isNotBlank() && path.length <= 16_384 && path.none { it < ' ' || it in '\u007f'..'\u009f' }) {
            "This file path cannot be added to terminal input."
        }
        require(firstLine > 0 && lastLine >= firstLine)
        val quoted = path.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$quoted\":$firstLine${if (lastLine == firstLine) "" else "-$lastLine"}${if (unsaved) " (unsaved editor)" else ""} "
    }

    companion object {
        fun supported(project: Project?, editor: Editor?): Boolean = project != null && !project.isDisposed &&
            editor != null && !editor.isDisposed && !editor.isViewer && editor.project === project &&
            editor.selectionModel.hasSelection() && editor.caretModel.caretCount == 1 &&
            FileDocumentManager.getInstance().getFile(editor.document)?.let { it.isValid && it.isInLocalFileSystem && !it.isDirectory } == true

        fun capture(project: Project, editor: Editor): EditorReference {
            require(supported(project, editor)) { "Select one range in a local file-backed editor." }
            val documents = FileDocumentManager.getInstance()
            val file = requireNotNull(documents.getFile(editor.document))
            val selection = editor.selectionModel
            return EditorReference(file.path, editor.document.getLineNumber(selection.selectionStart) + 1,
                editor.document.getLineNumber(selection.selectionEnd - 1) + 1,
                documents.isDocumentUnsaved(editor.document))
        }
    }
}
