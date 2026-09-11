package com.hedworth.seshlog.restore

import com.hedworth.seshlog.index.SearchRequestScope
import com.hedworth.seshlog.model.Session

/** UI captures tab handles; process inspection runs on a worker and only the latest result applies. */
internal class RestoreSnapshotCollector<T>(
    private val background: (() -> Unit) -> Unit,
    private val ui: (() -> Unit) -> Unit,
    private val isBusy: (T) -> Boolean,
    private val tree: ProcessTree,
) {
    private val scope = SearchRequestScope()

    fun collect(
        sessions: List<Session>,
        launched: Set<String>,
        starting: List<String>,
        shellPids: Set<Long>,
        widgets: Map<String, T>,
        apply: (List<String>) -> Unit,
    ) {
        val cancelled = scope.begin()
        background {
            if (cancelled()) return@background
            val detected = RestoreCandidates.snapshot(sessions, launched, shellPids, tree)
            val busy = widgets.filterValues(isBusy).keys
            val ids = (detected + busy + starting).distinct()
            ui { if (!cancelled()) apply(ids) }
        }
    }

    fun invalidate() = scope.cancel()
    fun dispose() = scope.dispose()
}
