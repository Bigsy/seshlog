package com.hedworth.seshlog.terminal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EndedSessionsTest {
    @Test fun `background action reads coexist with EDT writes and weak key cleanup`() {
        val history = EndedSessions<Any>()
        val tabs = List(50) { Any() }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0..3).map { worker -> pool.submit {
                start.await()
                repeat(10_000) { i ->
                    val tab = tabs[i % tabs.size]
                    if (worker == 0) { history[tab] = "s$i"; if (i % 3 == 0) history.remove(tab) }
                    else history[tab]
                }
            } }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            history[tabs[0]] = "final"
            assertEquals("final", history[tabs[0]])
        } finally { pool.shutdownNow() }
    }
}
