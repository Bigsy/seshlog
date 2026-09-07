package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.SearchRequestScope
import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.SessionOrganisation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.text.DefaultHighlighter

/** Full conversation stays in memory; loading never blocks the EDT. */
class ConversationDialog(private val project: Project, private val session: Session, query: String) : DialogWrapper(project, false) {
    private val scope = SearchRequestScope()
    private val editor = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val search = JBTextField(query, 24)
    private val status = JLabel("Loading conversation…")
    private val previous = JButton("Previous match")
    private val next = JButton("Next match")
    private var document = ConversationDocument("", emptyList())
    private var matches = emptyList<EntryMatch>()
    private val toggleTool = JButton("Expand/collapse tool")
    private var loading = true
    private var current = -1

    init {
        title = SessionOrganisation.getInstance().title(session)
        isModal = false
        init()
        previous.addActionListener { navigate(-1) }
        next.addActionListener { navigate(1) }
        toggleTool.addActionListener {
            val index = document.entryAt(editor.caretPosition) ?: return@addActionListener
            val entry = document.entries[index]
            if (entry.isTool) {
                val expanded = if (entry.sourceId in document.expanded) document.expanded - entry.sourceId else document.expanded + entry.sourceId
                document = ConversationDocument.buildEntries(document.entries, expanded)
                editor.text = document.text
                editor.caretPosition = document.entryRanges[index].first
            }
        }
        editor.addCaretListener { updateActions() }
        updateActions()
        search.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { findMatches() }
        })
        load()
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        preferredSize = Dimension(850, 650)
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            toolTipText = com.hedworth.seshlog.index.TextQuery.HINT
            add(JLabel("Find")); add(search); add(previous); add(next); add(toggleTool)
            add(JButton("Retry").apply { addActionListener { load() } })
        }, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(editor), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
    }

    private fun load() {
        status.text = "Loading conversation…"
        loading = true
        updateActions()
        val cancelled = scope.begin()
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val result = runCatching { ConversationDocument.buildEntries(SessionIndex.getInstance().providerFor(session).conversationEntries(session)) }
            app.invokeLater {
                if (cancelled() || project.isDisposed) return@invokeLater
                loading = false
                result.fold(onSuccess = {
                    document = it
                    editor.text = it.text
                    editor.caretPosition = 0
                    findMatches()
                }, onFailure = {
                    document = ConversationDocument("", emptyList())
                    editor.text = ""
                    matches = emptyList()
                    status.text = "Conversation could not be read. Use Retry."
                })
                updateActions()
            }
        }
    }

    private fun findMatches() {
        editor.highlighter.removeAllHighlights()
        if (loading) return
        matches = document.sourceMatches(search.text.trim())
        current = -1
        updateActions()
        status.text = when {
            document.text.isEmpty() -> "No conversation yet."
            search.text.isBlank() -> "${document.entries.count { it.searchable }} entries"
            matches.isEmpty() -> "No content matches. This result may match only the title or project path."
            else -> "${matches.size} matches"
        }
        if (matches.isNotEmpty()) navigate(1)
        else status.text += coverageStatus()
    }

    private fun navigate(delta: Int) {
        if (matches.isEmpty()) return
        current = Math.floorMod(current + delta, matches.size)
        val (revealed, range) = document.reveal(matches[current])
        if (revealed !== document) {
            document = revealed
            editor.text = document.text
        }
        editor.highlighter.removeAllHighlights()
        editor.highlighter.addHighlight(range.first, range.last + 1,
            DefaultHighlighter.DefaultHighlightPainter(com.intellij.ui.JBColor.YELLOW))
        editor.caretPosition = range.first
        status.text = "Match ${current + 1} of ${matches.size}" + coverageStatus()
    }

    private fun coverageStatus(): String =
        if (document.entries.any { it.truncated || !it.searchable }) " · Partial content/search coverage (see notice)" else ""

    private fun updateActions() {
        previous.isEnabled = !loading && matches.isNotEmpty()
        next.isEnabled = previous.isEnabled
        toggleTool.isEnabled = !loading && document.entryAt(editor.caretPosition)?.let { document.entries[it].isTool } == true
    }

    override fun dispose() { scope.dispose(); super.dispose() }
}
