package com.hedworth.seshlog.index

import java.util.concurrent.atomic.AtomicLong

/** Owned by one panel: clearing or disposing it cannot cancel another panel's search. */
class SearchRequestScope {
    private val generation = AtomicLong()
    @Volatile private var disposed = false

    fun begin(): () -> Boolean {
        val request = generation.incrementAndGet()
        return { disposed || generation.get() != request }
    }

    fun cancel() { generation.incrementAndGet() }
    fun dispose() { disposed = true; cancel() }
}
