package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.ContentSearchService
import com.hedworth.seshlog.index.DatePeriod
import com.hedworth.seshlog.index.ResolvedPaths
import com.hedworth.seshlog.index.SearchHit
import com.hedworth.seshlog.index.SearchRequestScope
import com.hedworth.seshlog.index.SessionAttention
import com.hedworth.seshlog.index.SessionDateFilter
import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.index.TextQuery
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.ConversationLimits
import com.hedworth.seshlog.model.ProviderHealth
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.AgentFilterMode
import com.hedworth.seshlog.settings.AgentFilterState
import com.hedworth.seshlog.settings.AgentSessionFilter
import com.hedworth.seshlog.settings.SeshlogSettings
import com.hedworth.seshlog.settings.SeshlogSettingsListener
import com.hedworth.seshlog.settings.SessionAttentionState
import com.hedworth.seshlog.settings.SessionOrganisation
import com.hedworth.seshlog.terminal.OwnedTerminalTabs
import com.hedworth.seshlog.ui.actions.ResumeSessionAction
import com.hedworth.seshlog.ui.actions.SessionAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBPanel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StatusText
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import javax.swing.JButton
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class SessionTreePanel(private val project: Project, parentDisposable: Disposable) :
    JBPanel<SessionTreePanel>(BorderLayout()), DataProvider, Disposable {

    @Volatile private var resolvedPaths = ResolvedPaths.EMPTY
    private var requestedPaths: Set<Path> = emptySet()
    private var pathResolutionGeneration = 0L
    private var latestSessions: List<Session> = emptyList()
    private var handledPathRefreshGeneration = index.pathRefreshGeneration
    private var renderedGroups: List<ProjectGroup>? = null
    private var lastSearchQuery: String? = null
    private var lastSearchCandidates: List<Session>? = null
    private var previewKey: PreviewKey? = null

    private val organisation get() = SessionOrganisation.getInstance()
    private val attentionView get() = AttentionViewState.getInstance(project)
    private var showHidden = false
    internal var dateFilter = SessionDateFilter()
        private set
    private val dateButton = JButton("All time")
    private val clearDate = JButton("Clear date").apply { isVisible = false }
    private var filtersAnchor: JComponent? = null

    internal fun setDateFilter(filter: SessionDateFilter) {
        dateFilter = filter
        attentionView.update(dateFilter = filter)
        dateButton.text = filter.label
        clearDate.isVisible = filter.period != DatePeriod.ALL
        rerender()
    }

    private fun chooseDateFilter() {
        val menu = JPopupMenu()
        DatePeriod.values().forEach { period ->
            menu.add(JMenuItem(period.label).apply {
                addActionListener {
                    if (period != DatePeriod.CUSTOM) {
                        setDateFilter(SessionDateFilter(period))
                    } else {
                        val dialog = CustomDateRangeDialog(project, dateFilter.start, dateFilter.end)
                        if (dialog.showAndGet()) setDateFilter(dialog.filter())
                    }
                }
            })
        }
        val anchor = filtersAnchor ?: this
        menu.show(anchor, 0, anchor.height)
    }

    private val settings get() = SeshlogSettings.getInstance()
    private val index get() = SessionIndex.getInstance()
    private val agentFilter get() = AgentFilterState.getInstance(project)

    internal val agentMenuButton = JButton("Agents ▾")

    private val statusLabel = JLabel()
    private val retryButton = JButton("Retry")
    private var searching = false
    private var searchPartial = false

    private val collapsedGroups = mutableSetOf<Path>()
    private var rebuildingTree = false
    private var rememberedSelection: String? = null

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode())
    val tree: Tree = object : Tree(treeModel) {
        override fun getScrollableTracksViewportWidth() = true
    }
    private val renderer = SessionCellRenderer()
    /** Refreshes the "waiting N min" badges; nothing else in the tree depends on wall-clock time. */
    private val badgeClock = Timer(60_000) { refreshBadges(renderer.runningOwned) }

    /** Bottom pane showing the selected session's last messages. */
    val preview = SessionPreviewPanel(this)
    private val splitter = OnePixelSplitter(true, "Seshlog.PreviewSplitter", 0.6f)
    /** Holds the splitter, plus the preview header below it while the preview is collapsed. */
    private val content = JPanel(BorderLayout())

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
        tree.rowHeight = 0 // Session rows include metadata; project headings stay compact.
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = renderer
        renderer.activeSessionId = OwnedTerminalTabs.getInstance(project).activeSessionId
        badgeClock.start()
        renderer.runningOwned = OwnedTerminalTabs.getInstance(project).runningSessionIds()
        renderer.unread = SessionAttentionState.getInstance().unreadIds
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            SessionAttentionState.TOPIC,
            object : SessionAttentionState.Listener {
                override fun changed() {
                    renderer.unread = SessionAttentionState.getInstance().unreadIds
                    refreshBadges(renderer.runningOwned)
                }
            })
        project.messageBus.connect(this).subscribe(OwnedTerminalTabs.RUNNING_TOPIC,
            object : OwnedTerminalTabs.RunningListener {
                override fun runningChanged(ids: Set<String>) = refreshBadges(ids)
            })
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
        searchField.toolTipText = TextQuery.HINT + " " + ConversationLimits.NOTICE
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = scheduleSearch()
        })

        agentMenuButton.addActionListener {
            createAgentMenu().show(agentMenuButton, 0, agentMenuButton.height)
        }
        dateButton.addActionListener { chooseDateFilter() }
        clearDate.addActionListener { setDateFilter(SessionDateFilter()) }
        val header = JPanel(BorderLayout()).apply {
            add(createToolbar().component, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(0, 4, 4, 4)
                add(searchField, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }
        add(header, BorderLayout.NORTH)
        splitter.firstComponent = ScrollPaneFactory.createScrollPane(tree)
        content.add(splitter, BorderLayout.CENTER)
        add(content, BorderLayout.CENTER)
        preview.onCollapsedChanged = ::applyPreviewVisibility
        add(JPanel(BorderLayout()).apply {
            add(statusLabel, BorderLayout.CENTER)
            add(retryButton, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        retryButton.addActionListener {
            invalidateResolvedPaths()
            lastSearchQuery = null
            lastSearchCandidates = null
            index.refresh()
            if (activeQuery.isNotEmpty()) runSearch()
        }
        updateLoadingState()
        applyPreviewVisibility()

        tree.addTreeSelectionListener {
            selectedSession()?.let { rememberedSelection = it.id }
            showPreviewIfChanged()
        }
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) { rememberExpansion(event, false) }
            override fun treeCollapsed(event: TreeExpansionEvent) { rememberExpansion(event, true) }
            private fun rememberExpansion(event: TreeExpansionEvent, collapsed: Boolean) {
                if (rebuildingTree) return
                val group = (event.path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ProjectGroup ?: return
                if (collapsed) collapsedGroups.add(group.cwd) else collapsedGroups.remove(group.cwd)
            }
        })

        project.messageBus.connect(this).subscribe(SessionIndex.TOPIC, object : SessionIndex.SessionIndexListener {
            override fun scanStateChanged(scanning: Boolean) { updateLoadingState() }
            override fun sessionsUpdated(sessions: List<Session>) {
                updateLoadingState()
                if (handledPathRefreshGeneration != index.pathRefreshGeneration) {
                    handledPathRefreshGeneration = index.pathRefreshGeneration
                    invalidateResolvedPaths()
                }
                preparePaths(sessions)
                // Agents without a pid file show their activity only while their owned tab still runs them.
                renderer.runningOwned = OwnedTerminalTabs.getInstance(project).runningSessionIds()
                // A rescan while searching: re-run the query so new/changed transcripts are included.
                if (activeQuery.isNotEmpty()) runSearch() else render(sessions)
            }
        })

        ApplicationManager.getApplication().messageBus.connect(this).subscribe(SeshlogSettingsListener.TOPIC, object : SeshlogSettingsListener {
            override fun settingsChanged() {
                lastSearchQuery = null
                lastSearchCandidates = null
                this@SessionTreePanel.settingsChanged()
                rerender()
            }
        })

        project.messageBus.connect(this).subscribe(
            ModuleRootListener.TOPIC,
            object : ModuleRootListener {
                override fun rootsChanged(event: ModuleRootEvent) {
                    projectRootsChanged()
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            SessionOrganisation.TOPIC,
            object : SessionOrganisation.Listener {
                override fun changed() {
                    lastSearchQuery = null
                    lastSearchCandidates = null
                    rerender()
                }
            },
        )
        attentionView.update(showHidden = showHidden, dateFilter = dateFilter)
        render(index.sessions)
        index.refresh()
    }

    /** Run [action] through the action system on the current selection. */
    internal fun invoke(action: SessionAction) {
        val session = selectedSession() ?: return
        val context = DataContext { dataId ->
            when {
                SeshlogDataKeys.SESSION.`is`(dataId) -> session
                CommonDataKeys.PROJECT.`is`(dataId) -> project
                else -> null
            }
        }
        ActionUtil.performAction(action, AnActionEvent.createFromDataContext("Seshlog", null, context))
    }

    private fun createToolbar(): ActionToolbar {
        val group = DefaultActionGroup().apply {
            add(ActionManager.getInstance().getAction("Seshlog.Refresh"))
            add(ActionManager.getInstance().getAction("Seshlog.NewSession"))
            add(ActionManager.getInstance().getAction("Seshlog.NextAttention"))
            add(ToggleAllProjectsAction())
            add(createFilterActions().apply { templatePresentation.icon = AllIcons.General.Filter })
            add(TogglePreviewAction())
            add(ActionManager.getInstance().getAction("Seshlog.OpenConversation"))
            add(ActionManager.getInstance().getAction("Seshlog.OpenContinuation"))
            addSeparator()
            add(object : DumbAwareAction("Settings", "Open Seshlog settings", AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) =
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, "Seshlog")
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("SeshlogToolbar", group, true)
        // Keep one row and put excess actions in the overflow menu on narrow tool windows.
        // A wrapping toolbar beside search can grow vertically during preferred-size calculation.
        toolbar.layoutStrategy = ToolbarLayoutStrategy.AUTOLAYOUT_STRATEGY
        toolbar.targetComponent = this
        filtersAnchor = toolbar.component
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

    internal fun createAgentMenu(): JPopupMenu = JPopupMenu().apply {
        val scoped = unfilteredSessions(latestSessions)
        val available = scoped.map { it.kind }.toSet()
        val selected = AgentSessionFilter.effectiveKinds(scoped, agentFilter.mode)
        add(JCheckBoxMenuItem("All agents", agentFilter.mode == AgentFilterMode.All).apply {
            addActionListener {
                agentFilter.mode = AgentFilterMode.All
                rerender()
            }
        })
        add(JCheckBoxMenuItem("Auto", agentFilter.mode == AgentFilterMode.Auto).apply {
            toolTipText = "Automatically show the agent with the most sessions"
            addActionListener {
                agentFilter.mode = AgentFilterMode.Auto
                rerender()
            }
        })
        if (available.isNotEmpty()) addSeparator()
        AgentKind.entries.filter { it in available }.forEach { kind ->
            add(JCheckBoxMenuItem(kind.displayName, kind in selected).apply {
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

    /** Filter actions shared by the toolbar and focused UI tests. */
    internal fun createFilterActions(): DefaultActionGroup = DefaultActionGroup("Filters", true).apply {
            add(object : DumbAwareAction("Agents", "Choose which agents are shown", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    createAgentMenu().show(filtersAnchor ?: this@SessionTreePanel, 0, filtersAnchor?.height ?: 0)
                }
            })
            add(object : DumbAwareAction("Date", "Limit sessions by activity date", null) {
                override fun update(e: AnActionEvent) { e.presentation.text = "Date: ${dateFilter.label}" }
                override fun actionPerformed(e: AnActionEvent) = chooseDateFilter()
            })
            add(object : ToggleAction("Sibling worktrees", "Include worktrees sharing this repository", AllIcons.Vcs.Branch) {
                override fun isSelected(e: AnActionEvent) = agentFilter.includeWorktrees
                override fun setSelected(e: AnActionEvent, state: Boolean) { agentFilter.includeWorktrees = state; rerender() }
            })
            add(object : ToggleAction("Show hidden", "Include locally hidden sessions", AllIcons.Actions.Show) {
                override fun isSelected(e: AnActionEvent) = showHidden
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    showHidden = state
                    attentionView.update(showHidden = state)
                    rerender()
                }
            })
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
        previewKey = null
        preview.reload()
        applyPreviewVisibility()
        showPreviewIfChanged()
    }

    private fun applyPreviewVisibility() {
        val show = settings.showPreview
        val folded = show && preview.collapsed
        preview.isVisible = show
        // A collapsed preview keeps only its header row, docked under the list rather than splitting it.
        splitter.secondComponent = if (show && !folded) preview else null
        if (folded) content.add(preview, BorderLayout.SOUTH) else content.remove(preview)
        if (show) showPreviewIfChanged()
        content.revalidate()
        content.repaint()
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
            lastSearchQuery = null
            lastSearchCandidates = null
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
        val candidates = baseFilter(index.sessions)
        if (query == lastSearchQuery && candidates == lastSearchCandidates) return
        lastSearchQuery = query
        lastSearchCandidates = candidates
        activeQuery = query
        searching = true
        updateLoadingState()
        ContentSearchService.getInstance().search(searchScope, query, candidates) { hits ->
            // Stale delivery guard: the field may have changed since this search was requested.
            if (searchField.text.trim() == query) renderSearchResults(query, hits)
        }
    }

    /** The project / worth-showing filter that applies to both the plain list and search candidates. */
    private fun baseFilter(all: List<Session>): List<Session> {
        return SessionListViewModel.visible(all, projectRoots(), resolvedPaths, listFilters(), hiddenIds(all))
    }

    /** Project/worth filter before applying the provider selection. */
    private fun unfilteredSessions(all: List<Session>): List<Session> {
        return SessionListViewModel.visible(
            all, projectRoots(), resolvedPaths,
            listFilters().copy(agentMode = AgentFilterMode.All, dateFilter = SessionDateFilter()),
            hiddenIds(all),
        )
    }

    private fun listFilters() = SessionListFilters(
        showAllProjects = settings.showAllProjects,
        includeWorktrees = agentFilter.includeWorktrees,
        showHidden = showHidden,
        minPrompts = settings.minPromptsForUntitled,
        agentMode = agentFilter.mode,
        dateFilter = dateFilter,
    )

    private fun hiddenIds(all: List<Session>): Set<String> =
        all.asSequence().filter { organisation.metadata(it.id).hidden }.map { it.id }.toSet()

    /** Show only the sessions in [hits], ranked best first within their project groups. */
    fun renderSearchResults(query: String, hits: List<SearchHit>) {
        activeQuery = query
        searching = false
        searchPartial = hits.any { it.partial }
        updateLoadingState()
        val byId = hits.associateBy { it.session.id }
        visibleSessions = hits.map { it.session }
        renderer.hits = byId
        renderer.query = query

        val groups = SessionTreeModel.groupRanked(hits) { organisation.metadata(it.id).pinned }
        rebuildTree(groups)
        showPreviewIfChanged()

        val text: StatusText = tree.emptyText
        text.clear()
        if (hits.isEmpty()) text.appendText("No sessions match \"$query\"" +
            if (dateFilter.period != DatePeriod.ALL) " · ${dateFilter.label}" else "")
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
                invalidateResolvedPaths()
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
        val result = SessionListViewModel.build(
            all, projectRoots(), resolvedPaths, listFilters(), hiddenIds(all),
        ) { organisation.metadata(it.id).pinned }
        visibleSessions = result.visible
        searchPartial = false
        updateLoadingState()
        renderer.hits = emptyMap()
        renderer.query = ""

        rebuildTree(result.groups)
        showPreviewIfChanged()
        updateEmptyText(all, result)
    }

    /** Capture UI roots here; resolution and all filesystem access happen on a worker. */
    private fun preparePaths(all: List<Session>) {
        val paths = (all.map { it.cwd } + projectRoots()).toSet()
        val missing = paths.filter { !resolvedPaths.contains(it) && it !in requestedPaths }.toSet()
        if (missing.isEmpty()) return
        requestedPaths = requestedPaths + missing
        val generation = pathResolutionGeneration
        ApplicationManager.getApplication().executeOnPooledThread {
            val snapshot = ResolvedPaths.resolve(missing, previous = resolvedPaths)
            ApplicationManager.getApplication().invokeLater {
                if (generation == pathResolutionGeneration) requestedPaths = requestedPaths - missing
                if (!project.isDisposed && !Disposer.isDisposed(this) && generation == pathResolutionGeneration) {
                    resolvedPaths = resolvedPaths.merge(snapshot)
                    if (activeQuery.isNotEmpty()) runSearch() else render(latestSessions)
                }
            }
        }
    }

    private fun rebuildTree(groups: List<ProjectGroup>) {
        if (renderedGroups == groups) {
            tree.repaint()
            return
        }
        renderedGroups = groups.toList()
        val selection = selectedSession()?.id ?: rememberedSelection
        rebuildingTree = true
        try {
            val root = SessionTreeModel.buildRoot(groups)
            treeModel.setRoot(root)
            for (i in 0 until root.childCount) {
                val node = root.getChildAt(i) as DefaultMutableTreeNode
                val group = node.userObject as ProjectGroup
                if (group.cwd !in collapsedGroups) tree.expandPath(TreePath(node.path))
            }
            selection?.let(::reselect)
        } finally {
            rebuildingTree = false
        }
    }

    private fun invalidateResolvedPaths() {
        pathResolutionGeneration++
        resolvedPaths = ResolvedPaths.EMPTY
        requestedPaths = emptySet()
    }

    private fun showPreviewIfChanged() {
        val session = selectedSession()
        val key = PreviewKey(
            session?.id,
            session?.lastActivityAt,
            activeQuery,
            session?.let { organisation.title(it) },
        )
        if (key == previewKey) return
        previewKey = key
        preview.showSession(session, activeQuery)
    }

    private data class PreviewKey(
        val sessionId: String?,
        val lastActivityAt: Instant?,
        val query: String,
        val localTitle: String?,
    )

    /** Re-select the session with [id] if it is still in the tree; a vanished session just loses selection. */
    private fun reselect(id: String) {
        val path = TreeUtil.treePathTraverser(tree).filter { p ->
            ((p.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Session)?.id == id
        }.firstOrNull() ?: return
        if (tree.isExpanded(path.parentPath)) tree.selectionPath = path
    }

    private fun updateLoadingState() {
        val errors = index.providerDiagnostics.filterValues { it.health == ProviderHealth.ERROR }
        val parts = mutableListOf<String>()
        if (index.isScanning) parts += "Scanning…"
        if (searching) parts += "Searching…"
        if (searchPartial) parts += "Limited coverage"
        errors.forEach { (kind, _) -> parts += "${kind.displayName}: storage could not be read" }
        statusLabel.text = parts.joinToString(" · ")
        statusLabel.toolTipText = when {
            errors.isNotEmpty() -> errors.values.mapNotNull { it.problem }.joinToString("; ")
            searchPartial -> ConversationLimits.NOTICE
            else -> null
        }
        retryButton.isVisible = errors.isNotEmpty()
    }

    private fun updateEmptyText(all: List<Session>, model: SessionListViewModel.Result? = null) {
        val text: StatusText = tree.emptyText
        text.clear()
        val result = model ?: SessionListViewModel.build(
            all, projectRoots(), resolvedPaths, listFilters(), hiddenIds(all),
        ) { organisation.metadata(it.id).pinned }
        when {
            result.emptyReason == SessionListViewModel.EmptyReason.Date -> {
                text.appendText("No sessions in ${dateFilter.label} with the current filters.")
                text.appendLine("Clear date filter", SimpleTextAttributes.LINK_ATTRIBUTES) {
                    setDateFilter(SessionDateFilter())
                }
            }
            result.emptyReason == SessionListViewModel.EmptyReason.NoSessions -> {
                text.appendText("No coding-agent sessions found under")
                index.dataRootDescriptions().forEach { text.appendLine(it) }
            }
            result.emptyReason == SessionListViewModel.EmptyReason.Agent -> {
                text.appendText(if (agentFilter.mode == AgentFilterMode.Selected(emptySet()))
                    "No agents selected." else "No sessions for the selected agents in the current scope.")
                text.appendLine("Show all agents", SimpleTextAttributes.LINK_ATTRIBUTES) {
                    agentFilter.mode = AgentFilterMode.All
                    rerender()
                }
            }
            !settings.showAllProjects -> {
                text.appendText("No sessions for this project.")
                text.appendLine("Show all projects", SimpleTextAttributes.LINK_ATTRIBUTES) {
                    settings.showAllProjects = true
                    rerender()
                }
            }
            else -> text.appendText("All sessions hidden by the current filter (see Settings | Tools | Seshlog).")
        }
    }

    fun selectedSession(): Session? =
        (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Session

    internal fun nextAttentionSession(): Session? {
        val root = treeModel.root as DefaultMutableTreeNode
        val displayed = root.depthFirstEnumeration().asSequence()
            .mapNotNull { (it as DefaultMutableTreeNode).userObject as? Session }.toList()
        return SessionAttention.next(displayed,
            SessionAttentionState.getInstance().unreadIds, selectedSession()?.id)
    }

    fun showNextAttention() {
        val target = nextAttentionSession() ?: return
        val root = treeModel.root as DefaultMutableTreeNode
        val node = TreeUtil.findNodeWithObject(root, target) ?: return
        val path = TreePath(node.path)
        tree.expandPath(path.parentPath)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
        if (!OwnedTerminalTabs.getInstance(project).focus(target.id)) {
            // Explicit navigation should expose the latest reply even with preview hidden, collapsed or a search active.
            if (!settings.showPreview || preview.collapsed || activeQuery.isNotEmpty())
                ConversationEditorTabs.open(project, target, "", startAtLatest = true)
            else preview.showSession(target)
        }
    }

    override fun getData(dataId: String): Any? = when {
        SeshlogDataKeys.SESSION.`is`(dataId) -> selectedSession()
        SeshlogDataKeys.PANEL.`is`(dataId) -> this
        SeshlogDataKeys.PROJECT_CWD.`is`(dataId) -> selectedProjectCwd()
        CommonDataKeys.PROJECT.`is`(dataId) -> project
        PlatformDataKeys.CONTEXT_COMPONENT.`is`(dataId) -> tree
        else -> null
    }

    private fun selectedProjectCwd(): Path? =
        ((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ProjectGroup)?.cwd
            ?: project.basePath?.let { Paths.get(it) }

    internal fun refreshBadges(ids: Set<String>) {
        renderer.runningOwned = ids
        val root = treeModel.root as DefaultMutableTreeNode
        for (node in root.depthFirstEnumeration()) {
            val value = (node as DefaultMutableTreeNode).userObject
            if (value is Session || value is ProjectGroup) treeModel.nodeChanged(node)
        }
    }

    override fun dispose() { badgeClock.stop(); searchScope.dispose() }

    companion object {
        const val MIN_QUERY_LENGTH = 2
        const val SEARCH_DEBOUNCE_MS = 300
    }
}

private class CustomDateRangeDialog(
    project: Project,
    start: LocalDate?,
    end: LocalDate?,
) : DialogWrapper(project) {
    private val startField = JTextField(start?.toString() ?: LocalDate.now().toString(), 12)
    private val endField = JTextField(end?.toString() ?: LocalDate.now().toString(), 12)

    init {
        title = "Custom date range"
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(GridLayout(0, 2, 8, 6)).apply {
        add(JLabel("From (YYYY-MM-DD)")); add(startField)
        add(JLabel("Through (YYYY-MM-DD)")); add(endField)
    }

    override fun doValidate(): ValidationInfo? {
        val from = runCatching { LocalDate.parse(startField.text.trim()) }.getOrNull()
            ?: return ValidationInfo("Enter a valid start date (YYYY-MM-DD).", startField)
        val through = runCatching { LocalDate.parse(endField.text.trim()) }.getOrNull()
            ?: return ValidationInfo("Enter a valid end date (YYYY-MM-DD).", endField)
        if (from > through) return ValidationInfo("The start date must be on or before the end date.", endField)
        return null
    }

    fun filter() = SessionDateFilter(
        DatePeriod.CUSTOM,
        LocalDate.parse(startField.text.trim()),
        LocalDate.parse(endField.text.trim()),
    )
}
