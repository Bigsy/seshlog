package com.hedworth.seshlog.switcher

import com.hedworth.seshlog.index.ResolvedPaths
import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.AgentFilterState
import com.hedworth.seshlog.settings.SeshlogSettings
import com.hedworth.seshlog.settings.SessionAttentionState
import com.hedworth.seshlog.settings.SessionOrganisation
import com.hedworth.seshlog.terminal.OwnedTerminalTabs
import com.hedworth.seshlog.worktree.WorktreeActionPresentation
import com.hedworth.seshlog.worktree.WorktreeProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.KeyboardFocusManager
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.nio.file.Paths
import java.util.concurrent.Future
import javax.swing.*
import javax.swing.event.DocumentEvent

internal object SessionSwitcher {
    fun show(project: Project) {
        val panel = SessionSwitcherPanel(project)
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel.query)
            .setTitle("Switch Session / Worktree").setResizable(true).setMovable(true)
            .setRequestFocus(true).setCancelOnWindowDeactivation(true).createPopup()
        panel.closePopup = { popup.cancel() }
        Disposer.register(popup, panel)
        Disposer.register(project, popup)
        popup.showCenteredInCurrentWindow(project)
    }
}

/**
 * Popup-local state. Follows the tool window's project scope only: no other filters, query history,
 * transcript reads or attention acknowledgement.
 */
