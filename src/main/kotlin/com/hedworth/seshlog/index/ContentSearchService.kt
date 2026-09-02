package com.hedworth.seshlog.index

import com.hedworth.seshlog.model.Session
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs content searches on a single pooled background thread. Each new [search] supersedes the
 * previous one: a running search notices and stops early, and only the latest query's result is
 * delivered (on the EDT). The filesystem is never touched on the EDT.
 */
@Service(Service.Level.APP)
class ContentSearchService : Disposable {
    private val LOG = logger<ContentSearchService>()

    private val index = ContentSearchIndex(
        extractor = { session -> SessionIndex.getInstance().providerFor(session).conversationText(session) },
        contentStamp = { session -> SessionIndex.getInstance().providerFor(session).contentStamp(session) },
    )
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Seshlog content search", 1)
    private val generation = AtomicLong()

    /**
     * Search [sessions] for [query] and deliver ranked hits to [onResult] on the EDT, unless a
     * newer search has been requested in the meantime.
     */
    fun search(query: String, sessions: List<Session>, onResult: (List<SearchHit>) -> Unit) {
        val myGen = generation.incrementAndGet()
        executor.execute {
            val start = System.currentTimeMillis()
            val hits = try {
                index.retainOnly(sessions.map { it.id })
                index.search(query, sessions) { generation.get() != myGen }
            } catch (e: Exception) {
                LOG.warn("Content search failed", e)
                emptyList()
            }
            if (generation.get() != myGen) return@execute
            LOG.debug("Content search '$query' → ${hits.size} hits in ${System.currentTimeMillis() - start} ms (${index.size} indexed)")
            val app = ApplicationManager.getApplication()
            if (app != null && !app.isDisposed) {
                app.invokeLater({ if (generation.get() == myGen && !app.isDisposed) onResult(hits) })
            }
        }
    }

    /** Invalidate any in-flight search so its result is never delivered. */
    fun cancel() {
        generation.incrementAndGet()
    }

    override fun dispose() {
        executor.shutdownNow()
    }

    companion object {
        fun getInstance(): ContentSearchService =
            ApplicationManager.getApplication().getService(ContentSearchService::class.java)
    }
}
