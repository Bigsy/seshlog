package com.hedworth.seshlog.index

import com.hedworth.seshlog.model.Session
import java.nio.file.Files
import java.nio.file.Path

/** Pure filtering logic, kept free of IntelliJ types so it is unit-testable. */
object SessionFilter {

    /**
     * Canonical form: symlinks resolved via the deepest existing ancestor (the path itself may not
     * exist any more — sessions outlive their directories), remainder appended unchanged.
     */
    fun canonical(path: Path): Path {
        val abs = path.toAbsolutePath().normalize()
        var existing: Path? = abs
        while (existing != null && !Files.exists(existing)) existing = existing.parent
        if (existing == null) return abs
        val real = try {
            existing.toRealPath()
        } catch (_: Exception) {
            return abs
        }
        return if (existing == abs) real else real.resolve(existing.relativize(abs))
    }

    /** True when [cwd] equals or is inside any of [roots]. Both sides are canonicalised. */
    fun isUnderAny(cwd: Path, roots: Collection<Path>): Boolean {
        val c = canonical(cwd)
        return roots.any { root -> c.startsWith(canonical(root)) }
    }

    fun belongsToProject(session: Session, projectRoots: Collection<Path>): Boolean =
        isUnderAny(session.cwd, projectRoots)

    /** Hide aborted starts: no explicit title and fewer than [minPrompts] real prompts. */
    fun isWorthShowing(session: Session, minPrompts: Int): Boolean =
        session.hasExplicitTitle || session.isLive || session.promptCount >= minPrompts
}
