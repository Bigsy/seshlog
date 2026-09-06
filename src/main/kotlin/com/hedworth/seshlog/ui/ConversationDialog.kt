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
    private var matches = emptyList<IntRange>()
    private var current = -1

    init {
        title = SessionOrganisation.getInstance().title(session)
        isModal = false
        init()
        previous.addActionListener { navigate(-1) }
        next.addActionListener { navigate(1) }
        search.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { findMatches() }
        })
        load()
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        preferredSize = Dimension(850, 650)
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Find")); add(search); add(previous); add(next)
            add(JButton("Retry").apply { addActionListener { load() } })
        }, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(editor), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
    }

    private fun load() {
        status.text = "Loading conversation…"
        val cancelled = scope.begin()
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val result = runCatching { ConversationDocument.build(SessionIndex.getInstance().providerFor(session).conversationMessages(session)) }
            app.invokeLater {
                if (cancelled() || project.isDisposed) return@invokeLater
                result.fold(onSuccess = {
                    document = it
                    editor.text = it.text
                    editor.caretPosition = 0
                    findMatches()
                }, onFailure = { status.text = "Conversation could not be read. Use Retry." })
            }
        }
    }

    private fun findMatches() {
        editor.highlighter.removeAllHighlights()
        matches = document.matches(search.text.trim())
        current = -1
        previous.isEnabled = matches.isNotEmpty()
        next.isEnabled = matches.isNotEmpty()
        status.text = when {
            document.text.isEmpty() -> "No conversation yet."
            search.text.isBlank() -> "${document.messageRanges.size} messages"
            matches.isEmpty() -> "No content matches (the session title may match)."
            else -> "${matches.size} matches"
        }
        if (matches.isNotEmpty()) navigate(1)
    }

    private fun navigate(delta: Int) {
        if (matches.isEmpty()) return
        current = Math.floorMod(current + delta, matches.size)
        val range = matches[current]
        editor.highlighter.removeAllHighlights()
        editor.highlighter.addHighlight(range.first, range.last + 1,
            DefaultHighlighter.DefaultHighlightPainter(com.intellij.ui.JBColor.YELLOW))
        editor.caretPosition = range.first
        status.text = "Match ${current + 1} of ${matches.size}"
    }

    override fun dispose() { scope.dispose(); super.dispose() }
}
