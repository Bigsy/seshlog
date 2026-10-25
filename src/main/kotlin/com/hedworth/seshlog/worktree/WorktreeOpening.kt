package com.hedworth.seshlog.worktree

import com.hedworth.seshlog.diagnostics.DiagnosticRef
import com.hedworth.seshlog.diagnostics.DiagnosticTrace
import com.hedworth.seshlog.model.Checkout
import com.hedworth.seshlog.model.Session
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

data class OpenProjectSnapshot<P>(val projects: Set<P>, val roots: Map<P, Path>)

data class WorktreeActionPresentation(val visible: Boolean, val enabled: Boolean, val text: String, val description: String) {
    companion object {
        fun of(session: Session?, openRoots: Collection<Path>, protectedRoots: Collection<Path>, forkSession: Boolean = false): WorktreeActionPresentation {
            val checkout = session?.checkout
            val focus = checkout?.root in openRoots
            val problem = when {
                checkout?.linked != true -> "No verified linked worktree"
                !checkout.available -> checkout.problem ?: "Worktree unavailable"
                session.location == null || session.location.uncertain -> "Location uncertain; refresh after the agent records its directory"
                forkSession && session.recordedDirectoryAvailable == false -> "The session's recorded directory is unavailable"
                !focus && protectedRoots.any { checkout.root.startsWith(it) } -> STORAGE_CONFLICT
                else -> null
            }
            return WorktreeActionPresentation(checkout?.linked == true, problem == null,
                if (forkSession) "Open Worktree and Fork Session" else if (focus) "Focus Worktree Window" else "Open Worktree in IntelliJ",
                problem ?: if (forkSession) "Open or focus the worktree and start an independent conversation fork in its terminal" else
                if (focus) "Focus the project for this session's recorded worktree" else
                    "Open the latest recorded worktree in a new window; keep this project and its terminals open")
        }
    }
}

const val STORAGE_CONFLICT = "Opening here would write IDE project files inside protected agent data; tracking and focusing existing windows remain available"

