package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.diagnostics.DiagnosticRef
import com.hedworth.seshlog.diagnostics.DiagnosticTrace

/** One uniquely identified session discovered in a tab during a process snapshot. */
internal data class TabProcessDiscovery<T : Any, P : Any>(
    val tab: T,
    val sessionId: String,
    val process: P?,
)

internal data class TerminalReconcileInput<T : Any, P : Any>(
    val sessions: List<Session>,
    /** Current registry state, keyed by session id. */
    val ownership: Map<String, T>,
    /** Ownership captured alongside the background process inspection. */
    val ownershipAtInspection: Map<T, String?>,
    /** Per-tab process discovery from the same background inspection. */
    val discoveries: List<TabProcessDiscovery<T, P>>,
    /** Sessions whose already-owned process was found running in this inspection. */
    val running: Set<String>,
    /** Process observed on the EDT before the worker inspection started. */
    val observedBefore: Map<String, P>,
    /** Process currently observed on the EDT when the result is applied. */
    val observedNow: Map<String, P>,
    /** Sessions whose captured process handles were no longer alive in the worker. */
    val exited: Set<String>,
    val tabTitles: Map<T, String?>,
    val displayTitle: (Session) -> String,
    /** Per-tab generation captured with [ownershipAtInspection]. */
    val generationsAtInspection: Map<T, Long> = emptyMap(),
    /** Per-tab generation at apply time. */
    val generationsNow: Map<T, Long> = emptyMap(),
    /** Exact process identity found for already-owned sessions in this inspection. */
    val runningProcesses: Map<String, P> = emptyMap(),
    /** Current evidence positively rules out the prior identity, even before index arrival. */
    val invalidatedTabs: Set<T> = emptySet(),
)

internal data class TerminalReconcileResult<T : Any, P : Any>(
    /** New/adopted associations. Applying these through TabRegistry preserves eviction rules. */
    val adopt: Map<String, T>,
    /** Associations whose exact observed process exited. */
    val release: Set<String>,
    /** Session id to process identity to add to ObservedAgents. */
    val observe: Map<String, P>,
    /** Ended session to tab, retained for copy/fork after release. */
    val ended: Map<T, String>,
    /** Session ids that should carry the running badge for this tick. */
    val running: Set<String>,
    val retitle: List<TabRegistry.Retitle<T>>,
    /** Release targets, retained alongside [release] for generation-checked application. */
    val releaseTabs: Map<String, T> = emptyMap(),
    /** Thread switches are not exits and must never populate ended-session context. */
    val displacedTabs: Map<String, T> = emptyMap(),
)

/**
 * Pure reconciliation of one terminal polling result. No registry or process state is mutated;
 * callers apply [TerminalReconcileResult] on the EDT only after checking their generation.
 */
