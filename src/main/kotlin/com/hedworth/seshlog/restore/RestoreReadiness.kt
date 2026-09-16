package com.hedworth.seshlog.restore

/** Non-blocking, bounded wait. A timeout never grants permission to launch a duplicate tab. */
internal class RestoreReadiness(
    private val later: (() -> Unit) -> Unit,
    private val cancelled: () -> Boolean,
    private val now: () -> Long = System::nanoTime,
    private val timeoutNanos: Long = 15_000_000_000L,
) {
    fun await(ready: () -> Boolean, launch: () -> Unit, timeout: () -> Unit) {
        val started = now()
        fun poll() {
            if (cancelled()) return
            if (ready()) launch()
            else if (now() - started >= timeoutNanos) timeout()
            else later(::poll)
        }
        poll()
    }
}
