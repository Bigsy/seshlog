package com.hedworth.seshlog.cache

import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

fun interface CacheScheduledTask { fun cancel() }

fun interface CachePersistScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): CacheScheduledTask

    companion object {
        fun default(): CachePersistScheduler = CachePersistScheduler { delay, task ->
            val future = AppExecutorUtil.getAppScheduledExecutorService().schedule(task, delay, TimeUnit.MILLISECONDS)
            CacheScheduledTask { future.cancel(false) }
        }
    }
}
