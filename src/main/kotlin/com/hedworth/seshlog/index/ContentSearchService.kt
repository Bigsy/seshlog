package com.hedworth.seshlog.index

import com.hedworth.seshlog.index.lexical.LexicalSearchStore
import com.hedworth.seshlog.index.lexical.SearchSessionKey
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.SessionOrganisation
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/** One bounded worker owns SQLite; callers keep independent cancellation scopes. */
@Service(Service.Level.APP)
class ContentSearchService : Disposable {
    private val log = logger<ContentSearchService>()
    val cachePath: Path
        get() = PathManager.getConfigDir().resolve("seshlog/search/lexical.sqlite")

    private val index =
        ContentSearchIndex(
            extractor = { SessionIndex.getInstance().providerFor(it).conversationText(it) },
            entryExtractor = { SessionIndex.getInstance().providerFor(it).conversationEntries(it) },
            localTitle = { SessionOrganisation.getInstance().metadata(it.id).title },
            contentStamp = { SessionIndex.getInstance().providerFor(it).contentStamp(it) },
            storeFactory = { LexicalSearchStore.open(cachePath) },
        )
    private val executor =
        AppExecutorUtil.createBoundedApplicationPoolExecutor("Seshlog content search", 1)
    private val epoch = AtomicLong()
    private val requests = AtomicLong()
    @Volatile private var disposed = false
    @Volatile private var lastCoverage = SearchCoverage(0, 0, 0, 0)
    val revision: Long
        get() = epoch.get()

    fun search(
        scope: SearchRequestScope,
        query: String,
        sessions: List<Session>,
        sources: SearchSources = SearchSources.DEFAULT,
        sort: SearchSort = SearchSort.BEST_MATCH,
        onResult: (List<SearchHit>) -> Unit,
    ) = searchSnapshot(scope, query, sessions, sources, sort) { onResult(it.hits) }

    fun searchSnapshot(
        scope: SearchRequestScope,
        query: String,
        sessions: List<Session>,
        sources: SearchSources = SearchSources.DEFAULT,
        sort: SearchSort = SearchSort.BEST_MATCH,
        onResult: (SearchResultSnapshot) -> Unit,
    ) {
        if (disposed) return
        val scopeCancelled = scope.begin()
        val generation = epoch.get()
        val request = requests.incrementAndGet()
        val cancelled = { disposed || scopeCancelled() || generation != epoch.get() }
        executor.execute {
            if (cancelled()) return@execute
            val snapshot =
                try {
                    val corpus = SessionIndex.getInstance()
                    index.retainOnly(
                        corpus.sessions.map { SearchSessionKey(it.kind, it.id) },
                        corpus.providerDiagnostics
                            .filterValues {
                                it.health == com.hedworth.seshlog.model.ProviderHealth.READY
                            }
                            .keys,
                    )
                    index.searchSnapshot(query, sessions, sources, sort, cancelled)
                } catch (_: Exception) {
                    // Providers/SQLite may include private text or paths in exception messages.
                    log.warn("Content search unavailable; retry in Search settings")
                    SearchResultSnapshot(
                        emptyList(),
                        SearchCoverage(sessions.size, 0, sessions.size, 0),
                        index.revision,
                        "Search unavailable. Retry in Search settings.",
                    )
                }
            if (cancelled()) return@execute
            lastCoverage = snapshot.coverage
            val result = snapshot.copy(request = request, candidateRevision = sessions.hashCode(), context = SearchContext(query, sources, sort))
            deliver { if (!cancelled()) onResult(result) }
        }
    }

    data class CacheStatus(
        val path: Path,
        val bytes: Long,
        val sources: Int,
        val coverage: SearchCoverage,
    )

    enum class CacheAction {
        REFRESH,
        REBUILD,
        CLEAR,
        RETRY,
    }

