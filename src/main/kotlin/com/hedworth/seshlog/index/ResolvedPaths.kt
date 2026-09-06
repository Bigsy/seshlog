package com.hedworth.seshlog.index

import java.nio.file.Path

/** Immutable per-scan snapshot. Build on a worker; all lookups are filesystem-free. */
class ResolvedPaths private constructor(private val paths: Map<Path, Path>) {
    fun canonical(path: Path): Path = paths[path] ?: path.toAbsolutePath().normalize()

    fun isUnderAny(path: Path, roots: Collection<Path>): Boolean {
        val resolved = canonical(path)
        return roots.any { resolved.startsWith(canonical(it)) }
    }

    companion object {
        val EMPTY = ResolvedPaths(emptyMap())
        fun resolve(paths: Collection<Path>, resolver: (Path) -> Path = SessionFilter::canonical): ResolvedPaths =
            ResolvedPaths(paths.distinct().associateWith(resolver))
    }
}
