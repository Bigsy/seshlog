package com.hedworth.seshlog.ui

import com.hedworth.seshlog.model.ConversationEntry
import com.hedworth.seshlog.index.SearchSources
import com.hedworth.seshlog.index.SearchMatchSource
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
    private var sources = SearchSources.DEFAULT
    private var current = -1
    private var anchor: EntryMatch? = null
    val isLatestReplyVisible: Boolean get() = CompletionViewObserver.isLatestReplyVisible(document, editor)

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
        anchor = null
        editor.text = ""
        editor.highlighter.removeAllHighlights()
        status.text = message
        previous.isEnabled = false
        next.isEnabled = false
    }

    fun showEntries(entries: List<ConversationEntry>, query: String, target: Pair<Int,IntRange>? = null, changed: Boolean = false,
                    sources: SearchSources = SearchSources.DEFAULT, primary: SearchMatchSource? = null) {
        editor.highlighter.removeAllHighlights()
        this.query = query
        this.sources = sources
        status.toolTipText = sources.description
        document = ConversationDocument.buildEntries(entries)
        matches = document.sourceMatches(query, sources)
        current = -1
        anchor = null
        editor.text = document.text
        editor.caretPosition = 0
        previous.isEnabled = matches.isNotEmpty()
        next.isEnabled = matches.isNotEmpty()
        if (changed) { status.text="Source changed; search again to refresh the passage." + coverage(); return }
        if (target!=null) {
            val targetMatch = EntryMatch(target.first, target.second)
            val (revealed, range)=document.reveal(targetMatch)
            document=revealed;editor.text=document.text;editor.caretPosition=range.first
            editor.highlighter.addHighlight(range.first,range.last+1,DefaultHighlighter.DefaultHighlightPainter(JBColor(0xFFBE62,0x996020)))
            current=matches.indexOfFirst { it.entryIndex==target.first && it.range==target.second }
            if (current < 0) current = matches.indexOfFirst {
                it.entryIndex == target.first && it.range.first in target.second
            }
            if (current < 0) current = matches.indexOfFirst {
                it.entryIndex == target.first && target.second.first in it.range
            }
            if (current < 0) anchor = targetMatch
            status.text="${primary?.label ?: "Ranked passage"} · ${matches.size} matches"+coverage()
        } else if (primary == SearchMatchSource.TITLE || primary == SearchMatchSource.PROJECT_PATH) {
            status.text = (if (primary == SearchMatchSource.TITLE) "Matched session title" else "Matched project path") + " · ${matches.size} body matches" + coverage()
        } else if (matches.isNotEmpty()) navigate(1)
        else status.text = (if (entries.isEmpty()) "No conversation yet."
            else "No content matches; title or path matched.") + coverage()
    }

    fun navigate(delta: Int) {
        if (matches.isEmpty()) return
        val target = anchor
        current = if (target != null) {
            // A ranked normalized token may have no literal navigation match. Find the next or
            // previous occurrence relative to its original entry and offset before wrapping.
            if (delta > 0) matches.indexOfFirst {
                it.entryIndex > target.entryIndex ||
                    it.entryIndex == target.entryIndex && it.range.first > target.range.first
            }.takeIf { it >= 0 } ?: 0
            else matches.indexOfLast {
                it.entryIndex < target.entryIndex ||
                    it.entryIndex == target.entryIndex && it.range.first < target.range.first
            }.takeIf { it >= 0 } ?: matches.lastIndex
        } else if (current < 0 && delta < 0) matches.lastIndex else Math.floorMod(current + delta, matches.size)
        anchor = null
        val (revealed, range) = document.reveal(matches[current])
        document = revealed
        if (editor.text != document.text) editor.text = document.text
        editor.highlighter.removeAllHighlights()
        for (match in document.matches(query, sources)) {
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
