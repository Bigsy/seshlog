package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.SearchRequestScope
import com.hedworth.seshlog.index.SessionAttention
import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.index.TextQuery
import com.hedworth.seshlog.model.ConversationEntry
import com.hedworth.seshlog.model.Role
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.SessionAttentionState
import com.hedworth.seshlog.settings.SessionOrganisation
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.util.Collections
import java.util.HashMap
import java.util.IdentityHashMap
import javax.swing.AbstractAction
import javax.swing.KeyStroke

/** Opens a conversation as a read-only in-memory Markdown editor tab. */
object ConversationEditorTabs {
    /** Controllers are removed when their in-memory editor tab closes. */
    private val controllers = Collections.synchronizedMap(HashMap<VirtualFile, Controller>())

    fun open(
        project: Project,
        session: Session,
        query: String = "",
        startAtLatest: Boolean = false,
        loader: ((Session) -> List<ConversationEntry>)? = null,
    ): VirtualFile {
        val title = SessionOrganisation.getInstance().title(session)
            .replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifBlank { "conversation" }
        val markdownType = FileTypeManager.getInstance().getFileTypeByExtension("md")
            .takeUnless { it == UnknownFileType.INSTANCE } ?: PlainTextFileType.INSTANCE
        val file = LightVirtualFile(
            "$title-${session.id.takeLast(8)}.md",
            markdownType,
            MarkdownConversation.placeholder(title),
        )
        file.setWritable(false)
        val manager = FileEditorManager.getInstance(project)
        val opened = manager.openFile(file, true)
        val editor = (manager.selectedEditor as? TextEditor)?.takeIf { it.file == file }?.editor
            ?: opened.filterIsInstance<TextEditor>().firstOrNull()?.editor
        val controller = Controller(project, session, file, editor, query, startAtLatest, loader)
        controllers[file] = controller
        project.messageBus.connect(controller).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileClosed(source: FileEditorManager, closed: VirtualFile) {
                    if (closed == file) Disposer.dispose(controller)
                }
            },
        )
        controller.load()
        return file
    }

    fun sessionFor(project: Project): Session? {
        val files = FileEditorManager.getInstance(project).selectedFiles
        return files.asSequence().mapNotNull { controllers[it]?.session }.firstOrNull()
    }

    /** Move to the next/previous occurrence of the query carried from the tree search. */
    internal fun navigate(project: Project, forward: Boolean): Boolean {
        val manager = FileEditorManager.getInstance(project)
        val controller = manager.selectedFiles.asSequence().mapNotNull { controllers[it] }.firstOrNull()
            ?: return false
        return controller.navigate(forward)
    }

    internal fun markdown(title: String, entries: List<ConversationEntry>): String =
        MarkdownConversation.build(title, entries).text

    private class Controller(
        private val project: Project,
        val session: Session,
        private val file: LightVirtualFile,
        private val initialEditor: Editor?,
        private val query: String,
        private val startAtLatest: Boolean,
        private val loader: ((Session) -> List<ConversationEntry>)?,
    ) : Disposable {
        private val scope = SearchRequestScope()
        private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Seshlog conversation", 1)
        private var document = MarkdownConversation.placeholder(SessionOrganisation.getInstance().title(session))
        private var latestReplyEnd = -1
        private var completionReceipt: SessionAttention.Completion? = null
        private val navigationEditors = Collections.newSetFromMap(IdentityHashMap<Editor, Boolean>())
        private val observer = CompletionViewObserver(this) {
            val editor = activeEditor() ?: return@CompletionViewObserver
            installNavigation(editor)
            if (completionReceipt == null || !CompletionViewObserver.isViewed(editor.component)) return@CompletionViewObserver
            val end = latestReplyEnd
            if (end < 0) return@CompletionViewObserver
            val point = editor.visualPositionToXY(editor.offsetToVisualPosition(end.coerceAtMost(editor.document.textLength - 1)))
            if (editor.scrollingModel.visibleArea.contains(point)) {
                SessionAttentionState.getInstance().viewed(session.id, completionReceipt)
            }
        }

        init { Disposer.register(project, this) }

        fun load() {
            val receipt = SessionAttentionState.getInstance().receipt(session)
            val cancelled = scope.begin()
            executor.execute {
                val result = runCatching {
                    loader?.invoke(session)
                        ?: SessionIndex.getInstance().providerFor(session).conversationEntries(session)
                }
                ApplicationManager.getApplication().invokeLater {
                    if (cancelled() || project.isDisposed) return@invokeLater
                    result.onSuccess { entries ->
                        val built = MarkdownConversation.build(SessionOrganisation.getInstance().title(session), entries, session)
                        document = built.text
                        latestReplyEnd = built.latestReplyEnd
                        completionReceipt = if (entries.none { it.truncated || !it.searchable }) receipt else null
                        replaceContent(document)
                        positionEditor()
                    }.onFailure {
                        document = MarkdownConversation.failure(SessionOrganisation.getInstance().title(session))
                        latestReplyEnd = -1
                        completionReceipt = null
                        replaceContent(document)
                    }
                }
            }
        }

        private fun activeEditor(): Editor? {
            val manager = FileEditorManager.getInstance(project)
            val selected = manager.getSelectedEditors().asSequence()
                .filterIsInstance<TextEditor>().firstOrNull { it.file == file }?.editor
            val editor = selected?.takeUnless { it.isDisposed }
                ?: initialEditor?.takeUnless { it.isDisposed }
                ?: manager.getEditors(file).filterIsInstance<TextEditor>().firstOrNull()?.editor
                    ?.takeUnless { it.isDisposed }
            if (editor != null) installNavigation(editor)
            return editor
        }

        private fun installNavigation(editor: Editor) {
            if (!navigationEditors.add(editor)) return
            val component = editor.contentComponent
            component.inputMap.put(KeyStroke.getKeyStroke("ctrl G"), "seshlog.nextConversationMatch")
            component.inputMap.put(KeyStroke.getKeyStroke("ctrl shift G"), "seshlog.previousConversationMatch")
            component.actionMap.put("seshlog.nextConversationMatch", object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent) { navigate(true) }
            })
            component.actionMap.put("seshlog.previousConversationMatch", object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent) { navigate(false) }
            })
            object : DumbAwareAction() {
                override fun actionPerformed(event: com.intellij.openapi.actionSystem.AnActionEvent) { navigate(true) }
            }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0)), component, this)
            object : DumbAwareAction() {
                override fun actionPerformed(event: com.intellij.openapi.actionSystem.AnActionEvent) { navigate(false) }
            }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_F3, KeyEvent.SHIFT_DOWN_MASK)), component, this)
        }

        private fun replaceContent(content: String) {
            if (project.isDisposed) return
            ApplicationManager.getApplication().runWriteAction {
                file.setWritable(true)
                file.setContent(this, content, true)
                activeEditor()?.document?.let { editorDocument ->
                    if (editorDocument.text != content) {
                        editorDocument.setReadOnly(false)
                        editorDocument.setText(content)
                    }
                    editorDocument.setReadOnly(true)
                }
                file.setWritable(false)
            }
        }

        private fun positionEditor() {
            val editor = activeEditor() ?: return
            if (editor.isDisposed) return
            val ranges = TextQuery.parse(query).ranges(editor.document.text)
            val offset = when {
                startAtLatest -> editor.document.textLength
                else -> ranges.firstOrNull()?.first ?: 0
            }
            editor.caretModel.moveToOffset(offset.coerceIn(0, editor.document.textLength))
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }

        fun navigate(forward: Boolean): Boolean {
            val editor = activeEditor() ?: return false
            val ranges = TextQuery.parse(query).ranges(editor.document.text)
            if (ranges.isEmpty()) return false
            val caret = editor.caretModel.offset
            val next = if (forward) {
                ranges.firstOrNull { it.first > caret } ?: ranges.first()
            } else {
                ranges.lastOrNull { it.first < caret } ?: ranges.last()
            }
            editor.caretModel.moveToOffset(next.first)
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            return true
        }

        override fun dispose() {
            synchronized(controllers) {
                if (controllers[file] === this) controllers.remove(file)
            }
            scope.dispose()
            executor.shutdownNow()
        }
    }
}

