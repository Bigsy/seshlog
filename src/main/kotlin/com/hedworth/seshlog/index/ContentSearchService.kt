package com.hedworth.seshlog.index

import com.hedworth.seshlog.model.Session
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * Runs content searches on a single pooled background thread. Each new [search] supersedes the
 * previous one from the same requesting scope: a running search notices and stops early, and only the latest query's result is
 * delivered (on the EDT). The filesystem is never touched on the EDT.
 */
@Service(Service.Level.APP)
class ContentSearchService : Disposable {
    private val LOG = logger<ContentSearchService>()

    private val index = ContentSearchIndex(
        extractor = { session -> SessionIndex.getInstance().providerFor(session).conversationText(session) },
        localTitle = { com.hedworth.seshlog.settings.SessionOrganisation.getInstance().metadata(it.id).title },
        contentStamp = { session -> SessionIndex.getInstance().providerFor(session).contentStamp(session) },
    )
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Seshlog content search", 1)

    /**
     * Search [sessions] for [query] and deliver ranked hits to [onResult] on the EDT, unless a
     * newer search has been requested in the meantime.
     */
    fun search(scope: SearchRequestScope, query: String, sessions: List<Session>, onResult: (List<SearchHit>) -> Unit) {
        val isCancelled = scope.begin()
        executor.execute {
            val start = System.currentTimeMillis()
            val hits = try {
                index.retainOnly(SessionIndex.getInstance().sessions.map { it.id })
                index.search(query, sessions, isCancelled)
            } catch (e: Exception) {
                LOG.warn("Content search failed", e)
                emptyList()
            }
            if (isCancelled()) return@execute
            LOG.debug("Content search '$query' → ${hits.size} hits in ${System.currentTimeMillis() - start} ms (${index.size} indexed)")
            val app = ApplicationManager.getApplication()
            if (app != null && !app.isDisposed) {
                app.invokeLater({ if (!isCancelled() && !app.isDisposed) onResult(hits) })
            }
        }
    }

    override fun dispose() {
        executor.shutdownNow()
    }

    companion object {
        fun getInstance(): ContentSearchService =
            ApplicationManager.getApplication().getService(ContentSearchService::class.java)
    }
}
