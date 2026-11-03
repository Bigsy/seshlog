package com.hedworth.seshlog.terminal

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds a configured agent executable the way a terminal shell would for a plain command: a bare
 * name is searched on PATH, an absolute or `~/` path is checked directly. Shell aliases and
 * functions are invisible here, so callers treat "not found" as "not offered", never as an error.
 */
internal object AgentExecutables {
    fun locate(
        executable: String,
        pathValue: String?,
        windows: Boolean = SystemInfo.isWindows,
        windowsExtensions: () -> List<String> = PathEnvironmentVariableUtil::getWindowsExecutableFileExtensions,
        home: String = System.getProperty("user.home"),
    ): Path? {
        val trimmed = executable.trim()
        if (trimmed.isEmpty()) return null
        val name = if (trimmed == "~" || trimmed.startsWith("~/")) home + trimmed.substring(1) else trimmed
        // On Windows a bare name may omit its extension (claude -> claude.cmd), as the shell allows.
        fun variants(base: Path): List<Path> =
            if (windows) listOf(base) + windowsExtensions().map { base.resolveSibling(base.fileName.toString() + it) }
            else listOf(base)
        val separated = '/' in name || (windows && '\\' in name)
        if (separated) {
            // A relative path would resolve against the new tab's directory, which is unknown here.
            val path = parse(name)?.takeIf { it.isAbsolute } ?: return null
            return variants(path).firstOrNull(::isExecutableFile)
        }
        if (pathValue.isNullOrBlank()) return null
        return PathEnvironmentVariableUtil.getPathDirs(pathValue).asSequence()
            .mapNotNull { dir -> try { Path.of(dir, name) } catch (_: InvalidPathException) { null } }
            .flatMap { variants(it) }
            .firstOrNull(::isExecutableFile)
    }

    private fun parse(value: String): Path? = try { Path.of(value) } catch (_: InvalidPathException) { null }

    private fun isExecutableFile(path: Path): Boolean = try {
        Files.isRegularFile(path) && Files.isExecutable(path)
    } catch (_: SecurityException) {
        false
    }
}

/**
 * Short-lived cache of [AgentExecutables.locate] results, keyed by the configured executable, so
 * menu updates stay cheap while an agent installed after IDE startup still appears within [ttlMillis].
 */
internal class AgentAvailability(
    private val finder: (String) -> Path? = { AgentExecutables.locate(it, PathEnvironmentVariableUtil.getPathVariableValue()) },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ttlMillis: Long = 30_000,
) {
    private data class Entry(val path: Path?, val checkedAt: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    fun locate(executable: String): Path? {
        val now = clock()
        cache[executable]?.takeIf { now - it.checkedAt < ttlMillis }?.let { return it.path }
        val found = finder(executable)
        cache[executable] = Entry(found, now)
        return found
    }

    companion object {
        val shared = AgentAvailability()
    }
}
