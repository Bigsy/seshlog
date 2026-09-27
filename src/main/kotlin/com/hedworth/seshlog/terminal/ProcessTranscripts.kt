package com.hedworth.seshlog.terminal

import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Exact writable file descriptors, never directory/mtime guesses. Runs only on workers. */
internal object ProcessTranscripts {
    private val log = logger<ProcessTranscripts>()

    /** A missing/failed observation is distinct from a successful empty descriptor list. */
    data class Result(val files: Map<Long, Set<Path>>, val unknown: Set<Long> = emptySet())

    fun writableFiles(pids: Set<Long>): Result {
        if (pids.isEmpty()) return Result(emptyMap())
        if (!Files.isDirectory(Path.of("/proc/self/fd"))) return lsofFiles(pids)
        val files = linkedMapOf<Long, Set<Path>>()
        val unknown = mutableSetOf<Long>()
        for (pid in pids) {
            val found = procFiles(pid)
            if (found == null) unknown += pid else files[pid] = found
        }
        return Result(files, unknown)
    }

    private fun procFiles(pid: Long): Set<Path>? = try {
        Files.list(Path.of("/proc/$pid/fd")).use { descriptors ->
            descriptors.iterator().asSequence().mapNotNull { fd ->
                val flags = Files.readAllLines(Path.of("/proc/$pid/fdinfo/${fd.fileName}"))
                    .firstOrNull { it.startsWith("flags:") }?.substringAfter(':')?.trim()?.toLong(8)
                    ?: error("Missing descriptor flags")
                if (flags and 3L == 0L) null else Files.readSymbolicLink(fd)
            }.toSet()
        }
    } catch (e: Exception) { log.debug("Cannot inspect writable process descriptors", e); null }

    private fun lsofFiles(pids: Set<Long>): Result {
        val unknown = Result(emptyMap(), pids)
        val output = try { Files.createTempFile("seshlog-open-files-", ".tmp") }
        catch (e: Exception) { log.debug("Cannot create lsof output", e); return unknown }
        var process: Process? = null
        return try {
            val executable = listOf("/usr/sbin/lsof", "/usr/bin/lsof").firstOrNull { Files.isExecutable(Path.of(it)) }
                ?: return unknown.also { log.debug("lsof unavailable") }
            process = ProcessBuilder(executable, "-nP", "-a", "-p", pids.joinToString(","), "-F0pafn")
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(output.toFile()).start()
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                log.debug("lsof timed out")
                unknown
            } else if (Files.size(output) > 4 * 1024 * 1024) {
                log.debug("lsof exceeded output limit")
                unknown
            } else resultFromLsof(pids, process.exitValue(), Files.readString(output))
        } catch (e: Exception) { log.debug("Cannot inspect writable files with lsof", e); unknown }
        finally {
            process?.destroyForcibly()
            try { Files.deleteIfExists(output) } catch (e: Exception) { log.debug("Cannot remove lsof output", e) }
        }
    }

    internal fun resultFromLsof(pids: Set<Long>, exit: Int, output: String): Result {
        if (exit !in 0..1) {
            log.debug("lsof failed with exit $exit")
            return Result(emptyMap(), pids)
        }
        val files = parseLsof(output).filterKeys { it in pids }
        // Exit 1 is normal when any requested PID disappeared; retain the other processes' data.
        return Result(files, if (exit == 1) pids - files.keys else emptySet())
    }

    internal fun parseLsof(output: String): Map<Long, Set<Path>> {
        val files = linkedMapOf<Long, MutableSet<Path>>()
        var pid: Long? = null
        var writable = false
        for (field in output.split('\u0000')) {
            val value = field.trimStart('\n')
            when (value.firstOrNull()) {
                'p' -> { pid = value.drop(1).toLongOrNull(); pid?.let { files.getOrPut(it) { linkedSetOf() } }; writable = false }
                'f' -> writable = false
                'a' -> writable = value.drop(1) in setOf("w", "u")
                'n' -> if (writable) {
                    val owner = pid ?: continue
                    val path = runCatching { Path.of(value.drop(1)) }.getOrNull() ?: continue
                    if (path.isAbsolute) files.getOrPut(owner) { linkedSetOf() }.add(path)
                }
            }
        }
        return files
    }
}
