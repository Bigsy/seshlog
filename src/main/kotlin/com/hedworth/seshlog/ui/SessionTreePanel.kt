package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.ContentSearchService
import com.hedworth.seshlog.index.SearchRequestScope
import com.hedworth.seshlog.index.SearchHit
import com.hedworth.seshlog.index.SessionFilter
import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.SeshlogSettings
import com.hedworth.seshlog.settings.SeshlogSettingsListener
import com.hedworth.seshlog.settings.AgentFilterMode
import com.hedworth.seshlog.settings.AgentFilterState
import com.hedworth.seshlog.settings.AgentSessionFilter
import com.hedworth.seshlog.ui.actions.ResumeSessionAction
import com.hedworth.seshlog.ui.actions.SessionAction
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
import com.intellij.openapi.ui.popup.JBPopupFactory
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

    private val settings get() = SeshlogSettings.getInstance()
    private val index get() = SessionIndex.getInstance()
    private val agentFilter get() = AgentFilterState.getInstance(project)

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
        TreeSpeedSearch.installOn(tree, true) { path ->
            when (val obj = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
                is Session -> obj.title + " " + (obj.displayBranch ?: "")
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

        searchField.textEditor.emptyText.text = "Search titles and transcript content"
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = scheduleSearch()
        })

        val header = JPanel(BorderLayout()).apply {
            add(createToolbar().component, BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
        }
        add(header, BorderLayout.NORTH)
        splitter.firstComponent = ScrollPaneFactory.createScrollPane(tree)
        splitter.secondComponent = preview
        add(splitter, BorderLayout.CENTER)
        applyPreviewVisibility()

        tree.addTreeSelectionListener { preview.showSession(selectedSession()) }

        project.messageBus.connect(this).subscribe(SessionIndex.TOPIC, object : SessionIndex.SessionIndexListener {
            override fun sessionsUpdated(sessions: List<Session>) {
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
            add(AgentFilterAction())
            add(TogglePreviewAction())
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

    private inner class AgentFilterAction :
        DumbAwareAction("Agent: Auto", "Choose which coding agent's sessions to show", null) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            val mode = agentFilter.mode
            val effective = AgentSessionFilter.effectiveKind(unfilteredSessions(index.sessions), mode)
            e.presentation.text = when (mode) {
                AgentFilterMode.Auto -> "Agent: ${effective?.displayName ?: "Auto"} (Auto)"
                else -> "Agent: ${mode.displayName}"
            }
        }

        override fun actionPerformed(e: AnActionEvent) {
            val group = DefaultActionGroup()
            AgentFilterMode.entries.forEach { mode ->
                group.add(object : ToggleAction(mode.displayName) {
                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                    override fun isSelected(e: AnActionEvent): Boolean = agentFilter.mode == mode
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        if (state) {
                            agentFilter.mode = mode
                            rerender()
                        }
                    }
                })
            }
            JBPopupFactory.getInstance()
                .createActionGroupPopup("Show Agent", group, e.dataContext, false, null, -1)
                .showInBestPositionFor(e.dataContext)
        }
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
            if (activeQuery.isNotEmpty()) {
                activeQuery = ""
                render(index.sessions)
            }
            return
        }
        searchAlarm.addRequest({ runSearch() }, SEARCH_DEBOUNCE_MS)
    }

    private fun runSearch() {
        val query = searchField.text.trim()
        if (query.length < MIN_QUERY_LENGTH) return
        activeQuery = query
        val candidates = baseFilter(index.sessions)
        ContentSearchService.getInstance().search(searchScope, query, candidates) { hits ->
            // Stale delivery guard: the field may have changed since this search was requested.
            if (searchField.text.trim() == query) renderSearchResults(query, hits)
        }
    }

    /** The project / worth-showing filter that applies to both the plain list and search candidates. */
    private fun baseFilter(all: List<Session>): List<Session> {
        return AgentSessionFilter.apply(unfilteredSessions(all), agentFilter.mode)
    }

    /** Project/worth filter before applying the provider selection. */
    private fun unfilteredSessions(all: List<Session>): List<Session> {
        val minPrompts = settings.minPromptsForUntitled
        val roots = projectRoots()
        return all.asSequence()
            .filter { SessionFilter.isWorthShowing(it, minPrompts) }
            .filter { settings.showAllProjects || SessionFilter.belongsToProject(it, roots) }
            .toList()
    }

    /** Show only the sessions in [hits], ranked best first within their project groups. */
    fun renderSearchResults(query: String, hits: List<SearchHit>) {
        activeQuery = query
        val byId = hits.associateBy { it.session.id }
        visibleSessions = hits.map { it.session }
        renderer.hits = byId
        renderer.query = query

        val previouslySelected = selectedSession()?.id
        val groups = SessionTreeModel.groupRanked(hits)
        treeModel.setRoot(SessionTreeModel.buildRoot(groups))
        TreeUtil.expandAll(tree)
        previouslySelected?.let(::reselect)

        val text: StatusText = tree.emptyText
        text.clear()
        if (hits.isEmpty()) text.appendText("No sessions match \"$query\"")
    }

    /** Project base path + content roots. Read once per render, on the EDT (no filesystem access). */
    private fun projectRoots(): List<Path> {
        val roots = ArrayList<Path>()
        project.basePath?.let { roots.add(Paths.get(it)) }
        ProjectRootManager.getInstance(project).contentRoots.forEach { roots.add(Paths.get(it.path)) }
        return roots
    }

    fun render(all: List<Session>) {
        val filtered = baseFilter(all)
        visibleSessions = filtered
        renderer.hits = emptyMap()
        renderer.query = ""

        val previouslySelected = selectedSession()?.id
        val groups = SessionTreeModel.group(filtered)
        treeModel.setRoot(SessionTreeModel.buildRoot(groups))
        TreeUtil.expandAll(tree)
        previouslySelected?.let(::reselect)
        updateEmptyText(all)
    }

    /** Re-select the session with [id] if it is still in the tree; a vanished session just loses selection. */
    private fun reselect(id: String) {
        val path = TreeUtil.treePathTraverser(tree).filter { p ->
            ((p.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Session)?.id == id
        }.firstOrNull() ?: return
        TreeUtil.promiseSelect(tree, path)
    }

    private fun updateEmptyText(all: List<Session>) {
        val text: StatusText = tree.emptyText
        text.clear()
        when {
            all.isEmpty() -> {
                text.appendText("No coding-agent sessions found under")
                index.dataRootDescriptions().forEach { text.appendLine(it) }
            }
            baseFilter(all).isEmpty() && unfilteredSessions(all).isNotEmpty() -> {
                text.appendText("No ${agentFilter.mode.displayName} sessions in the current scope.")
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

    override fun dispose() { searchScope.dispose() }

    companion object {
        const val MIN_QUERY_LENGTH = 2
        const val SEARCH_DEBOUNCE_MS = 300
    }
}
