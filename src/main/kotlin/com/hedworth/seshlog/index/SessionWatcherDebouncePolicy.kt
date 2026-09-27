package com.hedworth.seshlog.index

/**
 * Pure timing policy for [SessionWatcher]. A burst of events is quieted for [debounceMillis],
 * but a continuously active writer cannot postpone a scan beyond [maxWaitMillis].
 */
internal class SessionWatcherDebouncePolicy(
    private val clock: () -> Long = System::currentTimeMillis,
    private val debounceMillis: Long = 1_500,
    private val maxWaitMillis: Long = 5_000,
) {
    private var pendingSince: Long? = null
    private var lastEventAt: Long? = null

    /** Record an event and return the delay until the next scan is allowed. */
    @Synchronized
    fun event(): Long {
        val now = clock()
        if (pendingSince == null) pendingSince = now
        lastEventAt = now
        return delayUntilDue(now)
    }

    /** Return the delay for a scheduled alarm, or null when there is no pending burst. */
    @Synchronized
    fun delayUntilDue(): Long? {
        val now = clock()
        return if (pendingSince == null) null else delayUntilDue(now)
    }

    /** Consume the pending burst if its quiet or maximum-wait deadline has arrived. */
    @Synchronized
    fun consumeIfDue(): Boolean {
        val now = clock()
        if (pendingSince == null || delayUntilDue(now) > 0) return false
        pendingSince = null
        lastEventAt = null
        return true
    }

    private fun delayUntilDue(now: Long): Long {
        val first = requireNotNull(pendingSince)
        val last = requireNotNull(lastEventAt)
        val dueAt = minOf(last + debounceMillis, first + maxWaitMillis)
        return (dueAt - now).coerceAtLeast(0)
    }
}