internal object TerminalReconciler {
    fun <T : Any, P : Any> reconcile(input: TerminalReconcileInput<T, P>, trace: DiagnosticTrace = DiagnosticTrace.NONE): TerminalReconcileResult<T, P> {
        val sessions = input.sessions.associateBy { it.id }
        val ownershipByTab = input.ownership.entries.associate { it.value to it.key }.toMutableMap()
        val adopt = LinkedHashMap<String, T>()
        val observe = LinkedHashMap<String, P>()
        fun reject(tab: T, reason: String, id: String? = null) = trace.event("reconcile_rejected",
            "tab" to DiagnosticRef.tab(tab), "session" to DiagnosticRef.of(id), "reason" to reason,
            "inspectedGeneration" to input.generationsAtInspection[tab], "currentGeneration" to input.generationsNow[tab])

        fun unchanged(tab: T): Boolean {
            val inspectedOwner = input.ownershipAtInspection[tab]
            if (input.ownershipAtInspection.containsKey(tab) && ownershipByTab[tab] != inspectedOwner) {
                reject(tab, "owner_changed"); return false
            }
            if (input.generationsAtInspection.isNotEmpty() &&
                input.generationsNow[tab] != input.generationsAtInspection[tab]) {
                reject(tab, "generation_changed"); return false
            }
            return true
        }

        // A tab with multiple identities, or an identity seen in multiple tabs, is ambiguous.
        // Reject the whole ambiguous set instead of picking an iteration-order winner.
        val uniqueDiscoveries = input.discoveries.distinctBy { it.tab to it.sessionId }
        val tabsByDiscovery = uniqueDiscoveries.groupBy { it.tab }
        val tabsBySession = uniqueDiscoveries.groupBy { it.sessionId }
        val unambiguous = uniqueDiscoveries.filter { discovery ->
            tabsByDiscovery.getValue(discovery.tab).size == 1 &&
                tabsBySession.getValue(discovery.sessionId).map { it.tab }.distinct().size == 1
        }
        (uniqueDiscoveries - unambiguous.toSet()).forEach { reject(it.tab, "ambiguous_discovery", it.sessionId) }

        val affected = (uniqueDiscoveries.map { it.tab } + input.invalidatedTabs).toSet()
        // A stale member invalidates the complete transition, including an exchange partner.
        val stable = affected.map(::unchanged).all { it }
        val displaced = LinkedHashMap<String, T>()
        if (stable) {
            for ((id, tab) in input.ownership) {
                if (tab in input.invalidatedTabs || tabsByDiscovery[tab]?.let { claims ->
                        claims.isNotEmpty() && claims.none { it.sessionId == id }
                    } == true) displaced[id] = tab
            }
            for (discovery in unambiguous) {
                val session = sessions[discovery.sessionId]
                if (session == null) { reject(discovery.tab, "session_missing_from_index", discovery.sessionId); continue }
                if (!session.isLive && discovery.process == null) { reject(discovery.tab, "no_live_evidence", discovery.sessionId); continue }
                val existingTab = input.ownership[discovery.sessionId]
                if (existingTab != null && existingTab != discovery.tab && discovery.sessionId !in displaced) {
                    reject(discovery.tab, "session_owned_elsewhere", discovery.sessionId); continue
                }
                adopt[discovery.sessionId] = discovery.tab
                discovery.process?.let { observe[discovery.sessionId] = it }
            }
        }

        // Capture valid incoming handles before processing exits. A process can be replaced
        // between snapshots while retaining the same session id; an old exit result must not
        // release the replacement.
        val incomingObservations = LinkedHashMap(observe)
        for ((sessionId, process) in input.runningProcesses) {
            val tab = input.ownership[sessionId] ?: continue
            if (sessionId !in displaced && unchanged(tab)) incomingObservations[sessionId] = process
        }

        val release = LinkedHashSet<String>()
        val releaseTabs = LinkedHashMap<String, T>()
        val ended = LinkedHashMap<T, String>()
        for (sessionId in input.exited) {
            if (sessionId in displaced || sessionId in adopt) continue
            val before = input.observedBefore[sessionId] ?: continue
            // A missing current observation means explicit tracking cleared the old evidence (for
            // example a quick Resume); preserve ownership until a newer tick proves an exit.
            val current = input.observedNow[sessionId]
            // Identity comparison is intentional: a new process in the same tab must survive an
            // old process's exit result. Both values come from the same captured handle store.
            if (current !== before || incomingObservations[sessionId]?.let { it !== before } == true) {
                trace.event("exit_ignored", "session" to DiagnosticRef.of(sessionId), "reason" to "observation_replaced_or_cleared")
                continue
            }
            val tab = input.ownership[sessionId] ?: continue
            if (!unchanged(tab)) continue
            release += sessionId
            releaseTabs[sessionId] = tab
            ended[tab] = sessionId
        }

        // Existing running observations are accepted only while their tab generation and owner
        // still match the worker snapshot. This is the stale-result guard for same-tab reuse.
        for ((sessionId, process) in input.runningProcesses) {
            if (incomingObservations[sessionId] === process) observe[sessionId] = process
        }
        val running = LinkedHashSet<String>()
        // Raw running ids are only trustworthy when their ownership and tab generation still
        // match the worker snapshot. Otherwise a same-tab quick Resume can inherit stale state.
        for (sessionId in input.running) {
            val tab = input.ownership[sessionId] ?: continue
            if (sessionId !in displaced && unchanged(tab)) running += sessionId
        }
        running += observe.keys
        running.removeAll(release)
        observe.keys.removeAll(release)

        val effectiveOwnership = LinkedHashMap(input.ownership)
        for (sessionId in release + displaced.keys) effectiveOwnership.remove(sessionId)
        for ((sessionId, tab) in adopt) {
            effectiveOwnership.entries.removeAll { it.value == tab && it.key != sessionId }
            effectiveOwnership[sessionId] = tab
        }
        val retitle = ArrayList<TabRegistry.Retitle<T>>()
        for ((sessionId, tab) in effectiveOwnership) {
            if (sessionId in release) continue
            val session = sessions[sessionId] ?: continue
            val desired = input.displayTitle(session)
            if (input.tabTitles[tab] != desired) retitle += TabRegistry.Retitle(tab, desired)
        }
        running.retainAll(effectiveOwnership.keys)
        return TerminalReconcileResult(adopt, release, observe, ended, running, retitle, releaseTabs, displaced)
    }
}
