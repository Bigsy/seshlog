package com.hedworth.seshlog.index

/**
 * Serializes replacement of an external watch registration while making stale concurrent starts
 * harmless. The replacement/removal functions are injected so the ordering policy stays pure and
 * deterministic in tests.
 */
internal class WatchRootRegistration<T : Any>(
    private val replace: (old: Set<T>, paths: Set<String>) -> Set<T>,
    private val remove: (requests: Set<T>) -> Unit,
) {
    data class Request internal constructor(val sequence: Long, val paths: Set<String>)

    private val lock = Any()
    private var nextSequence = 0L
    private var latestSequence = 0L
    private var installed: Set<T> = emptySet()

    fun request(paths: Set<String>): Request = synchronized(lock) {
        val request = Request(++nextSequence, paths.toSet())
        latestSequence = request.sequence
        request
    }

    fun isLatest(request: Request): Boolean = synchronized(lock) { request.sequence == latestSequence }

    /** Install only the newest request; returns false when a later start superseded it. */
    fun install(request: Request): Boolean = synchronized(lock) {
        if (request.sequence != latestSequence) return@synchronized false
        installed = replace(installed, request.paths)
        true
    }

    fun dispose() = synchronized(lock) {
        latestSequence = ++nextSequence
        remove(installed)
        installed = emptySet()
    }
}
