package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.ContentSearchService
import com.hedworth.seshlog.index.SearchRequestScope
import com.hedworth.seshlog.index.SearchHit
import com.hedworth.seshlog.index.ResolvedPaths
import com.hedworth.seshlog.index.SessionFilter
import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.settings.SeshlogSettings
import com.hedworth.seshlog.settings.SeshlogSettingsListener
import com.hedworth.seshlog.settings.AgentFilterMode
import com.hedworth.seshlog.settings.AgentFilterState
import com.hedworth.seshlog.settings.AgentSessionFilter
import com.hedworth.seshlog.ui.actions.ResumeSessionAction
import com.hedworth.seshlog.ui.actions.SessionAction
import com.hedworth.seshlog.terminal.OwnedTerminalTabs
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBPanel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.ui.StatusText
import com.intellij.util.ui.tree.TreeUtil
import com.intellij.icons.AllIcons
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.KeyStroke
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

class SessionTreePanel(private val project: Project, parentDisposable: Disposable) :
    JBPanel<SessionTreePanel>(BorderLayout()), DataProvider, Disposable {

    @Volatile private var resolvedPaths = ResolvedPaths.EMPTY
    private var requestedPaths: Set<Path> = emptySet()
    private val pathScope = SearchRequestScope()
    private var latestSessions: List<Session> = emptyList()

    private val organisation get() = com.hedworth.seshlog.settings.SessionOrganisation.getInstance()
    private var showHidden = false
    internal var dateFilter = com.hedworth.seshlog.index.SessionDateFilter()
        private set
    private val dateButton = javax.swing.JButton("All time")
    private val clearDate = javax.swing.JButton("Clear date").apply { isVisible = false }

    internal fun setDateFilter(filter: com.hedworth.seshlog.index.SessionDateFilter) {
        dateFilter = filter
        dateButton.text = filter.label
        clearDate.isVisible = filter.period != com.hedworth.seshlog.index.DatePeriod.ALL
        rerender()
    }

    private fun chooseDateFilter() {
        val menu = javax.swing.JPopupMenu()
        com.hedworth.seshlog.index.DatePeriod.values().forEach { period ->
            menu.add(javax.swing.JMenuItem(period.label).apply {
                addActionListener {
                    if (period != com.hedworth.seshlog.index.DatePeriod.CUSTOM) {
                        setDateFilter(com.hedworth.seshlog.index.SessionDateFilter(period))
                    } else {
                        val start = javax.swing.JTextField(dateFilter.start?.toString() ?: java.time.LocalDate.now().toString(), 10)
                        val end = javax.swing.JTextField(dateFilter.end?.toString() ?: java.time.LocalDate.now().toString(), 10)
                        val form = JPanel(java.awt.GridLayout(0, 2)).apply {
                            add(javax.swing.JLabel("From (YYYY-MM-DD)")); add(start)
                            add(javax.swing.JLabel("Through (YYYY-MM-DD)")); add(end)
                        }
                        while (javax.swing.JOptionPane.showConfirmDialog(this@SessionTreePanel, form,
                                "Custom date range", javax.swing.JOptionPane.OK_CANCEL_OPTION) == javax.swing.JOptionPane.OK_OPTION) {
                            val filter = runCatching { com.hedworth.seshlog.index.SessionDateFilter(period,
                                java.time.LocalDate.parse(start.text.trim()), java.time.LocalDate.parse(end.text.trim())) }.getOrNull()
                            if (filter != null) { setDateFilter(filter); break }
                            javax.swing.JOptionPane.showMessageDialog(this@SessionTreePanel,
                                "Enter valid dates with the start on or before the end.", "Invalid range", javax.swing.JOptionPane.ERROR_MESSAGE)
                        }
                    }
                }
            })
        }
        menu.show(dateButton, 0, dateButton.height)
    }

    private val settings get() = SeshlogSettings.getInstance()
    private val index get() = SessionIndex.getInstance()
    private val agentFilter get() = AgentFilterState.getInstance(project)

    internal val agentMenuButton = javax.swing.JButton("Agents ▾")

    private val statusLabel = javax.swing.JLabel()
    private val retryButton = javax.swing.JButton("Retry")
    private var searching = false

    private val collapsedGroups = mutableSetOf<Path>()
    private var rebuildingTree = false
    private var rememberedSelection: String? = null

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode())
    val tree: Tree = Tree(treeModel)
    private val renderer = SessionCellRenderer()

    /** Bottom pane showing the selected session's last messages. */
    val preview = SessionPreviewPanel(this)
    private val splitter = OnePixelSplitter(true, "Seshlog.PreviewSplitter", 0.6f)

    /** Content search: the field, its debounce alarm, and the last delivered hits (by session id). */
    val searchField = SearchTextField(true)
    private val searchScope = SearchRequestScope()
    private val searchAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /** Query currently applied to the list; empty when not searching. */
    var activeQuery: String = ""
        private set

    /** Sessions currently shown (after filtering and, when searching, ranked by hits), for tests and actions. */
    var visibleSessions: List<Session> = emptyList()
        private set

    init {
        Disposer.register(parentDisposable, this)

        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = renderer
        renderer.activeSessionId = OwnedTerminalTabs.getInstance(project).activeSessionId
        project.messageBus.connect(this).subscribe(OwnedTerminalTabs.ACTIVE_SESSION_TOPIC,
            object : OwnedTerminalTabs.ActiveSessionListener {
                override fun activeSessionChanged(sessionId: String?) {
                    val previous = renderer.activeSessionId
                    renderer.activeSessionId = sessionId
                    // Invalidate row widths as well as paint: the label changes preferred size.
                    val root = treeModel.root as DefaultMutableTreeNode
                    for (node in root.depthFirstEnumeration()) {
                        val session = (node as DefaultMutableTreeNode).userObject as? Session ?: continue
                        if (session.id == previous || session.id == sessionId) treeModel.nodeChanged(node)
                    }
                }
            })
        TreeSpeedSearch.installOn(tree, true) { path ->
            when (val obj = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
                is Session -> organisation.title(obj) + " " + obj.title + " " + (obj.displayBranch ?: "")
                is ProjectGroup -> obj.displayName
                else -> ""
            }
        }
        TreeUtil.installActions(tree)

        val resume = ResumeSessionAction()
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (selectedSession() == null) return false
                invoke(resume)
                return true
            }
        }.installOn(tree)
        tree.registerKeyboardAction(
            { if (selectedSession() != null) invoke(resume) },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JComponent.WHEN_FOCUSED,
        )

        val contextMenu = ActionManager.getInstance().getAction("Seshlog.ContextMenu") as DefaultActionGroup
        PopupHandler.installPopupMenu(tree, contextMenu, "SeshlogPopup")

        searchField.textEditor.emptyText.text = "Search titles, paths and content"
        searchField.toolTipText = com.hedworth.seshlog.index.TextQuery.HINT + " " + com.hedworth.seshlog.model.ConversationLimits.NOTICE
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = scheduleSearch()
        })

        agentMenuButton.addActionListener {
            createAgentMenu().show(agentMenuButton, 0, agentMenuButton.height)
        }
        dateButton.addActionListener { chooseDateFilter() }
        clearDate.addActionListener { setDateFilter(com.hedworth.seshlog.index.SessionDateFilter()) }
        val header = JPanel(BorderLayout()).apply {
            add(createToolbar().component, BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)).apply {
                add(agentMenuButton); add(dateButton); add(clearDate)
                add(javax.swing.JLabel(com.hedworth.seshlog.index.TextQuery.HINT))
                add(javax.swing.JLabel("Limited coverage").apply { toolTipText = com.hedworth.seshlog.model.ConversationLimits.NOTICE })
            }, BorderLayout.SOUTH)
        }
        add(header, BorderLayout.NORTH)
        splitter.firstComponent = ScrollPaneFactory.createScrollPane(tree)
        splitter.secondComponent = preview
        add(splitter, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(statusLabel, BorderLayout.CENTER)
            add(retryButton, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        retryButton.addActionListener { index.refresh(); if (activeQuery.isNotEmpty()) runSearch() }
        updateLoadingState()
        applyPreviewVisibility()

        tree.addTreeSelectionListener {
            selectedSession()?.let { rememberedSelection = it.id }
            preview.showSession(selectedSession())
        }
        tree.addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
            override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent) { rememberExpansion(event, false) }
            override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent) { rememberExpansion(event, true) }
            private fun rememberExpansion(event: javax.swing.event.TreeExpansionEvent, collapsed: Boolean) {
                if (rebuildingTree) return
                val group = (event.path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ProjectGroup ?: return
                if (collapsed) collapsedGroups.add(group.cwd) else collapsedGroups.remove(group.cwd)
            }
        })

        project.messageBus.connect(this).subscribe(SessionIndex.TOPIC, object : SessionIndex.SessionIndexListener {
            override fun scanStateChanged(scanning: Boolean) { updateLoadingState() }
            override fun sessionsUpdated(sessions: List<Session>) {
                updateLoadingState()
                requestedPaths = emptySet() // Refresh symlinks and missing ancestors on every scan.
                preparePaths(sessions)
                // A rescan while searching: re-run the query so new/changed transcripts are included.
                if (activeQuery.isNotEmpty()) runSearch() else render(sessions)
            }
        })

        ApplicationManager.getApplication().messageBus.connect(this).subscribe(SeshlogSettingsListener.TOPIC, object : SeshlogSettingsListener {
            override fun settingsChanged() {
                this@SessionTreePanel.settingsChanged()
                rerender()
            }
        })

        project.messageBus.connect(this).subscribe(
            com.intellij.ProjectTopics.PROJECT_ROOTS,
            object : com.intellij.openapi.roots.ModuleRootListener {
                override fun rootsChanged(event: com.intellij.openapi.roots.ModuleRootEvent) {
                    projectRootsChanged()
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            com.hedworth.seshlog.settings.SessionOrganisation.TOPIC,
            object : com.hedworth.seshlog.settings.SessionOrganisation.Listener {
                override fun changed() { rerender() }
            },
        )
        render(index.sessions)
        index.refresh()
    }

    /**
     * Run [action] on the current selection. Deliberately a direct call instead of going through
     * ActionManager: ActionUtil.invokeAction is deprecated, its replacement (ActionUtil.performAction)
     * only exists from 2025.2, and ActionManager.tryToExecute defers via
     * IdeFocusManager.doWhenFocusSettlesDown — which would make double-click and Enter asynchronous.
     * The context-menu path still goes through the action system, so listeners and stats keep working.
     */
    internal fun invoke(action: SessionAction) {
        val session = selectedSession() ?: return
        action.perform(project, session)
    }

    private fun createToolbar(): ActionToolbar {
        val group = DefaultActionGroup().apply {
            add(ActionManager.getInstance().getAction("Seshlog.Refresh"))
            add(ToggleAllProjectsAction())
            add(object : ToggleAction("Sibling Worktrees", "Include worktrees sharing this repository", AllIcons.Vcs.Branch) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = agentFilter.includeWorktrees
                override fun setSelected(e: AnActionEvent, state: Boolean) { agentFilter.includeWorktrees = state; rerender() }
            })
            add(TogglePreviewAction())
            add(ActionManager.getInstance().getAction("Seshlog.OpenConversation"))
            add(object : ToggleAction("Show Hidden", "Include locally hidden sessions", AllIcons.Actions.Show) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent) = showHidden
                override fun setSelected(e: AnActionEvent, state: Boolean) { showHidden = state; rerender() }
            })
            addSeparator()
            add(object : DumbAwareAction("Settings", "Open Seshlog settings", AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) =
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, "Seshlog")
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("SeshlogToolbar", group, true)
        toolbar.targetComponent = this
        return toolbar
    }

    private inner class ToggleAllProjectsAction :
        ToggleAction("All Projects", "Show sessions from every project, not just this one", AllIcons.Actions.GroupByModule) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun isSelected(e: AnActionEvent) = settings.showAllProjects
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            settings.showAllProjects = state
            rerender()
        }
    }

    private inner class TogglePreviewAction :
        ToggleAction("Preview", "Show the selected session's last messages", AllIcons.Actions.PreviewDetails) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun isSelected(e: AnActionEvent) = settings.showPreview
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            settings.showPreview = state
            applyPreviewVisibility()
        }
    }

    internal fun createAgentMenu(): javax.swing.JPopupMenu = javax.swing.JPopupMenu().apply {
        val scoped = unfilteredSessions(latestSessions)
        val available = scoped.map { it.kind }.toSet()
        val selected = AgentSessionFilter.effectiveKinds(scoped, agentFilter.mode)
        add(javax.swing.JCheckBoxMenuItem("All agents", agentFilter.mode == AgentFilterMode.All).apply {
            addActionListener {
                agentFilter.mode = AgentFilterMode.All
                rerender()
            }
        })
        add(javax.swing.JCheckBoxMenuItem("Auto", agentFilter.mode == AgentFilterMode.Auto).apply {
            toolTipText = "Automatically show the agent with the most sessions"
            addActionListener {
                agentFilter.mode = AgentFilterMode.Auto
                rerender()
            }
        })
        if (available.isNotEmpty()) addSeparator()
        AgentKind.entries.filter { it in available }.forEach { kind ->
            add(javax.swing.JCheckBoxMenuItem(kind.displayName, kind in selected).apply {
                addActionListener {
                    val current = AgentSessionFilter.effectiveKinds(unfilteredSessions(latestSessions), agentFilter.mode)
                    agentFilter.mode = AgentFilterMode.Selected(
                        if (isSelected) current + kind else current - kind,
                    )
                    rerender()
                }
            })
        }
    }

    private fun updateAgentMenu(all: List<Session>) {
        val scoped = unfilteredSessions(all)
        val selected = AgentSessionFilter.effectiveKinds(scoped, agentFilter.mode)
        agentMenuButton.text = when (agentFilter.mode) {
            AgentFilterMode.All -> "Agents: All ▾"
            AgentFilterMode.Auto -> "Agents: Auto ▾"
            else -> "Agents: ${selected.size} ▾"
        }
        agentMenuButton.toolTipText = if (selected.isEmpty()) "No agents selected" else
            "Selected agents: " + AgentKind.entries.filter { it in selected }.joinToString { it.displayName }
    }

    /** Settings may have changed (configurable Apply): re-read preview visibility and count. */
    fun settingsChanged() {
        applyPreviewVisibility()
        preview.showSession(selectedSession())
    }

    private fun applyPreviewVisibility() {
        val show = settings.showPreview
        preview.isVisible = show
        splitter.secondComponent = if (show) preview else null
        if (show) preview.showSession(selectedSession())
        splitter.revalidate()
        splitter.repaint()
    }

    /** Re-apply whatever is active: the search query or the plain list. */
    private fun rerender() {
        if (activeQuery.isNotEmpty()) runSearch() else render(index.sessions)
    }

    private fun scheduleSearch() {
        searchAlarm.cancelAllRequests()
        val query = searchField.text.trim()
        if (query.length < MIN_QUERY_LENGTH) {
            // Cleared (or too short): drop back to the plain list immediately.
            searchScope.cancel()
            searching = false
            updateLoadingState()
            if (activeQuery.isNotEmpty()) {
                activeQuery = ""
                render(index.sessions)
            }
            return
        }
        searchAlarm.addRequest({ runSearch() }, SEARCH_DEBOUNCE_MS)
    }

    private fun runSearch() {
        latestSessions = index.sessions
        preparePaths(index.sessions)
        updateAgentMenu(index.sessions)
        val query = searchField.text.trim()
        if (query.length < MIN_QUERY_LENGTH) return
        activeQuery = query
        searching = true
        updateLoadingState()
        val candidates = baseFilter(index.sessions)
        ContentSearchService.getInstance().search(searchScope, query, candidates) { hits ->
            // Stale delivery guard: the field may have changed since this search was requested.
            if (searchField.text.trim() == query) renderSearchResults(query, hits)
        }
    }

    /** The project / worth-showing filter that applies to both the plain list and search candidates. */
    private fun baseFilter(all: List<Session>): List<Session> {
        val bounds = dateFilter.bounds()
        return AgentSessionFilter.apply(unfilteredSessions(all), agentFilter.mode)
            .filter { bounds.contains(it.lastActivityAt) }
    }

    /** Project/worth filter before applying the provider selection. */
    private fun unfilteredSessions(all: List<Session>): List<Session> {
        val minPrompts = settings.minPromptsForUntitled
        val roots = projectRoots()
        return all.asSequence()
            .filter { showHidden || !organisation.metadata(it.id).hidden }
            .filter { SessionFilter.isWorthShowing(it, minPrompts) }
            .filter { settings.showAllProjects || resolvedPaths.isUnderAny(it.cwd, roots) ||
                (agentFilter.includeWorktrees && resolvedPaths.belongsToRepository(it.cwd, roots)) }
            .toList()
    }

    /** Show only the sessions in [hits], ranked best first within their project groups. */
    fun renderSearchResults(query: String, hits: List<SearchHit>) {
        activeQuery = query
        searching = false
        updateLoadingState()
        val byId = hits.associateBy { it.session.id }
        visibleSessions = hits.map { it.session }
        renderer.hits = byId
        renderer.query = query

        val groups = SessionTreeModel.groupRanked(hits) { organisation.metadata(it.id).pinned }
        rebuildTree(groups)

        val text: StatusText = tree.emptyText
        text.clear()
        if (hits.isEmpty()) text.appendText("No sessions match \"$query\"" +
            if (dateFilter.period != com.hedworth.seshlog.index.DatePeriod.ALL) " · ${dateFilter.label}" else "")
    }

    /** Project base path + content roots. Read once per render, on the EDT (no filesystem access). */
    private fun projectRoots(): List<Path> {
        val roots = ArrayList<Path>()
        project.basePath?.let { roots.add(Paths.get(it)) }
        ProjectRootManager.getInstance(project).contentRoots.forEach { roots.add(Paths.get(it.path)) }
        return roots
    }

    internal fun projectRootsChanged() {
        // Since background write actions, roots notifications need not arrive on the EDT.
        val app = ApplicationManager.getApplication()
        val update = Runnable {
            if (!project.isDisposed && !Disposer.isDisposed(this)) {
                requestedPaths = emptySet()
                preparePaths(index.sessions)
                rerender()
            }
        }
        if (app.isDispatchThread) update.run() else app.invokeLater(update)
    }

    fun render(all: List<Session>) {
        latestSessions = all
        preparePaths(all)
        updateAgentMenu(all)
        val filtered = baseFilter(all)
        visibleSessions = filtered
        renderer.hits = emptyMap()
        renderer.query = ""

        val groups = SessionTreeModel.group(filtered) { organisation.metadata(it.id).pinned }
        rebuildTree(groups)
        updateEmptyText(all)
    }

    /** Capture UI roots here; resolution and all filesystem access happen on a worker. */
    private fun preparePaths(all: List<Session>) {
        val paths = (all.map { it.cwd } + projectRoots()).toSet()
        if (paths == requestedPaths) return
        requestedPaths = paths
        val cancelled = pathScope.begin()
        ApplicationManager.getApplication().executeOnPooledThread {
            val snapshot = ResolvedPaths.resolve(paths)
            ApplicationManager.getApplication().invokeLater {
                if (!cancelled() && !project.isDisposed) {
                    resolvedPaths = snapshot
                    if (activeQuery.isNotEmpty()) runSearch() else render(latestSessions)
                }
            }
        }
    }

    private fun rebuildTree(groups: List<ProjectGroup>) {
        val selection = selectedSession()?.id ?: rememberedSelection
        rebuildingTree = true
        try {
            val root = SessionTreeModel.buildRoot(groups)
            treeModel.setRoot(root)
            for (i in 0 until root.childCount) {
                val node = root.getChildAt(i) as DefaultMutableTreeNode
                val group = node.userObject as ProjectGroup
                if (group.cwd !in collapsedGroups) tree.expandPath(javax.swing.tree.TreePath(node.path))
            }
            selection?.let(::reselect)
        } finally {
            rebuildingTree = false
        }
    }

    /** Re-select the session with [id] if it is still in the tree; a vanished session just loses selection. */
    private fun reselect(id: String) {
        val path = TreeUtil.treePathTraverser(tree).filter { p ->
            ((p.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Session)?.id == id
        }.firstOrNull() ?: return
        if (tree.isExpanded(path.parentPath)) tree.selectionPath = path
    }

    private fun updateLoadingState() {
        val errors = index.providerDiagnostics.filterValues { it.health == com.hedworth.seshlog.model.ProviderHealth.ERROR }
        val parts = mutableListOf<String>()
        if (index.isScanning) parts += "Scanning…"
        if (searching) parts += "Searching…"
        errors.forEach { (kind, _) -> parts += "${kind.displayName}: storage could not be read" }
        statusLabel.text = parts.joinToString(" · ")
        statusLabel.toolTipText = errors.values.mapNotNull { it.problem }.joinToString("; ").ifEmpty { null }
        retryButton.isVisible = errors.isNotEmpty()
    }

    private fun updateEmptyText(all: List<Session>) {
        val text: StatusText = tree.emptyText
        text.clear()
        when {
            dateFilter.period != com.hedworth.seshlog.index.DatePeriod.ALL -> {
                text.appendText("No sessions in ${dateFilter.label} with the current filters.")
                text.appendLine("Clear date filter", com.intellij.ui.SimpleTextAttributes.LINK_ATTRIBUTES) {
                    setDateFilter(com.hedworth.seshlog.index.SessionDateFilter())
                }
            }
            all.isEmpty() -> {
                text.appendText("No coding-agent sessions found under")
                index.dataRootDescriptions().forEach { text.appendLine(it) }
            }
            baseFilter(all).isEmpty() && unfilteredSessions(all).isNotEmpty() -> {
                text.appendText(if (agentFilter.mode == AgentFilterMode.Selected(emptySet()))
                    "No agents selected." else "No sessions for the selected agents in the current scope.")
                text.appendLine("Show all agents", com.intellij.ui.SimpleTextAttributes.LINK_ATTRIBUTES) {
                    agentFilter.mode = AgentFilterMode.All
                    rerender()
                }
            }
            !settings.showAllProjects -> {
                text.appendText("No sessions for this project.")
                text.appendLine("Show all projects", com.intellij.ui.SimpleTextAttributes.LINK_ATTRIBUTES) {
                    settings.showAllProjects = true
                    rerender()
                }
            }
            else -> text.appendText("All sessions hidden by the current filter (see Settings | Tools | Seshlog).")
        }
    }

    fun selectedSession(): Session? =
        (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Session

    override fun getData(dataId: String): Any? = when {
        SeshlogDataKeys.SESSION.`is`(dataId) -> selectedSession()
        SeshlogDataKeys.PANEL.`is`(dataId) -> this
        CommonDataKeys.PROJECT.`is`(dataId) -> project
        PlatformDataKeys.CONTEXT_COMPONENT.`is`(dataId) -> tree
        else -> null
    }

    override fun dispose() { searchScope.dispose(); pathScope.dispose() }

    companion object {
        const val MIN_QUERY_LENGTH = 2
        const val SEARCH_DEBOUNCE_MS = 300
    }
}
