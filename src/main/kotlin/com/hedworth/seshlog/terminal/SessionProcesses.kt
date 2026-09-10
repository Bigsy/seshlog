package com.hedworth.seshlog.terminal

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Capture before closing the terminal: its children may be reparented during disposal. */
object SessionProcesses {
    fun capture(pids: Collection<Long>): List<ProcessHandle> {
        val protected = ProcessHandle.current().let { current ->
            buildSet {
                add(current.pid())
                var parent = current.parent().orElse(null)
                while (parent != null) {
                    add(parent.pid())
                    parent = parent.parent().orElse(null)
                }
            }
        }
        return pids.filter { it > 1 && it !in protected }.flatMap { pid ->
            val root = ProcessHandle.of(pid).orElse(null) ?: return@flatMap emptyList()
            root.descendants().use { it.toList().asReversed() } + root
        }.distinctBy { it.pid() }.filter { it.pid() !in protected }
    }

    /** Run off the EDT. Report surviving PIDs rather than claiming a failed kill succeeded. */
    fun terminate(processes: List<ProcessHandle>): List<Long> {
        processes.filter { it.isAlive }.forEach { runCatching { it.destroy() } }
        awaitExit(processes)
        processes.filter { it.isAlive }.forEach { runCatching { it.destroyForcibly() } }
        awaitExit(processes)
        return processes.filter { it.isAlive }.map { it.pid() }
    }

    private fun awaitExit(processes: List<ProcessHandle>) {
        val exits = processes.filter { it.isAlive }.map { it.onExit() }
        try {
            CompletableFuture.allOf(*exits.toTypedArray()).get(2, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            // Escalate after the grace period, or report survivors after the forced kill.
        }
    }
}
