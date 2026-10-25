package com.hedworth.seshlog.index

import com.hedworth.seshlog.model.Checkout
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Files
import java.nio.file.Path

/** Read-only Git layout inspection, called only while building background path snapshots. */
object GitRepository {
    /** Reuse one inspector for a refresh: sibling sessions read each checkout's metadata once. */
    class Snapshot {
        private val roots = HashMap<Path, Checkout?>()
        private val ancestors = HashMap<Path, Checkout?>()

        fun resolve(path: Path, previous: Checkout? = null): Checkout? {
            val found = try {
                val canonical = SessionFilter.canonical(path)
                val lookup = if (Files.isDirectory(canonical)) canonical else previous?.root
                    ?.takeIf { canonical.startsWith(it) && Files.isDirectory(it) }
                lookup?.let(::find)?.takeUnless { canonical.startsWith(it.gitDir) }
            } catch (_: Exception) { null }
            // Do not reinterpret a removed nested worktree as its enclosing main checkout, or
            // silently retarget a directory replaced with a different repository.
            if (previous != null && (found == null || !previous.sameIdentity(found))) {
                return previous.unavailable("Checkout missing, unreadable or replaced")
            }
            return found
        }

        private fun find(path: Path): Checkout? {
            if (ancestors.containsKey(path)) return ancestors[path]
            val marker = path.resolve(".git")
            val result = when {
                Files.exists(marker) -> roots.getOrPut(path) { inspect(path, marker) }
                Files.isRegularFile(path.resolve("HEAD")) && Files.isDirectory(path.resolve("objects")) &&
                    Files.isDirectory(path.resolve("refs")) -> null // bare repository: stop at this boundary
                path.parent != null -> find(path.parent)
                else -> null
            }
            ancestors[path] = result
            return result
        }

        private fun inspect(root: Path, marker: Path): Checkout? {
            val gitDir = when {
                Files.isDirectory(marker) -> marker.toRealPath()
                Files.isRegularFile(marker) -> {
                    val value = smallText(marker)?.trim()?.takeIf { it.startsWith("gitdir:") }
                        ?.removePrefix("gitdir:")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                    root.resolve(value).toRealPath()
                }
                else -> return null
            }
            if (!Files.isDirectory(gitDir)) return null
            val commonFile = gitDir.resolve("commondir")
            val linked = Files.exists(commonFile)
            val common = if (linked) {
                val value = smallText(commonFile)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                gitDir.resolve(value).toRealPath().takeIf { Files.isDirectory(it) } ?: return null
            } else gitDir
            if (linked) {
                // A gitfile alone also describes a submodule or --separate-git-dir. Linked
                // checkouts have a private directory under common/worktrees and a backlink.
                if (gitDir.parent != common.resolve("worktrees") || common == gitDir) return null
                val backlink = smallText(gitDir.resolve("gitdir"))?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                if (SessionFilter.canonical(gitDir.resolve(backlink)) != marker.toRealPath()) return null
            }
            val head = smallText(gitDir.resolve("HEAD"))?.trim() ?: return null
            val branch = head.takeIf { it.startsWith("ref: refs/heads/") }?.removePrefix("ref: refs/heads/")
                ?.takeIf { it.isNotBlank() }
            val commit = head.takeIf { COMMIT.matches(it) }
            if (branch == null && commit == null) return null
            val key = Files.readAttributes(gitDir, BasicFileAttributes::class.java).fileKey()?.toString()
            return Checkout(root, gitDir, common, if (linked) Checkout.Kind.LINKED else Checkout.Kind.MAIN,
                branch, commit, directoryKey = key)
        }
    }

    fun checkout(path: Path, previous: Checkout? = null): Checkout? = Snapshot().resolve(path, previous)

    private val COMMIT = Regex("[0-9a-fA-F]{40,64}")
    private fun smallText(path: Path): String? = try {
        Files.newBufferedReader(path).use { reader ->
            val buffer = CharArray(4097)
            val count = reader.read(buffer)
            if (count < 0 || count > 4096) null else String(buffer, 0, count)
        }
    } catch (_: Exception) { null }

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
