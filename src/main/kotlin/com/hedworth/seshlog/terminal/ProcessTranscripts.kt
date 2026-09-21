package com.hedworth.seshlog.terminal

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Exact writable file descriptors, never directory/mtime guesses. Runs only on workers. */
internal object ProcessTranscripts {
    fun writableFiles(pids: Set<Long>): Map<Long, Set<Path>> {
        if (pids.isEmpty()) return emptyMap()
        return if (Files.isDirectory(Path.of("/proc/self/fd"))) {
            pids.associateWith(::procFiles)
        } else lsofFiles(pids)
    }

    private fun procFiles(pid: Long): Set<Path> = try {
        Files.list(Path.of("/proc/$pid/fd")).use { descriptors ->
            descriptors.iterator().asSequence().mapNotNull { fd ->
                runCatching {
                    val flags = Files.readAllLines(Path.of("/proc/$pid/fdinfo/${fd.fileName}"))
                        .firstOrNull { it.startsWith("flags:") }?.substringAfter(':')?.trim()?.toLong(8)
                    if (flags == null || flags and 3L == 0L) null else Files.readSymbolicLink(fd)
                }.getOrNull()
            }.toSet()
        }
    } catch (_: Exception) { emptySet() }

    private fun lsofFiles(pids: Set<Long>): Map<Long, Set<Path>> {
        // Redirect output to avoid pipe deadlock and bound the entire external command.
        val output = runCatching { Files.createTempFile("seshlog-open-files-", ".tmp") }.getOrNull()
            ?: return emptyMap()
        var process: Process? = null
        return try {
            val executable = listOf("/usr/sbin/lsof", "/usr/bin/lsof").firstOrNull { Files.isExecutable(Path.of(it)) }
                ?: return emptyMap()
            process = ProcessBuilder(executable, "-nP", "-a", "-p", pids.joinToString(","), "-F0pafn")
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(output.toFile()).start()
            if (!process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() != 0 || Files.size(output) > 4 * 1024 * 1024) {
                emptyMap()
            } else parseLsof(Files.readString(output)).filterKeys { it in pids }
        } catch (_: Exception) { emptyMap() }
        finally {
            process?.destroyForcibly()
            Files.deleteIfExists(output)
        }
    }

    internal fun parseLsof(output: String): Map<Long, Set<Path>> {
        val files = linkedMapOf<Long, MutableSet<Path>>()
        var pid: Long? = null
        var writable = false
        for (field in output.split('\u0000')) {
            val value = field.trimStart('\n')
            when (value.firstOrNull()) {
                'p' -> { pid = value.drop(1).toLongOrNull(); writable = false }
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
