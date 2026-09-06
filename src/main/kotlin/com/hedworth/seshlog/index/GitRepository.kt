package com.hedworth.seshlog.index

import java.nio.file.Files
import java.nio.file.Path

/** Read-only Git layout inspection, called only while building background path snapshots. */
object GitRepository {
    fun commonDir(path: Path): Path? = try {
        findCommonDir(SessionFilter.canonical(path))
    } catch (_: Exception) { null }

    private fun findCommonDir(path: Path): Path? {
        var directory: Path? = path
        while (directory != null) {
            val marker = directory.resolve(".git")
            if (Files.isDirectory(marker)) return SessionFilter.canonical(marker)
            if (Files.isRegularFile(marker)) {
                val line = Files.readString(marker).lineSequence().firstOrNull()?.trim() ?: return null
                if (!line.startsWith("gitdir:")) return null
                val value = line.removePrefix("gitdir:").trim()
                if (value.isEmpty()) return null
                val gitDir = directory.resolve(value).normalize()
                val commonFile = gitDir.resolve("commondir")
                val common = if (Files.isRegularFile(commonFile)) {
                    val relative = Files.readString(commonFile).trim()
                    if (relative.isEmpty()) return null
                    gitDir.resolve(relative)
                } else gitDir
                return SessionFilter.canonical(common)
            }
            directory = directory.parent
        }
        return null
    }
}
