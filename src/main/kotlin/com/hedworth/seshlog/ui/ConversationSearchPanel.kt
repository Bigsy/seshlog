package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.ConversationEntry
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import javax.swing.*
import javax.swing.text.DefaultHighlighter

/** In-memory conversation navigation shared by all provider search results. */
internal class ConversationSearchPanel : JPanel(BorderLayout()) {
    val editor = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    val status = JLabel()
    val previous = JButton("Previous match")
    val next = JButton("Next match")
    private var document = ConversationDocument("", emptyList())
    private var matches = emptyList<EntryMatch>()
    private var query = ""
    private var current = -1

    init {
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(previous); add(next); add(status)
        }, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(editor), BorderLayout.CENTER)
        previous.addActionListener { navigate(-1) }
        next.addActionListener { navigate(1) }
        for ((key, delta) in listOf("F3" to 1, "shift F3" to -1)) {
            getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key), key)
            actionMap.put(key, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) { navigate(delta) }
            })
        }
        previous.toolTipText = "Previous occurrence (Shift+F3)"
        next.toolTipText = "Next occurrence (F3)"
        clear("Select a search result.")
    }

    fun clear(message: String) {
        document = ConversationDocument("", emptyList())
        matches = emptyList()
        current = -1
        editor.text = ""
        editor.highlighter.removeAllHighlights()
        status.text = message
        previous.isEnabled = false
        next.isEnabled = false
    }

    fun showEntries(entries: List<ConversationEntry>, query: String) {
        editor.highlighter.removeAllHighlights()
        this.query = query
        document = ConversationDocument.buildEntries(entries)
        matches = document.sourceMatches(query)
        current = -1
        editor.text = document.text
        editor.caretPosition = 0
        previous.isEnabled = matches.isNotEmpty()
        next.isEnabled = matches.isNotEmpty()
        if (matches.isNotEmpty()) navigate(1)
        else status.text = (if (entries.isEmpty()) "No conversation yet."
            else "No content matches; title or path matched.") + coverage()
    }

    fun navigate(delta: Int) {
        if (matches.isEmpty()) return
        current = if (current < 0 && delta < 0) matches.lastIndex else Math.floorMod(current + delta, matches.size)
        val (revealed, range) = document.reveal(matches[current])
        document = revealed
        if (editor.text != document.text) editor.text = document.text
        editor.highlighter.removeAllHighlights()
        for (match in document.matches(query)) {
            editor.highlighter.addHighlight(match.first, match.last + 1,
                DefaultHighlighter.DefaultHighlightPainter(JBColor(0xFFF2AA, 0x665A22)))
        }
        editor.highlighter.addHighlight(range.first, range.last + 1,
            DefaultHighlighter.DefaultHighlightPainter(JBColor(0xFFBE62, 0x996020)))
        editor.caretPosition = range.first
        editor.modelToView2D(range.first)?.bounds?.let { editor.scrollRectToVisible(it) }
        status.text = "Match ${current + 1} of ${matches.size}" + coverage()
    }

    private fun coverage() = if (document.entries.any { it.truncated || !it.searchable })
        " · Partial content/search coverage" else ""
}
