package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.SearchHit
import com.hedworth.seshlog.index.SearchSources
import com.hedworth.seshlog.index.SearchContext
import com.hedworth.seshlog.index.SearchMatchSource
import com.hedworth.seshlog.index.lexical.validatedPassage
import com.hedworth.seshlog.index.SearchRequestScope
import com.hedworth.seshlog.index.SessionAttention
import com.hedworth.seshlog.index.SessionIndex
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
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
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
        hit: SearchHit? = null,
        sources: SearchSources = hit?.sources ?: SearchSources.DEFAULT,
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
        val controller = Controller(project, session, file, editor, SearchContext(query, sources), startAtLatest, loader, hit)
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
        private val context: SearchContext,
        private val startAtLatest: Boolean,
        private val loader: ((Session) -> List<ConversationEntry>)?,
        private val hit: SearchHit?,

    ) : Disposable {
        private val scope = SearchRequestScope()
        private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Seshlog conversation", 1)
        private var document = MarkdownConversation.placeholder(SessionOrganisation.getInstance().title(session))
        private var latestReplyEnd = -1
        private var targetOffset: Int? = null
        private var changedPassage=false
        private var ranges: List<IntRange> = emptyList()
        private val highlights = IdentityHashMap<Editor, List<RangeHighlighter>>()
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
                    val entries = loader?.invoke(session) ?: SessionIndex.getInstance().providerFor(session).conversationEntries(session)
                    val target = validatedPassage(hit, entries, context.sources)
                    val built = MarkdownConversation.build(SessionOrganisation.getInstance().title(session), entries, session,
                        context.takeIf { it.query.isNotBlank() }, hit?.primary?.source)
                    Triple(entries, target, built)
                }
                ApplicationManager.getApplication().invokeLater {
                    if (cancelled() || project.isDisposed) return@invokeLater
                    result.onSuccess { (entries,target,built) ->
                        changedPassage=hit?.entryId!=null && target==null
                        document = built.text + if(changedPassage) "\n_Source changed; search again to refresh the ranked passage._\n" else ""
                        ranges = built.matches
                        targetOffset=target?.let { (i,range) -> built.starts[i]+range.first }
                        latestReplyEnd = built.latestReplyEnd
                        completionReceipt = if (entries.none { it.truncated || !it.searchable }) receipt else null
                        replaceContent(document)
                        positionEditor()
                    }.onFailure {
                        document = MarkdownConversation.failure(SessionOrganisation.getInstance().title(session))
                        latestReplyEnd = -1
                        ranges = emptyList()
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
            highlightMatches(editor)
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
            navigationEditors.filterNot { it.isDisposed }.forEach(::highlightMatches)
        }

        private fun highlightMatches(editor: Editor) {
            highlights.remove(editor)?.forEach { editor.markupModel.removeHighlighter(it) }
            val attributes = TextAttributes().apply { backgroundColor = JBColor(0xFFF2AA, 0x665A22) }
            highlights[editor] = ranges.filter { it.first >= 0 && it.last < editor.document.textLength }.map {
                editor.markupModel.addRangeHighlighter(it.first, it.last + 1, HighlighterLayer.SELECTION - 1, attributes, HighlighterTargetArea.EXACT_RANGE)
            }
        }

        private fun positionEditor() {
            val editor = activeEditor() ?: return
            if (editor.isDisposed) return
            val offset = when {
                startAtLatest -> editor.document.textLength
                changedPassage -> 0
                targetOffset!=null -> targetOffset!!
                hit?.primary?.source == SearchMatchSource.TITLE || hit?.primary?.source == SearchMatchSource.PROJECT_PATH -> 0
                else -> ranges.firstOrNull()?.first ?: 0
            }
            editor.caretModel.moveToOffset(offset.coerceIn(0, editor.document.textLength))
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }

        fun navigate(forward: Boolean): Boolean {
            val editor = activeEditor() ?: return false
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
            highlights.forEach { (editor, ranges) -> if (!editor.isDisposed) ranges.forEach { editor.markupModel.removeHighlighter(it) } }
            highlights.clear()
            scope.dispose()
            executor.shutdownNow()
        }
    }
}

private data class BuiltMarkdown(val text: String, val latestReplyEnd: Int, val starts: List<Int>, val matches: List<IntRange>)

private object MarkdownConversation {
    fun placeholder(title: String) = "# ${escapeHeading(title)}\n\n_Loading conversation…_\n"
    fun failure(title: String) = "# ${escapeHeading(title)}\n\n_Conversation could not be read._\n"

    fun build(title: String, entries: List<ConversationEntry>, session: Session? = null,
              context: SearchContext? = null, primary: SearchMatchSource? = null): BuiltMarkdown {
        val sourceMatches = if (context == null) emptyList() else
            ConversationDocument("", emptyList(), entries).sourceMatches(context.query, context.sources)
        val out = StringBuilder("# ${escapeHeading(title)}\n\n")
        context?.let {
            out.append("_Search: “").append(escapeHeading(it.query)).append("” · Search in: ")
                .append(it.sources.summary).append(" · ").append(sourceMatches.size).append(" body matches._\n\n")
            when (primary) {
                SearchMatchSource.TITLE -> out.append("_Matched session title._\n\n")
                SearchMatchSource.PROJECT_PATH -> out.append("_Matched project path._\n\n")
                else -> {}
            }
        }
        session?.let {
            if (it.subagentTranscriptPaths.isNotEmpty()) {
                out.append("_Includes ${it.subagentTranscriptPaths.size} Claude subagent transcript")
                if (it.subagentTranscriptPaths.size != 1) out.append('s')
                out.append("._\n\n")
            }
            it.continuationId?.let { successor -> out.append("_Continues in session `$successor`._\n\n") }
        }
        var latestEnd = -1
        val starts=ArrayList<Int>()
        entries.forEach { entry ->
            out.append("## ").append(escapeHeading(entry.label)).append("\n\n")
            val start = out.length
            starts+=start
            out.append(entry.text).append("\n\n")
            if (!entry.isTool && entry.message.role == Role.ASSISTANT && entry.text.isNotBlank()) latestEnd = out.length - 3
            out.append("---\n\n")
            if (start == out.length) out.append('\n')
        }
        return BuiltMarkdown(out.toString(), latestEnd, starts, sourceMatches.map { match ->
            val start = starts[match.entryIndex]
            (start + match.range.first)..(start + match.range.last)
        })
    }

    private fun escapeHeading(value: String): String = value.replace("\n", " ").replace("\r", " ")
}