/** Pure orchestration. Resolve on a worker, deliver effects on the UI thread, coalesce by root. */
internal class WorktreeOpening<P : Any>(
    private val current: (String) -> Session?,
    private val refresh: () -> Unit,
    private val resolve: (Path, Checkout) -> Checkout?,
    private val projects: () -> OpenProjectSnapshot<P>,
    private val projectSet: () -> Set<P>,
    private val storageProblem: (Path) -> String?,
    private val focus: (P) -> Unit,
    private val open: (Path) -> P?,
    private val worker: (() -> Unit) -> Unit,
    private val ui: (() -> Unit) -> Unit,
    private val active: () -> Boolean,
    private val report: (String) -> Unit,
    private val pending: MutableSet<Path> = ConcurrentHashMap.newKeySet(),
    private val fork: ((P, Session) -> Unit)? = null,
    private val forkProblem: (Session, Checkout) -> String? = { _, _ -> null },
    private val trace: DiagnosticTrace = DiagnosticTrace.NONE,
) {
    fun request(selected: Session) {
        val checkout = selected.checkout ?: return
        if (!checkout.linked || !checkout.available || selected.location?.uncertain != false || !active()) return
        trace.event("worktree_requested", "session" to DiagnosticRef.of(selected.id),
            "root" to DiagnosticRef.path(checkout.root), "fork" to (fork != null))
        if (!pending.add(checkout.root)) {
            trace.event("worktree_coalesced")
            return
        }
        validate(selected, checkout, 0)
    }

    private fun unchanged(selected: Session, checkout: Checkout): Boolean {
        val latest = current(selected.id) ?: return false
        return active() && latest.kind == selected.kind && latest.cwd == selected.cwd && latest.location == selected.location &&
            latest.location?.uncertain == false && latest.checkout?.let { it.available && checkout.sameIdentity(it) } == true
    }

    private fun validate(selected: Session, checkout: Checkout, attempt: Int) {
        try {
            worker {
                try {
                    if (!active()) { pending.remove(checkout.root); return@worker }
                    if (attempt == 0) refresh()
                    check(unchanged(selected, checkout)) { "Session location changed; select it again after refresh" }
                    // Validate the checkout root, retaining the recorded subdirectory for other actions.
                    val verified = resolve(checkout.root, checkout)
                    check(verified?.available == true && checkout.sameIdentity(verified)) { "Worktree is missing, unreadable or replaced" }
                    if (fork != null) forkProblem(selected, checkout)?.let { error(it) }
                    val snapshot = projects()
                    val blocked = storageProblem(checkout.root)
                    ui {
                        if (!active()) { pending.remove(checkout.root); return@ui }
                        if (!unchanged(selected, checkout)) {
                            finish(checkout) { report("Session location changed while validating the worktree") }
                        } else if (snapshot.projects != projectSet()) {
                            // A project opened/closed during validation. Resolve aliases again before
                            // deciding to create a new window; never use a stale open-project list.
                            if (attempt < 3) validate(selected, checkout, attempt + 1)
                            else finish(checkout) { report("Open projects changed; please try again") }
                        } else {
                            val existing = snapshot.roots.entries.firstOrNull { it.value == checkout.root }?.key
                            try {
                                if (existing == null && blocked != null) {
                                    finish(checkout) { report(blocked) }
                                } else {
                                    val destination = if (existing != null) existing.also(focus) else open(checkout.root)
                                    when {
                                        destination == null -> finish(checkout) { report("Worktree opening was cancelled or did not complete") }
                                        fork == null -> finish(checkout) {}
                                        else -> validateFork(selected, checkout, destination)
                                    }
                                    trace.event("worktree_open_result", "existing" to (existing != null), "opened" to (destination != null))
                                }
                            } catch (e: Exception) {
                                fail(checkout, e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    fail(checkout, e)
                }
            }
        } catch (e: Exception) { fail(checkout, e) }
    }

    /** Opening can run a modal trust/import flow. Check again before starting a new process. */
    private fun validateFork(selected: Session, checkout: Checkout, destination: P) {
        try {
            worker {
                try {
                    if (!active()) { pending.remove(checkout.root); return@worker }
                    refresh()
                    check(unchanged(selected, checkout)) { "Session location changed; select it again after refresh" }
                    val verified = resolve(checkout.root, checkout)
                    check(verified?.available == true && checkout.sameIdentity(verified)) { "Worktree is missing, unreadable or replaced" }
                    forkProblem(selected, checkout)?.let { error(it) }
                    val snapshot = projects()
                    check(destination in snapshot.projects && snapshot.roots[destination] == checkout.root) {
                        "The worktree window closed or changed before the session could be forked"
                    }
                    ui {
                        finish(checkout) {
                            if (!active()) return@finish
                            check(destination in projectSet()) { "The worktree window closed before the session could be forked" }
                            check(unchanged(selected, checkout)) { "Session location changed before the session could be forked" }
                            fork?.invoke(destination, selected)
                            trace.event("worktree_fork_launched")
                        }
                    }
                } catch (e: Exception) { fail(checkout, e) }
            }
        } catch (e: Exception) { fail(checkout, e) }
    }

    private fun finish(checkout: Checkout, effect: () -> Unit) {
        try { effect() }
        catch (e: Exception) {
            trace.failure("worktree_failed", e)
            if (active()) report("Could not ${if (fork != null) "fork session in worktree" else "open worktree"}: ${e.message ?: "cancelled"}")
        }
        finally { pending.remove(checkout.root) }
    }

    private fun fail(checkout: Checkout, error: Exception) {
        trace.failure("worktree_failed", error)
        try { ui { finish(checkout) { if (active()) report(error.message ?: "Worktree opening cancelled") } } }
        catch (_: Exception) { pending.remove(checkout.root) }
    }
}