private data class BuiltMarkdown(val text: String, val latestReplyEnd: Int)

private object MarkdownConversation {
    fun placeholder(title: String) = "# ${escapeHeading(title)}\n\n_Loading conversation…_\n"
    fun failure(title: String) = "# ${escapeHeading(title)}\n\n_Conversation could not be read._\n"

    fun build(title: String, entries: List<ConversationEntry>, session: Session? = null): BuiltMarkdown {
        val out = StringBuilder("# ${escapeHeading(title)}\n\n")
        session?.let {
            if (it.subagentTranscriptPaths.isNotEmpty()) {
                out.append("_Includes ${it.subagentTranscriptPaths.size} Claude subagent transcript")
                if (it.subagentTranscriptPaths.size != 1) out.append('s')
                out.append("._\n\n")
            }
            it.continuationId?.let { successor -> out.append("_Continues in session `$successor`._\n\n") }
        }
        var latestEnd = -1
        entries.forEach { entry ->
            out.append("## ").append(escapeHeading(entry.label)).append("\n\n")
            val start = out.length
            out.append(entry.text).append("\n\n")
            if (!entry.isTool && entry.message.role == Role.ASSISTANT && entry.text.isNotBlank()) latestEnd = out.length - 3
            out.append("---\n\n")
            if (start == out.length) out.append('\n')
        }
        return BuiltMarkdown(out.toString(), latestEnd)
    }

    private fun escapeHeading(value: String): String = value.replace("\n", " ").replace("\r", " ")
}
