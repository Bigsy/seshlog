package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.AgentKind

/** Builds a fresh-agent command from the configured executable and verbatim extra arguments. */
internal object NewSessionCommand {
    fun build(kind: AgentKind, executable: String, extraArgs: String): String {
        require(executable.isNotBlank()) { "Agent executable must not be blank" }
        // Keep this explicit per provider: a fresh session currently has no id/path argument,
        // while the provider-specific resume and fork commands do. New flags can be added here
        // without making UI actions know the command-line details.
        val base = when (kind) {
            AgentKind.CLAUDE_CODE, AgentKind.CODEX, AgentKind.OPENCODE, AgentKind.PI ->
                ShellQuote.quote(executable.trim())
        }
        return ExtraArguments.append(base, extraArgs)
    }
}

/**
 * A launch is resolved only from exact discovery for its own terminal tab. The caller supplies
 * [discoveredSessionIds] after PID/process-descriptor evidence has identified that tab; this class
 * intentionally has no cwd, timestamp, or global "first new session" fallback.
 */
internal class PendingSessionAssociations<T : Any>(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val timeoutMillis: Long = 60_000,
) {
    private data class Entry(val existingIds: Set<String>, val startedAt: Long)
    private val pending = LinkedHashMap<T, Entry>()

    fun mark(tab: T, existingSessionIds: Set<String>) {
        pending[tab] = Entry(existingSessionIds.toSet(), clock())
    }

    private fun purgeExpired() {
        val now = clock()
        pending.entries.removeIf { now - it.value.startedAt >= timeoutMillis }
    }

    val tabs: List<T> get() {
        purgeExpired()
        return pending.keys.toList()
    }

    fun isPending(tab: T): Boolean {
        purgeExpired()
        return tab in pending
    }

    fun candidate(tab: T, discoveredSessionIds: Set<String>): String? {
        purgeExpired()
        val existing = pending[tab]?.existingIds ?: return null
        return discoveredSessionIds.filterNot(existing::contains).singleOrNull()
    }

    /** Resolve one unobserved id from exact evidence for [tab]; ambiguity remains pending. */
    fun resolve(tab: T, discoveredSessionIds: Set<String>): String? {
        val id = candidate(tab, discoveredSessionIds) ?: return null
        pending.remove(tab)
        return id
    }

    fun forget(tab: T) {
        pending.remove(tab)
    }
}
