package com.hedworth.seshlog.index

import java.nio.file.Path
import com.hedworth.seshlog.model.Checkout

/** Immutable per-scan snapshot. Build on a worker; all lookups are filesystem-free. */
class ResolvedPaths private constructor(private val paths: Map<Path, Path>, private val repositories: Map<Path, Path?>,
    private val checkouts: Map<Path, Checkout?> = emptyMap()) {
    fun retain(inputs: Set<Path>): ResolvedPaths = ResolvedPaths(paths.filterKeys { it in inputs },
        repositories.filterKeys { it in inputs }, checkouts.filterKeys { it in inputs })

    /** True when this snapshot has an entry for [path], including entries that resolved to itself. */
    fun contains(path: Path): Boolean = path in paths

    /** Combine independently resolved additions without touching the existing entries. */
    fun merge(other: ResolvedPaths): ResolvedPaths = ResolvedPaths(paths + other.paths, repositories + other.repositories, checkouts + other.checkouts)

    fun canonical(path: Path): Path = paths[path] ?: path.toAbsolutePath().normalize()

    fun repository(path: Path): Path? = checkouts[path]?.commonDir ?: repositories[path]

    fun checkout(path: Path): Checkout? = checkouts[path]

    fun inScope(path: Path, roots: Collection<Path>, includeWorktrees: Boolean): Boolean {
        val checkout = checkouts[path]
        return roots.any { root ->
            val rootCheckout = checkouts[root]
            if (checkout != null && rootCheckout != null && checkout.root != rootCheckout.root) {
                includeWorktrees && checkout.commonDir == rootCheckout.commonDir
            } else canonical(path).startsWith(canonical(root)) ||
                (includeWorktrees && repository(path) != null && repository(path) == repository(root))
        }
    }

    fun isUnderAny(path: Path, roots: Collection<Path>): Boolean {
        val resolved = canonical(path)
        return roots.any { resolved.startsWith(canonical(it)) }
    }

    fun belongsToRepository(path: Path, roots: Collection<Path>): Boolean {
        val repository = repository(path) ?: return false
        return roots.any { repository(it) == repository }
    }

    companion object {
        val EMPTY = ResolvedPaths(emptyMap(), emptyMap())
        fun resolve(
            paths: Collection<Path>,
            previous: ResolvedPaths = EMPTY,
            resolver: (Path) -> Path = SessionFilter::canonical,
            repositoryResolver: (Path) -> Path? = GitRepository::commonDir,
            refresh: Boolean = false,
            checkoutResolver: (Path, Checkout?) -> Checkout? = GitRepository.Snapshot()::resolve,
        ): ResolvedPaths {
            val unique = paths.distinct()
            val missing = if (refresh) unique else unique.filterNot { previous.paths.containsKey(it) }
            if (missing.isEmpty()) return previous
            val resolved = previous.paths.toMutableMap()
            val repositories = previous.repositories.toMutableMap()
            val checkouts = previous.checkouts.toMutableMap()
            missing.forEach { path ->
                resolved[path] = resolver(path)
                checkouts[path] = checkoutResolver(path, previous.checkouts[path])
                repositories[path] = checkouts[path]?.commonDir ?: repositoryResolver(path)
            }
            return ResolvedPaths(resolved, repositories, checkouts)
        }
    }
}
