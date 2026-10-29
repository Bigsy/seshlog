package com.hedworth.seshlog.worktree

import com.hedworth.seshlog.diagnostics.DiagnosticRef
import com.hedworth.seshlog.diagnostics.DiagnosticTrace
import com.hedworth.seshlog.index.ResolvedPaths
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Checkout
import com.hedworth.seshlog.model.Session
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Choices from the confirmation dialog. */
data class WorktreeDeletionOptions(val hideSessions: Boolean, val deleteBranch: Boolean)

data class GitResult(val exitCode: Int, val output: String)

/** Only a plain branch name is passed to `git branch -d`; never something git could read as an option. */
val Checkout.deletableBranch: String? get() = branch?.takeUnless { it.startsWith("-") }

/** Sessions recorded anywhere in [checkout]. Directories without a resolved checkout fall back to the path. */
internal fun worktreeSessions(checkout: Checkout, sessions: List<Session>, paths: ResolvedPaths): List<Session> =
    sessions.filter { session ->
        val resolved = session.checkout ?: paths.checkout(session.cwd)
        resolved?.sameIdentity(checkout) ?: paths.canonical(session.cwd).startsWith(checkout.root)
    }

/** Why [checkout] cannot be deleted now, or null. Filesystem-free; callers supply the evidence. */
internal fun deletionProblem(checkout: Checkout, sessions: List<Session>, associated: Set<String>,
                             openDirectories: Collection<Path>, protectedRoots: Collection<Path>): String? = when {
    !checkout.linked -> "Only linked worktrees can be deleted"
    !checkout.available -> checkout.problem ?: "Worktree unavailable"
    protectedRoots.any { protected ->
        listOf(checkout.root, checkout.gitDir, checkout.commonDir).any {
            it.startsWith(protected) || protected.startsWith(it)
        }
    } -> "This worktree or its Git metadata overlaps an agent's data folder; remove it with that agent"
    openDirectories.any { it.startsWith(checkout.root) } -> "This worktree is open in an IntelliJ window; close that window first"
    sessions.any { it.isLive || it.id in associated } ->
        "A session in this worktree is running or has a terminal tab; stop it or close the tab first"
    else -> null
}

data class WorktreeDeletionPresentation(val visible: Boolean, val enabled: Boolean, val description: String) {
    companion object {
        fun of(checkout: Checkout?, sessions: List<Session>, associated: Set<String>,
               openDirectories: Collection<Path>, protectedRoots: Collection<Path>): WorktreeDeletionPresentation {
            if (checkout?.linked != true) return WorktreeDeletionPresentation(false, false, "")
            val problem = deletionProblem(checkout, sessions, associated, openDirectories, protectedRoots)
            return WorktreeDeletionPresentation(true, problem == null,
                problem ?: "Remove this worktree's folder with git; its conversations are kept")
        }
    }
}

/**
 * Pure orchestration, like [WorktreeOpening]: refresh and verify on a worker, recheck terminals and
 * windows on the UI thread, then let git remove the worktree. Git refuses uncommitted or untracked
 * changes and locked worktrees itself; this never forces. Shares the per-root pending set with opening.
 */
