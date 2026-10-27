package com.hedworth.seshlog.worktree

import com.hedworth.seshlog.diagnostics.DiagnosticRef
import com.hedworth.seshlog.diagnostics.SessionDiagnostics
import com.hedworth.seshlog.index.GitRepository
import com.hedworth.seshlog.index.SessionFilter
import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.SeshlogSettings
import com.hedworth.seshlog.terminal.TerminalTabs
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** SDK effects; only capture/inspect methods access paths, and those are called on workers. */
object WorktreeProjects {
    private val pending = ConcurrentHashMap.newKeySet<Path>()

    fun projectSet(): Set<Project> = ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }.toSet()

    fun capture(): OpenProjectSnapshot<Project> {
        val projects = projectSet()
        val git = GitRepository.Snapshot()
        val directories = projects.mapNotNull { project ->
            project.basePath?.let { raw ->
                runCatching {
                    val path = SessionFilter.canonical(Path.of(raw))
                    project to path
                }.getOrNull()
            }
        }.toMap()
        val roots = directories.mapValues { (_, path) -> git.resolve(path)?.root ?: path }
        return OpenProjectSnapshot(projects, roots, directories)
    }

    fun protectedRoots(): Set<Path> {
        val settings = SeshlogSettings.getInstance()
        val home = Path.of(System.getProperty("user.home"))
        return (listOf(home.resolve(".claude"), home.resolve(".codex"), home.resolve(".pi/agent"),
            SeshlogSettings.defaultOpenCodeDataDir(emptyMap()), SeshlogSettings.defaultOpenCodeDataDir(),
            settings.resolvedClaudeDataDir(), settings.resolvedCodexDataDir(), settings.resolvedOpenCodeDataDir(),
            settings.resolvedPiSessionsDir()) +
            listOfNotNull(System.getenv("PI_CODING_AGENT_DIR")?.let { runCatching { Path.of(it) }.getOrNull() }))
            .mapTo(HashSet(), SessionFilter::canonical)
    }

    fun open(source: Project, session: Session) { open(source, session, forkSession = false) }

    fun openAndFork(source: Project, session: Session) { open(source, session, forkSession = true) }

    internal fun openFromSwitcher(source: Project, session: Session, allowed: (Session) -> Boolean) =
        open(source, session, forkSession = false, uiAllowed = allowed)

    /** Resume uses the same checkout/open/trust revalidation as a fork, with a caller-supplied final guard. */
    internal fun openAndResume(source: Project, session: Session, allowed: (Session) -> Boolean,
                               uiAllowed: (Session) -> Boolean,
                               resume: (Project, Session) -> Unit) =
        open(source, session, forkSession = false, resume = resume, allowed = allowed, uiAllowed = uiAllowed)

    private fun open(source: Project, session: Session, forkSession: Boolean,
                     resume: ((Project, Session) -> Unit)? = null, allowed: (Session) -> Boolean = { true },
                     uiAllowed: (Session) -> Boolean = { true }): Boolean {
        val app = ApplicationManager.getApplication()
        val index = SessionIndex.getInstance()
        val trace = SessionDiagnostics.trace("worktree", "request" to SessionDiagnostics.nextId(),
            "project" to DiagnosticRef.of(source.basePath), "session" to DiagnosticRef.of(session.id))
        return WorktreeOpening(
            current = { id -> index.sessions.firstOrNull { it.id == id && it.kind == session.kind } },
            refresh = {
                if (resume != null) index.requireFreshScan(session.kind) else index.scanNow()
                Unit
            },
            resolve = { path, previous -> GitRepository.checkout(path, previous) },
            projects = ::capture,
            projectSet = ::projectSet,
            storageProblem = { root ->
                val roots = protectedRoots()
                if (roots.any { root.startsWith(it) || SessionFilter.canonical(root.resolve(".idea")).startsWith(it) }) STORAGE_CONFLICT else null
            },
            focus = { ProjectUtil.focusProjectWindow(it, true) },
            open = { root ->
                ProjectUtil.openOrImport(root, OpenProjectTask {
                    forceOpenInNewFrame = true
                    // Keep default trust/import handling; never supply the source as projectToClose.
                })
            },
            worker = { task -> app.executeOnPooledThread(task) },
            ui = { task -> app.invokeLater(task) },
            active = { !source.isDisposed && !app.isDisposed },
            report = { message ->
                NotificationGroupManager.getInstance().getNotificationGroup("Seshlog")
                    .createNotification(message, NotificationType.WARNING).notify(source)
            },
            pending = pending,
            launch = if (forkSession) { destination, selected ->
                trace.event("worktree_fork_requested", "destination" to DiagnosticRef.of(destination.basePath),
                    "cwd" to DiagnosticRef.path(selected.cwd))
                TerminalTabs.fork(destination, selected, index.forkCommand(selected),
                    index.sessions.mapTo(HashSet()) { it.id }.also { it.add(selected.id) })
                Unit
            } else resume,
            launchVerb = if (forkSession) "fork" else "resume",
            allowed = allowed,
            uiAllowed = uiAllowed,
            launchProblem = { selected, checkout ->
                val directory = SessionFilter.canonical(selected.cwd)
                if (!Files.isDirectory(directory) || !directory.startsWith(checkout.root) ||
                    GitRepository.checkout(directory)?.let(checkout::sameIdentity) != true)
                    "The session's recorded directory is missing or no longer belongs to this worktree"
                else null
            },
            trace = trace,
        ).request(session)
    }
}