internal class SessionSwitcherPanel(
    private val project: Project,
    private val live: Boolean = true,
    private val focusButton: (JButton) -> Unit = { it.requestFocusInWindow() },
    private val traverseFocus: (JButton, Boolean) -> Unit = { button, forward ->
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        if (forward) manager.focusNextComponent(button) else manager.focusPreviousComponent(button)
    },
) :
    JPanel(BorderLayout(JBUI.scale(6), JBUI.scale(6))), Disposable {
    internal val query = JBTextField().apply { emptyText.text = "Search titles, projects, paths or branches" }
    internal val list = JBList<SwitcherRow>(DefaultListModel<SwitcherRow>())
    internal val resume = JButton("Resume").apply { mnemonic = KeyEvent.VK_R }
    internal val open = JButton("Open Worktree")
    internal val status = JLabel("Loading sessions…").apply { putClientProperty("html.disable", true) }
    internal val results = SwitcherResults()
    var closePopup: () -> Unit = {}
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Seshlog switcher", 1)
    private var future: Future<*>? = null
    private var disposed = false
    private var acting = false
    private var lastRows: List<SwitcherRow>? = null
    private var lastQuery: String? = null
    private var lastScope: SwitcherScope? = null
    private var lastPaths = ResolvedPaths.EMPTY
    private val debounce = Timer(100) { search() }.apply { isRepeats = false }
    private val poll = Timer(1500) { refresh() }
    private var ticket = 0L
    private val navigation = SwitcherNavigation(project, { !disposed },
        { message -> acting = false; updateActions(); status.text = message }, { closePopup() })

    init {
        border = JBUI.Borders.empty(8)
        preferredSize = JBUI.size(760, 420)
        query.accessibleContext.accessibleName = "Search sessions by title, project, path or branch"
        list.accessibleContext.accessibleName = "Sessions across all projects"
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = SwitcherRenderer { query.text }
        add(query, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(status, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                add(open); add(resume)
            }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        list.addListSelectionListener {
            if (!it.valueIsAdjusting) { results.selected = list.selectedValue?.key; updateActions() }
        }
        query.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { refresh(force = true) }
        })
        bind(query, "DOWN") { move(1) }
        bind(query, "UP") { move(-1) }
        bind(query, "ENTER") { enter() }
        bind(list, "ENTER") { enter() }
        bind(resume, "ENTER") { if (resume.isEnabled) resume.doClick() }
        bind(open, "ENTER") { if (open.isEnabled) open.doClick() }
        bindButtonNavigation()
        resume.addActionListener { act { navigation.resume(it.session, it.checkout) } }
        open.addActionListener { act { navigation.openWorktree(it.session) } }
        if (live) {
            val bus = ApplicationManager.getApplication().messageBus.connect(this)
            bus.subscribe(SessionIndex.TOPIC, object : SessionIndex.SessionIndexListener {
                override fun sessionsUpdated(sessions: List<Session>) { refresh() }
            })
            bus.subscribe(SessionOrganisation.TOPIC, object : SessionOrganisation.Listener {
                override fun changed() { refresh() }
            })
            bus.subscribe(SessionAttentionState.TOPIC, object : SessionAttentionState.Listener {
                override fun changed() { refresh() }
            })
            WorktreeProjects.projectSet().forEach { OwnedTerminalTabs.getInstance(it).start() }
            refresh(force = true)
            poll.start()
            SessionIndex.getInstance().refresh()
        } else updateActions()
    }

    private fun capture(): List<SwitcherRow> {
        val index = SessionIndex.getInstance()
        val organisation = SessionOrganisation.getInstance()
        val projects = WorktreeProjects.projectSet()
        val associated = projects.flatMap { OwnedTerminalTabs.getInstance(it).sessionIds }.toSet()
        val names = index.openProjectRoots.filterKeys { it in projects }.entries.groupBy({ it.value }, { it.key.name })
        val unread = SessionAttentionState.getInstance().unreadIds
        return switcherRows(index.sessions, index.resolvedPaths, names, associated, unread,
            organisation::title, { organisation.metadata(it.id).hidden })
    }

    /** Same roots and worktree toggle as the session list; read on the EDT, resolved on the worker. */
    private fun scope(): SwitcherScope? {
        if (SeshlogSettings.getInstance().showAllProjects) return null
        val roots = buildList {
            project.basePath?.let { add(Paths.get(it)) }
            ProjectRootManager.getInstance(project).contentRoots.forEach { add(Paths.get(it.path)) }
        }
        return SwitcherScope(roots, AgentFilterState.getInstance(project).includeWorktrees)
    }

    private fun refresh(force: Boolean = false) {
        if (disposed || !live) return
        val rows = capture()
        val scope = scope()
        if (!force && rows == lastRows && query.text == lastQuery && scope == lastScope) return
        lastRows = rows
        lastQuery = query.text
        lastScope = scope
        lastPaths = SessionIndex.getInstance().resolvedPaths
        list.accessibleContext.accessibleName = if (scope == null) "Sessions across all projects" else "Sessions in this project"
        ticket = results.begin(query.text)
        future?.cancel(true)
        debounce.restart()
        updateActions()
    }

    private fun search() {
        if (disposed) return
        val captured = lastRows ?: return
        val text = lastQuery.orEmpty()
        val scope = lastScope
        val paths = lastPaths
        val request = ticket
        future = executor.submit {
            val matches = SwitcherSearch.find(scopedRows(captured, scope, paths), text)
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && results.apply(request, matches)) render()
            }
        }
    }

    /** Apply immutable results; preserve selection by provider/id and the viewport on refresh. */
    internal fun render() {
        val selected = results.selected
        val scroll = (list.parent as? JViewport)?.viewPosition
        val model = list.model as DefaultListModel<SwitcherRow>
        list.valueIsAdjusting = true
        model.clear()
        model.addAll(results.rows)
        list.selectedIndex = results.rows.indexOfFirst { it.key == selected }
        list.valueIsAdjusting = false
        if (scroll != null) (list.parent as? JViewport)?.viewPosition = scroll
        updateActions()
    }

    private fun move(delta: Int) {
        if (list.model.size == 0) return
        list.selectedIndex = (if (list.selectedIndex < 0) 0 else list.selectedIndex + delta).coerceIn(0, list.model.size - 1)
        list.ensureIndexIsVisible(list.selectedIndex)
    }

    internal fun enter() {
        val row = list.selectedValue ?: return
        if (results.pending || acting) return
        if (row.associated) act { navigation.focus(it.session, it.checkout) }
        else if (resume.isEnabled) {
            status.text = "Choose Resume to start this session"
            focusButton(resume)
        } else status.text = row.status
    }

    private fun act(effect: (SwitcherRow) -> Unit) {
        val row = list.selectedValue ?: return
        if (results.pending || acting) return
        acting = true
        updateActions()
        effect(row)
    }

    private fun updateActions() {
        val row = list.selectedValue
        val ready = !results.pending && !acting && row != null
        resume.isEnabled = ready && row.canResume
        open.isEnabled = ready && row.session.checkout?.linked == true && row.locationProblem == null
        if (live && open.isEnabled) {
            val index = SessionIndex.getInstance()
            open.isEnabled = WorktreeActionPresentation.of(row!!.session,
                index.openProjectRoots.filterKeys { !it.isDisposed }.values, index.protectedDataRoots).enabled
        }
        status.text = when {
            acting -> "Validating session…"
            results.pending -> "Searching…"
            query.text.length > SwitcherSearch.MAX_QUERY -> "Query is too long (maximum ${SwitcherSearch.MAX_QUERY})"
            row != null -> row.status
            results.rows.isEmpty() -> if (lastScope != null) "No matching sessions in this project (All Projects is off)"
                else "No matching visible sessions"
            else -> "Select a session · showing up to ${SwitcherSearch.LIMIT}"
        }
    }

    override fun dispose() {
        disposed = true
        results.close()
        debounce.stop(); poll.stop()
        future?.cancel(true)
        executor.shutdownNow()
    }

    private fun bindButtonNavigation() {
        // Some macOS focus policies skip buttons during Tab traversal. Route between these two
        // explicitly, then hand traversal back to the platform at the ends.
        for (button in listOf(open, resume)) {
            val forward = KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS
            val backward = KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS
            button.setFocusTraversalKeys(forward, button.getFocusTraversalKeys(forward)
                .filterNot { it == KeyStroke.getKeyStroke("TAB") }.toSet())
            button.setFocusTraversalKeys(backward, button.getFocusTraversalKeys(backward)
                .filterNot { it == KeyStroke.getKeyStroke("shift TAB") }.toSet())
        }
        bind(resume, "LEFT") { if (open.isEnabled) focusButton(open) }
        bind(open, "RIGHT") { if (resume.isEnabled) focusButton(resume) }
        bind(resume, "shift TAB") {
            if (open.isEnabled) focusButton(open) else traverseFocus(resume, false)
        }
        bind(open, "TAB") {
            if (resume.isEnabled) focusButton(resume) else traverseFocus(open, true)
        }
        bind(open, "shift TAB") { traverseFocus(open, false) }
        bind(resume, "TAB") { traverseFocus(resume, true) }
    }

    private fun bind(component: JComponent, key: String, action: () -> Unit) {
        component.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), "seshlog.$key")
        component.actionMap.put("seshlog.$key", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = action()
        })
    }
}
