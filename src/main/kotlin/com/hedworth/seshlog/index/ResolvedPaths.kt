package com.hedworth.seshlog.index

import java.nio.file.Path

/** Immutable per-scan snapshot. Build on a worker; all lookups are filesystem-free. */
class ResolvedPaths private constructor(private val paths: Map<Path, Path>, private val repositories: Map<Path, Path?>) {
    /** True when this snapshot has an entry for [path], including entries that resolved to itself. */
    fun contains(path: Path): Boolean = path in paths

    /** Combine independently resolved additions without touching the existing entries. */
    fun merge(other: ResolvedPaths): ResolvedPaths = ResolvedPaths(paths + other.paths, repositories + other.repositories)

    fun canonical(path: Path): Path = paths[path] ?: path.toAbsolutePath().normalize()

    fun repository(path: Path): Path? = repositories[path]

    fun isUnderAny(path: Path, roots: Collection<Path>): Boolean {
        val resolved = canonical(path)
        return roots.any { resolved.startsWith(canonical(it)) }
    }

    fun belongsToRepository(path: Path, roots: Collection<Path>): Boolean {
        val repository = repositories[path] ?: return false
        return roots.any { repositories[it] == repository }
    }

    companion object {
        val EMPTY = ResolvedPaths(emptyMap(), emptyMap())
        fun resolve(
            paths: Collection<Path>,
            previous: ResolvedPaths = EMPTY,
            resolver: (Path) -> Path = SessionFilter::canonical,
            repositoryResolver: (Path) -> Path? = GitRepository::commonDir,
        ): ResolvedPaths {
            val unique = paths.distinct()
            val missing = unique.filterNot { previous.paths.containsKey(it) }
            if (missing.isEmpty()) return previous
            val resolved = previous.paths.toMutableMap()
            val repositories = previous.repositories.toMutableMap()
            missing.forEach { path ->
                resolved[path] = resolver(path)
                repositories[path] = repositoryResolver(path)
            }
            return ResolvedPaths(resolved, repositories)
        }
    }
}
