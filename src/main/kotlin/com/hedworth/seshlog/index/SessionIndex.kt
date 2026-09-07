package com.hedworth.seshlog.index

import com.hedworth.seshlog.claude.ClaudeCodeSessionProvider
import com.hedworth.seshlog.pi.PiSessionProvider
import com.hedworth.seshlog.codex.CodexSessionProvider
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.model.SessionProvider
import com.hedworth.seshlog.opencode.OpenCodeSessionProvider
import com.hedworth.seshlog.settings.SeshlogSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-level cache of all known sessions. Scans run on a pooled background thread; the
 * result is published via [SessionIndexListener] on the EDT. The filesystem is never touched on
 * the EDT.
 */
@Service(Service.Level.APP)
class SessionIndex : Disposable {
    private val LOG = logger<SessionIndex>()

    interface SessionIndexListener {
        fun scanStateChanged(scanning: Boolean) {}
        fun sessionsUpdated(sessions: List<Session>)
    }

    private val settings get() = SeshlogSettings.getInstance()

    val providers: List<SessionProvider> = listOf(
        ClaudeCodeSessionProvider(
            dataDir = { settings.resolvedClaudeDataDir() },
            executable = { settings.claudeExecutable },
            cacheFile = Paths.get(PathManager.getSystemPath(), "seshlog", "index.json"),
        ),
        CodexSessionProvider(
            dataDir = { settings.resolvedCodexDataDir() },
            executable = { settings.codexExecutable },
            cacheFile = Paths.get(PathManager.getSystemPath(), "seshlog", "codex-index.json"),
        ),
        // No cache file: a scan queries opencode's own database — the session list, plus the
        // prompts of each session that is new or changed since the last scan.
        OpenCodeSessionProvider(
            dataDir = { settings.resolvedOpenCodeDataDir() },
            executable = { settings.opencodeExecutable },
            showArchived = { settings.opencodeShowArchived },
        ),
        PiSessionProvider(
            sessionsDir = { settings.resolvedPiSessionsDir() },
            executable = { settings.piExecutable },
            cacheFile = Paths.get(PathManager.getSystemPath(), "seshlog", "pi-index.json"),
        ),
    )

    @Volatile
    var sessions: List<Session> = emptyList()
        private set

    @Volatile
    var lastScanMillis: Long = 0
        private set

    @Volatile var providerDiagnostics: Map<com.hedworth.seshlog.model.AgentKind, com.hedworth.seshlog.model.ProviderScan> = emptyMap()
        private set
    val isScanning: Boolean get() = scanning.get()
    private val scanning = AtomicBoolean(false)
    private val rescanRequested = AtomicBoolean(false)
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Seshlog scanner", 1)
    private val watcher = SessionWatcher(this) { refresh() }

    init {
        Disposer.register(this, watcher)
        watcher.start(providers.flatMap { it.watchRoots() })
    }

    /** Agent data roots, for the empty-state message. */
    fun dataRootDescriptions(): List<String> = providers.map { "${it.kind.displayName}: ${it.dataRoot()}" }

    fun watchRoots(): List<Path> = providers.flatMap { it.watchRoots() }

    fun providerFor(session: Session): SessionProvider = providers.first { it.kind == session.kind }

    fun resumeCommand(session: Session): String = providerFor(session).resumeCommand(session)

    fun forkCommand(session: Session): String = providerFor(session).forkCommand(session)

    fun sessionById(id: String): Session? = sessions.firstOrNull { it.id == id }

    /** Settings may have moved the data dir: re-watch and rescan. */
    fun settingsChanged() {
        watcher.start(watchRoots())
        refresh()
    }

    /** Request a rescan. Coalesces: at most one scan runs at a time, one more can be queued. */
    fun refresh() {
        if (!scanning.compareAndSet(false, true)) {
            rescanRequested.set(true)
            return
        }
        publishScanState(true)
        executor.execute { runScan() }
    }

    private fun runScan() {
        try {
            val start = System.currentTimeMillis()
            val previous = sessions.associateBy { it.id }
            val result = ArrayList<Session>()
            val diagnostics = linkedMapOf<com.hedworth.seshlog.model.AgentKind, com.hedworth.seshlog.model.ProviderScan>()
            for (provider in providers) {
                val scan = provider.scanWithDiagnostics(previous)
                diagnostics[provider.kind] = scan
                result += scan.sessions
            }
            providerDiagnostics = diagnostics
            result.sortByDescending { it.lastActivityAt }
            sessions = result
            lastScanMillis = System.currentTimeMillis() - start
            LOG.debug("Scanned ${result.size} sessions in ${lastScanMillis} ms")
            val app = ApplicationManager.getApplication()
            if (app != null && !app.isDisposed) {
                app.invokeLater({
                    if (!app.isDisposed) app.messageBus.syncPublisher(TOPIC).sessionsUpdated(result)
                })
            }
        } finally {
            scanning.set(false)
            publishScanState(false)
            if (rescanRequested.compareAndSet(true, false)) refresh()
        }
    }

    private fun publishScanState(scanning: Boolean) {
        val app = ApplicationManager.getApplication()
        if (!app.isDisposed) app.invokeLater {
            if (!app.isDisposed) app.messageBus.syncPublisher(TOPIC).scanStateChanged(scanning)
        }
    }

    override fun dispose() {
        executor.shutdownNow()
    }

    companion object {
        @JvmField
        val TOPIC: Topic<SessionIndexListener> =
            Topic.create("Seshlog session index", SessionIndexListener::class.java)

        fun getInstance(): SessionIndex = ApplicationManager.getApplication().getService(SessionIndex::class.java)
    }
}
