package com.hedworth.seshlog.settings

import com.hedworth.seshlog.index.ContentSearchService
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import java.awt.BorderLayout
import javax.swing.*

/** Immediate cache actions are independent of configurable Apply and never run SQL on the EDT. */
internal class SearchCachePanel : JPanel(BorderLayout()) {
    private val service = ContentSearchService.getInstance()
    private val status = JLabel("Reading search index…")
    private val coverage = JLabel()
    private val result = JBTextArea().apply {
        isEditable = false
        isOpaque = false
        columns = 28
        lineWrap = true
        wrapStyleWord = true
        border = null
        minimumSize = java.awt.Dimension(0, 0)
        font = status.font
        accessibleContext.accessibleName = "Search index action result"
    }
    private lateinit var resultRow: Row
    private lateinit var coverageRow: Row
    private val refreshButton = JButton("Refresh")
    private val manageButton = JButton("Manage index…")

    init {
        refreshButton.toolTipText = "Check local sources again on the next search, reusing saved index data"
        refreshButton.addActionListener { action(ContentSearchService.CacheAction.REFRESH) }
        manageButton.addActionListener { showActions() }
        add(panel {
            row { cell(status) }
            coverageRow = row { cell(coverage) }.visible(false)
            row {
                cell(refreshButton)
                cell(manageButton)
            }
            resultRow = row { cell(result).align(AlignX.FILL).resizableColumn() }.visible(false)
            collapsibleGroup("Storage and privacy") {
                row { comment("Search runs locally. The saved index contains words and positions that can reveal conversation content. No raw transcript copies or query history are saved.", maxLineLength = 45) }
                row { comment("The index stays in this IDE profile and is excluded from Settings Sync.", maxLineLength = 45) }
                row("Index file:") {
                    textField().columns(24).applyToComponent {
                        text = service.cachePath.toString()
                        isEditable = false
                        caretPosition = 0
                        toolTipText = text
                    }.align(AlignX.FILL).resizableColumn()
                }
                row { button("Open index folder") { RevealFileAction.openDirectory(service.cachePath.parent) } }
            }
        }, BorderLayout.CENTER)
        refresh()
    }

    private fun showActions() {
        val menu = JPopupMenu()
        fun item(label: String, action: () -> Unit) {
            menu.add(JMenuItem(label).apply { addActionListener { action() } })
        }
        item("Retry opening index") { action(ContentSearchService.CacheAction.RETRY) }
        item("Rebuild index…") {
            if (confirm("Rebuild the search index? Local sources will be indexed on your next search. A recovery copy of the current index will be kept."))
                action(ContentSearchService.CacheAction.REBUILD)
        }
        item("Clear index…") {
            if (confirm("Clear the saved search index and recovery copies? Your agent conversations will not be changed."))
                action(ContentSearchService.CacheAction.CLEAR)
        }
        menu.show(manageButton, 0, manageButton.height)
    }

    private fun confirm(message: String) =
        JOptionPane.showConfirmDialog(
            this,
            message,
            "Seshlog search index",
            JOptionPane.OK_CANCEL_OPTION,
        ) == JOptionPane.OK_OPTION

    private fun action(action: ContentSearchService.CacheAction) {
        working()
        service.maintain(action, ::completed)
    }

    private fun working() {
        refreshButton.isEnabled = false
        manageButton.isEnabled = false
        result.text = "Working…"
        resultRow.visible(true)
    }

    private fun completed(message: String) {
        result.text = message
        refreshButton.isEnabled = true
        manageButton.isEnabled = true
        revalidate()
        refresh()
    }

    private fun refresh() {
        service.status {
            val sources = when (it.sources) {
                0 -> "No sources indexed yet"
                1 -> "1 indexed source"
                else -> "${it.sources} indexed sources"
            }
            status.text = "$sources · ${StringUtil.formatFileSize(it.bytes)} on disk"
            coverage.text = "Last search: ${it.coverage.indexed} of ${it.coverage.eligible} sources indexed"
            coverageRow.visible(it.coverage.eligible > 0)
        }
    }
}