    fun status(callback: (CacheStatus) -> Unit) {
        if (disposed) return
        executor.execute {
            val bytes =
                try {
                    Files.newDirectoryStream(cachePath.parent, "lexical*.sqlite*").use { files ->
                        files.sumOf { Files.size(it) }
                    }
                } catch (_: Exception) {
                    0L
                }
            val count =
                try {
                    index.size
                } catch (_: Exception) {
                    0
                }
            deliver { callback(CacheStatus(cachePath, bytes, count, lastCoverage)) }
        }
    }

    fun maintain(action: CacheAction, callback: (String) -> Unit) =
        maintenance(callback) {
            when (action) {
                CacheAction.REFRESH -> {
                    index.refresh()
                    "Sources refreshed. Saved index data will be checked and reused on your next search."
                }
                CacheAction.REBUILD -> {
                    index.rebuildSavedIndex { LexicalSearchStore.reset(cachePath) }
                    "The index will rebuild on your next search. A recovery copy is saved in the index folder."
                }
                CacheAction.CLEAR -> {
                    index.rebuildSavedIndex { LexicalSearchStore.purge(cachePath) }
                    "Search index and recovery copies cleared. Your agent conversations are unchanged."
                }
                CacheAction.RETRY -> {
                    index.retry()
                    "Saved index will be reopened on the next search."
                }
            }
        }

    fun export(destination: Path, callback: (String) -> Unit) =
        maintenance(callback) {
            validateDestination(destination)
            index.export(destination)
            "Search index exported. It contains sensitive lexical terms and positions."
        }

    fun importSnapshot(source: Path, callback: (String) -> Unit) =
        maintenance(callback) {
            try {
                val count = index.importSnapshot(source) { disposed }
                "Imported $count sources; local transcripts will be validated before use."
            } catch (_: IllegalArgumentException) {
                "Search snapshot is incompatible or invalid. The active index is preserved. Rebuild and export again from the original IDE."
            }
        }

    private fun validateDestination(path: Path) {
        val destination = path.toAbsolutePath().normalize()
        val settings = com.hedworth.seshlog.settings.SeshlogSettings.getInstance()
        val home = Path.of(System.getProperty("user.home"))
        val forbidden =
            listOf(
                    settings.resolvedClaudeDataDir(),
                    settings.resolvedCodexDataDir(),
                    settings.resolvedOpenCodeDataDir(),
                    settings.resolvedPiSessionsDir(),
                )
                .map { it.toAbsolutePath().normalize() } +
                listOf(home.resolve(".claude"), home.resolve(".codex"), home.resolve(".pi/agent"))
        val resolved = destination.parent.toRealPath().resolve(destination.fileName)
        require(
            forbidden.none { resolved.startsWith(if (Files.exists(it)) it.toRealPath() else it) }
        )
        require(!resolved.startsWith(cachePath.parent.toAbsolutePath().normalize()))
    }

    private fun maintenance(callback: (String) -> Unit, action: () -> String) {
        if (disposed) return
        epoch.incrementAndGet()
        executor.execute {
            val result =
                try {
                    action()
                } catch (_: Exception) {
                    "Search cache action could not complete. Existing data was preserved where possible; close other IDE instances and retry, or choose a new export filename."
                }
            deliver {
                ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).changed()
                callback(result)
            }
        }
    }

    private fun deliver(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (!disposed && app != null && !app.isDisposed)
            app.invokeLater { if (!disposed && !app.isDisposed) action() }
    }

    override fun dispose() {
        disposed = true
        epoch.incrementAndGet()
        // A final queued close runs after the current bounded operation, never concurrently with
        // SQL.
        executor.execute { index.close() }
        executor.shutdown()
    }

    interface Listener {
        fun changed()
    }

    companion object {
        val TOPIC = Topic.create("Seshlog lexical index", Listener::class.java)

        fun getInstance(): ContentSearchService =
            ApplicationManager.getApplication().getService(ContentSearchService::class.java)
    }
}
