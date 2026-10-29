package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.SessionAttentionState
import com.hedworth.seshlog.settings.SessionOrganisation
import com.hedworth.seshlog.switcher.SwitcherNavigation
import com.hedworth.seshlog.terminal.OwnedTerminalTabs
import com.hedworth.seshlog.worktree.WorktreeActionPresentation
import com.hedworth.seshlog.worktree.WorktreeProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.swing.*

internal class AttentionInboxDialog(private val project: Project) : DialogWrapper(project, false) {
    private val index get() = SessionIndex.getInstance()
    private val attention get() = SessionAttentionState.getInstance()
    private val organisation get() = SessionOrganisation.getInstance()
    private val model = DefaultListModel<Session>()
    private val list = JBList(model)
    private val preview = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val status = JLabel(" ").apply { putClientProperty("html.disable", true) }
    private val read = JButton("Mark Read")
    private val conversation = JButton("Open Conversation")
    private val terminal = JButton("Show Terminal")
    private val worktree = JButton("Open Worktree")
    private val previews = InboxPreviewState()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "seshlog-attention-preview").apply { isDaemon = true } }
    private var job: Future<*>? = null
    private var closed = false
    private var refreshing = false
    private var shown: Session? = null
    private val poll = Timer(1500) { refresh() }
    private val navigation = SwitcherNavigation(project, { !closed }, { status.text = it }, {})

    init {
        title = "Seshlog Attention Inbox"
        setModal(false)
        setOKButtonText("Close")
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.accessibleContext.accessibleName = "Unread replies across all projects"
        preview.accessibleContext.accessibleName = "Latest assistant reply preview"
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(l: JList<*>?, value: Any?, row: Int, selected: Boolean, focus: Boolean): java.awt.Component {
                val s = value as Session
                return super.getListCellRendererComponent(l,
                    "${s.kind} · ${organisation.title(s)} · ${s.checkout?.branchLabel ?: s.displayBranch.orEmpty()} · ${s.cwd}", row, selected, focus)
            }
        }
        list.addListSelectionListener { if (!refreshing && !it.valueIsAdjusting) loadPreview() }
        read.addActionListener {
            previews.delivered?.let { attention.viewed(it.session.id, it.receipt) }
            refresh()
        }
        conversation.addActionListener { selected()?.let { ConversationEditorTabs.open(project, it) } }
        terminal.addActionListener { selected()?.let(navigation::focus) }
        worktree.addActionListener { selected()?.let(navigation::openWorktree) }
        init()
        refresh()
        poll.start()
    }

    private fun selected(): Session? = list.selectedValue?.let { selected ->
        index.sessionById(selected.id)?.takeIf { it.kind == selected.kind && !organisation.metadata(it.id).hidden }
    }

    private fun refresh() {
        if (closed || project.isDisposed) return
        val sessions = index.sessions
        val rows = inboxSessions(sessions, attention.unreadIds, sessions.filter { organisation.metadata(it.id).hidden }.mapTo(HashSet()) { it.id })
        val previous = list.selectedValue
        refreshing = true
        model.clear()
        rows.take(200).forEach(model::addElement)
        val selection = (0 until model.size).firstOrNull { model[it].id == previous?.id && model[it].kind == previous.kind }
        if (model.size > 0) list.selectedIndex = selection ?: 0
        refreshing = false
        status.text = if (rows.isEmpty()) "No unread replies. Hidden sessions are excluded." else
            "${rows.size} unread across all projects${if (rows.size > 200) " · showing newest 200" else ""}"
        val current = selected()
        if (current != shown) loadPreview() else updateActions()
    }

    private fun updateActions() {
        val selected = selected()
        read.isEnabled = previews.delivered?.let { it.session.id == selected?.id && it.receipt != null } == true
        conversation.isEnabled = selected != null
        terminal.isEnabled = selected != null && WorktreeProjects.projectSet().count { OwnedTerminalTabs.getInstance(it).owns(selected.id) } == 1
        worktree.isEnabled = selected != null && WorktreeActionPresentation.of(selected, index.openProjectRoots.values, index.protectedDataRoots).enabled
    }

    private fun loadPreview() {
        job?.cancel(true)
        previews.clear()
        val selected = selected()
        shown = selected
        preview.text = if (selected == null) "Select an unread session." else "Loading reply…"
        updateActions()
        if (selected == null) return
        val request = previews.begin(selected, attention.receipt(selected))
        job = worker.submit {
            val text = runCatching { InboxPreviewState.text(index.providerFor(selected).lastMessages(selected, 8)) }
            ApplicationManager.getApplication().invokeLater({
                if (!closed && !project.isDisposed && selected() == shown &&
                    previews.accept(request, selected())) {
                    preview.text = text.getOrNull() ?: text.exceptionOrNull()?.let { "Reply unavailable: ${it.message}" }
                        ?: "No assistant reply was available in the recent messages. Open the conversation to inspect it."
                    if (text.getOrNull() == null) previews.clear()
                    preview.caretPosition = 0
                    updateActions()
                }
            }, ModalityState.any())
        }
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(8, 8)).apply {
        preferredSize = Dimension(950, 560)
        add(JLabel("Unread replies across projects · previews do not mark replies read automatically"), BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(list), JBScrollPane(preview)).apply { resizeWeight = 0.45 }, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(status, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(worktree); add(terminal); add(conversation); add(read) }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
    }
    override fun createActions(): Array<Action> = arrayOf(okAction)
    override fun dispose() { closed = true; poll.stop(); previews.clear(); job?.cancel(true); worker.shutdownNow(); super.dispose() }
}