internal class WorktreeDeletion<P : Any>(
    private val refresh: (Set<AgentKind>) -> Unit,
    private val sessions: (Checkout) -> List<Session>,
    private val resolve: (Path, Checkout) -> Checkout?,
    private val projects: () -> OpenProjectSnapshot<P>,
    private val projectSet: () -> Set<P>,
    private val associated: () -> Set<String>,
    private val protectedRoots: () -> Collection<Path>,
    private val git: (Path, List<String>) -> GitResult,
    private val hide: (Set<String>) -> Unit,
    private val rescan: () -> Unit,
    private val worker: (() -> Unit) -> Unit,
    private val ui: (() -> Unit) -> Unit,
    private val active: () -> Boolean,
    private val report: (String) -> Unit,
    private val done: (String) -> Unit,
    private val pending: MutableSet<Path> = ConcurrentHashMap.newKeySet(),
    private val trace: DiagnosticTrace = DiagnosticTrace.NONE,
) {
    fun request(checkout: Checkout, options: WorktreeDeletionOptions): Boolean {
        if (!checkout.linked || !checkout.available || !active()) return false
        trace.event("worktree_delete_requested", "root" to DiagnosticRef.path(checkout.root),
            "hide" to options.hideSessions, "branch" to options.deleteBranch)
        if (!pending.add(checkout.root)) {
            trace.event("worktree_coalesced")
            report("Another operation is already using this worktree; please try again")
            return false
        }
        step(checkout) {
            val before = sessions(checkout)
            // Liveness comes from a completed, healthy scan of every agent with sessions here.
            refresh(before.mapTo(HashSet()) { it.kind })
            val verified = resolve(checkout.root, checkout)
            check(verified != null && verified.available && verified.linked && checkout.sameIdentity(verified)) {
                "Worktree is missing, unreadable or replaced"
            }
            check(!options.deleteBranch || verified.branch == checkout.branch) { "The worktree's branch changed; select it again" }
            val inside = sessions(verified)
            check(inside.all { session -> before.any { it.kind == session.kind } }) { "Sessions in this worktree changed; please try again" }
            val snapshot = projects()
            val protected = protectedRoots()
            ui {
                if (!active()) { pending.remove(checkout.root); return@ui }
                val problem = deletionProblem(verified, sessions(verified), associated(), snapshot.directories.values, protected)
                    ?: if (snapshot.projects != projectSet()) "Open projects changed; please try again" else null
                if (problem != null) finish(checkout) { report(problem) } else remove(verified, inside, options)
            }
        }
        return true
    }

    private fun remove(checkout: Checkout, inside: List<Session>, options: WorktreeDeletionOptions) = step(checkout) {
        // The worker may have queued behind other work after UI approval. Revalidate here, with
        // no further scheduling boundary before git. This cannot lock out external Git/CLI users.
        val kinds = inside.mapTo(HashSet()) { it.kind }
        refresh(kinds)
        val verified = resolve(checkout.root, checkout)
        check(verified != null && verified.available && verified.linked && checkout.sameIdentity(verified)) {
            "Worktree is missing, unreadable or replaced"
        }
        check(!options.deleteBranch || verified.branch == checkout.branch) { "The worktree's branch changed; select it again" }
        val snapshot = projects()
        val protected = protectedRoots()
        val latest = sessions(verified)
        check(latest.all { it.kind in kinds }) { "Sessions in this worktree changed; please try again" }
        check(snapshot.projects == projectSet()) { "Open projects changed; please try again" }
        deletionProblem(verified, latest, associated(), snapshot.directories.values, protected)?.let { error(it) }
        val repository = "--git-dir=${checkout.commonDir}"
        val removed = git(checkout.commonDir, listOf(repository, "worktree", "remove", checkout.root.toString()))
        trace.event("worktree_delete_result", "exit" to removed.exitCode)
        check(removed.exitCode == 0) { gitMessage(removed) }
        val branchNote = checkout.deletableBranch?.takeIf { options.deleteBranch }?.let { branch ->
            val deleted = git(checkout.commonDir, listOf(repository, "branch", "-d", branch))
            trace.event("worktree_branch_delete_result", "exit" to deleted.exitCode)
            if (deleted.exitCode == 0) "; deleted branch $branch" else "; kept branch $branch: ${gitMessage(deleted)}"
        }.orEmpty()
        ui {
            // The folder is gone: hide and rescan even if the requesting window closed meanwhile.
            finish(checkout) {
                if (options.hideSessions) hide(latest.mapTo(HashSet()) { it.id })
                rescan()
                if (active()) done("Deleted worktree ${checkout.root.fileName}$branchNote")
            }
        }
    }

    private fun step(checkout: Checkout, body: () -> Unit) {
        try {
            worker {
                try {
                    if (!active()) { pending.remove(checkout.root); return@worker }
                    body()
                } catch (e: Exception) { fail(checkout, e) }
            }
        } catch (e: Exception) { fail(checkout, e) }
    }

    private fun finish(checkout: Checkout, effect: () -> Unit) {
        try { effect() }
        catch (e: Exception) {
            trace.failure("worktree_delete_failed", e)
            if (active()) report("Could not delete worktree: ${e.message ?: "cancelled"}")
        }
        finally { pending.remove(checkout.root) }
    }

    private fun fail(checkout: Checkout, error: Exception) {
        trace.failure("worktree_delete_failed", error)
        try { ui { finish(checkout) { if (active()) report("Could not delete worktree: ${error.message ?: "cancelled"}") } } }
        catch (_: Exception) { pending.remove(checkout.root) }
    }

    private fun gitMessage(result: GitResult): String =
        result.output.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() && !it.startsWith("hint:") }
            ?.removePrefix("fatal: ")?.removePrefix("error: ") ?: "git exited with ${result.exitCode}"
}
