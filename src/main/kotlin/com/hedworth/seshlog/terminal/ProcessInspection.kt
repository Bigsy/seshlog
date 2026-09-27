package com.hedworth.seshlog.terminal

import java.util.concurrent.atomic.AtomicBoolean

/** One inspection at a time, including EDT delivery; every failure releases the next tick. */
internal class ProcessInspection(
    private val background: (() -> Unit) -> Unit,
    private val ui: (() -> Unit) -> Unit,
    private val failure: (Throwable) -> Unit,
) {
    private val checking = AtomicBoolean()
    val isRunning: Boolean get() = checking.get()

    fun <T> run(inspect: () -> T, apply: (T) -> Unit) {
        if (!checking.compareAndSet(false, true)) return
        try {
            background {
                var delivered = false
                try {
                    val result = inspect()
                    ui {
                        try { apply(result) }
                        finally { checking.set(false) }
                    }
                    delivered = true
                } catch (t: Throwable) { failure(t) }
                finally { if (!delivered) checking.set(false) }
            }
        } catch (t: Throwable) {
            checking.set(false)
            failure(t)
        }
    }
}
