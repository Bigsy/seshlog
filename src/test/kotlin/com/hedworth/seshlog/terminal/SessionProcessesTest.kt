package com.hedworth.seshlog.terminal

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class SessionProcessesTest {
    @Test
    fun `never captures the IDE or its ancestors`() {
        assertTrue(SessionProcesses.capture(listOf(0, 1, ProcessHandle.current().pid())).isEmpty())
        ProcessHandle.current().parent().ifPresent {
            assertTrue(SessionProcesses.capture(listOf(it.pid())).isEmpty())
        }
    }

    @Test
    fun `stops captured children even after their shell exits`() {
        val marker = Files.createTempFile("seshlog-child", ".pid")
        val parent = ProcessBuilder("/bin/sh", "-c", "sleep 60 & echo $! > \"$1\"; wait", "sh", marker.toString()).start()
        var captured = emptyList<ProcessHandle>()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (Files.size(marker) == 0L && System.nanoTime() < deadline) Thread.sleep(10)
            val childPid = Files.readString(marker).trim().toLong()
            captured = SessionProcesses.capture(listOf(parent.pid(), childPid))
            assertEquals(setOf(parent.pid(), childPid), captured.map { it.pid() }.toSet())
            assertEquals(2, captured.size)
            parent.destroy()
            assertTrue(parent.waitFor(5, TimeUnit.SECONDS))
            assertTrue(SessionProcesses.terminate(captured).isEmpty())
            assertFalse(captured.any { it.isAlive })
        } finally {
            captured.filter { it.isAlive }.forEach { it.destroyForcibly() }
            parent.destroyForcibly()
            Files.deleteIfExists(marker)
        }
    }

    @Test
    fun `force kills a process that ignores graceful termination`() {
        val process = ProcessBuilder("/bin/sh", "-c", "trap '' TERM; echo ready; while :; do :; done").start()
        try {
            assertEquals("ready", process.inputStream.bufferedReader().readLine())
            assertTrue(SessionProcesses.terminate(SessionProcesses.capture(listOf(process.pid()))).isEmpty())
            assertFalse(process.isAlive)
        } finally {
            process.destroyForcibly()
        }
    }
}
