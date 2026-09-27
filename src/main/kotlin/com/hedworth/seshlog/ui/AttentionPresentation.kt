package com.hedworth.seshlog.ui

import com.hedworth.seshlog.SeshlogIcons
import com.hedworth.seshlog.index.SessionAttention
import com.hedworth.seshlog.index.ResolvedPaths
import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.AgentFilterState
import com.hedworth.seshlog.settings.SessionAttentionState
import com.hedworth.seshlog.settings.SessionOrganisation
import com.hedworth.seshlog.settings.SeshlogSettings
import com.hedworth.seshlog.settings.SeshlogSettingsListener
import com.hedworth.seshlog.terminal.OwnedTerminalTabs
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.BadgeIconSupplier
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicLong

/** Keeps attention indicators current even when the Seshlog tool window has never been opened. */
@Service(Service.Level.PROJECT)
class AttentionPresentation(private val project: Project) : Disposable {
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Seshlog attention", 1)
    private val generation = AtomicLong()
    private val badge = BadgeIconSupplier(SeshlogIcons.ToolWindow)
    private var resolvedPaths = ResolvedPaths.EMPTY
    private var resolvedRoots: List<Path> = emptyList()
    private var resolvedRefreshGeneration = 0L
    @Volatile var counts: SessionAttention.Counts = SessionAttention.Counts(0, 0)
        private set

    init {
        subscribe()
        refresh()
    }

    private fun subscribe() {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(SessionIndex.TOPIC,
            object : SessionIndex.SessionIndexListener {
                override fun sessionsUpdated(sessions: List<Session>) = refresh()
            })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(SessionAttentionState.TOPIC,
            object : SessionAttentionState.Listener { override fun changed() = refresh() })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(SeshlogSettingsListener.TOPIC,
            object : SeshlogSettingsListener { override fun settingsChanged() = refresh() })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(SessionOrganisation.TOPIC,
            object : SessionOrganisation.Listener { override fun changed() = refresh() })
        project.messageBus.connect(this).subscribe(OwnedTerminalTabs.RUNNING_TOPIC,
            object : OwnedTerminalTabs.RunningListener { override fun runningChanged(ids: Set<String>) = refresh() })
        project.messageBus.connect(this).subscribe(AgentFilterState.TOPIC,
            object : AgentFilterState.Listener { override fun changed() = refresh() })
        project.messageBus.connect(this).subscribe(AttentionViewState.TOPIC,
            object : AttentionViewState.Listener { override fun changed() = refresh() })
        project.messageBus.connect(this).subscribe(com.intellij.openapi.roots.ModuleRootListener.TOPIC,
            object : com.intellij.openapi.roots.ModuleRootListener {
                override fun rootsChanged(event: com.intellij.openapi.roots.ModuleRootEvent) = refresh()
            })
    }

    internal fun refresh() {
        if (project.isDisposed) return
        val request = generation.incrementAndGet()
        val sessions = SessionIndex.getInstance().sessions
        val running = OwnedTerminalTabs.getInstance(project).runningSessionIds()
        val unread = SessionAttentionState.getInstance().unreadIds
        val filter = filterSnapshot()
        executor.execute {
            if (filter.roots != resolvedRoots || filter.pathRefreshGeneration != resolvedRefreshGeneration) {
                resolvedPaths = ResolvedPaths.EMPTY
                resolvedRoots = filter.roots
                resolvedRefreshGeneration = filter.pathRefreshGeneration
            }
            val inputs = (sessions.map { it.cwd } + filter.roots).toSet()
            resolvedPaths = ResolvedPaths.resolve(inputs, previous = resolvedPaths)
            val scoped = AttentionProjectFilter.apply(
                sessions = sessions,
                roots = filter.roots,
                showAllProjects = filter.showAllProjects,
                includeWorktrees = filter.includeWorktrees,
                agentMode = filter.agentMode,
                hiddenIds = filter.hiddenIds,
                showHidden = filter.showHidden,
                minPrompts = filter.minPrompts,
                dateBounds = filter.dateBounds,
                canonical = resolvedPaths::canonical,
                repository = resolvedPaths::repository,
            )
            val next = SessionAttention.counts(scoped, running, unread)
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && generation.get() == request) apply(next)
            }
        }
    }

    private fun apply(next: SessionAttention.Counts) {
        counts = next
        ToolWindowManager.getInstance(project).getToolWindow("Seshlog")?.setIcon(
            if (next.unread > 0) badge.infoIcon else SeshlogIcons.ToolWindow,
        )
        val status = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)
            ?.getWidget(AttentionStatusBarWidget.ID) as? AttentionStatusBarWidget
        status?.update(next)
    }

    private fun filterSnapshot(): FilterSnapshot {
        val settings = SeshlogSettings.getInstance()
        val roots = buildList {
            project.basePath?.let { add(Paths.get(it)) }
            ProjectRootManager.getInstance(project).contentRoots.forEach { add(Paths.get(it.path)) }
        }
        val organisation = SessionOrganisation.getInstance()
        val view = AttentionViewState.getInstance(project).snapshot
        return FilterSnapshot(
            roots = roots,
            pathRefreshGeneration = SessionIndex.getInstance().pathRefreshGeneration,
            showHidden = view.showHidden,
            dateBounds = view.dateFilter.bounds(),
            showAllProjects = settings.showAllProjects,
            includeWorktrees = AgentFilterState.getInstance(project).includeWorktrees,
            agentMode = AgentFilterState.getInstance(project).mode,
            hiddenIds = SessionIndex.getInstance().sessions.mapNotNull { session ->
                session.id.takeIf { organisation.metadata(session.id).hidden }
            }.toSet(),
            minPrompts = settings.minPromptsForUntitled,
        )
    }

    private data class FilterSnapshot(
        val roots: List<Path>,
        val pathRefreshGeneration: Long,
        val showHidden: Boolean,
        val dateBounds: com.hedworth.seshlog.index.DateBounds,
        val showAllProjects: Boolean,
        val includeWorktrees: Boolean,
        val agentMode: com.hedworth.seshlog.settings.AgentFilterMode,
        val hiddenIds: Set<String>,
        val minPrompts: Int,
    )

    override fun dispose() {
        generation.incrementAndGet()
        executor.shutdownNow()
    }

    companion object {
        fun getInstance(project: Project): AttentionPresentation = project.getService(AttentionPresentation::class.java)
    }
}
