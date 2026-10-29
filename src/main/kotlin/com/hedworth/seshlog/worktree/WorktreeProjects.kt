package com.hedworth.seshlog.worktree

import com.hedworth.seshlog.diagnostics.DiagnosticRef
import com.hedworth.seshlog.diagnostics.SessionDiagnostics
import com.hedworth.seshlog.index.GitRepository
import com.hedworth.seshlog.index.SessionFilter
import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.Checkout
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.SeshlogSettings
import com.hedworth.seshlog.settings.SessionOrganisation
import com.hedworth.seshlog.terminal.OwnedTerminalTabs
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

    internal fun createAndFork(source: Project, session: Session, checkout: Checkout, options: WorktreeCreationOptions) {
        val app = ApplicationManager.getApplication()
        val index = SessionIndex.getInstance()
        val destination = options.destination.toAbsolutePath().normalize()
        fun report(message: String) = NotificationGroupManager.getInstance().getNotificationGroup("Seshlog")
            .createNotification(message, NotificationType.WARNING).notify(source)
        if (!pending.add(checkout.root)) { report("Another operation is using the source checkout; try again"); return }
        if (!pending.add(destination)) {
            pending.remove(checkout.root)
            report("Another operation is using the destination; try again")
            return
        }
        fun current(): Session {
            val latest = index.sessionById(session.id)
            check(latest != null && latest.kind == session.kind && latest.cwd == session.cwd &&
                latest.startedAt == session.startedAt && latest.transcriptPath == session.transcriptPath &&
                latest.location?.uncertain != true) { "Source session changed; select it again" }
            return latest
        }
        WorktreeCreation(
            create = {
                index.requireFreshScan(session.kind)
                current()
                check(index.providerFor(session).forkInDirectoryCommand(session, destination) != null) { "This agent does not support forks into another checkout" }
                WorktreeCreator.create(session, checkout, options, protectedRoots())
            },
            validate = { created ->
                index.requireFreshScan(session.kind)
                current()
                WorktreeReview.verify(created.checkout)
                check(protectedRoots().none { created.checkout.root.startsWith(it) || it.startsWith(created.checkout.root) ||
                    SessionFilter.canonical(created.checkout.root.resolve(".idea")).startsWith(it) }) { "Created checkout overlaps protected storage" }
                val directory = SessionFilter.canonical(created.directory)
                check(Files.isDirectory(directory) && directory.startsWith(created.checkout.root) &&
                    GitRepository.checkout(directory)?.let(created.checkout::sameIdentity) == true) { "Destination directory changed" }
            },
            open = { created ->
                // The creator verified the new checkout on its worker; opening retains default trust handling.
                ProjectUtil.openOrImport(created.checkout.root, OpenProjectTask { forceOpenInNewFrame = true })
            },
            destinationValid = { project, created -> capture().roots[project] == created.checkout.root },
            launch = { project, created ->
                check(!project.isDisposed && project in projectSet()) { "Destination window closed" }
                val latest = current()
                val command = requireNotNull(index.providerFor(latest).forkInDirectoryCommand(latest, created.directory))
                TerminalTabs.fork(project, latest.copy(cwd = created.directory, checkout = created.checkout), command,
                    index.sessions.mapTo(HashSet()) { it.id }.also { it.add(session.id) })
                index.refresh(forcePathResolution = true)
            },
            worker = { task -> app.executeOnPooledThread(task) },
            ui = { task -> app.invokeLater(task) },
            active = { !source.isDisposed && !app.isDisposed },
            report = ::report,
            finished = { pending.remove(checkout.root); pending.remove(destination) },
        ).start()
    }

    /** Remove a linked worktree with git after revalidating it; never forces. */
    fun delete(source: Project, checkout: Checkout, options: WorktreeDeletionOptions): Boolean {
        val app = ApplicationManager.getApplication()
        val index = SessionIndex.getInstance()
        val notifications = NotificationGroupManager.getInstance().getNotificationGroup("Seshlog")
        val trace = SessionDiagnostics.trace("worktree", "request" to SessionDiagnostics.nextId(),
            "project" to DiagnosticRef.of(source.basePath), "root" to DiagnosticRef.path(checkout.root))
        return WorktreeDeletion(
            refresh = { kinds -> index.requireFreshScan(kinds) },
            sessions = { worktreeSessions(it, index.sessions, index.resolvedPaths) },
            resolve = { path, previous -> GitRepository.checkout(path, previous) },
            projects = ::capture,
            projectSet = ::projectSet,
            associated = { projectSet().flatMapTo(HashSet()) { OwnedTerminalTabs.getInstance(it).sessionIds } },
            protectedRoots = ::protectedRoots,
            git = { directory, args -> GitCommand.run(GitCommand.executable(), directory, args) },
            hide = { ids -> SessionOrganisation.getInstance().editAll(ids) { it.hidden = true } },
            rescan = { index.refresh(forcePathResolution = true) },
            worker = { task -> app.executeOnPooledThread(task) },
            ui = { task -> app.invokeLater(task) },
            active = { !source.isDisposed && !app.isDisposed },
            report = { message -> notifications.createNotification(message, NotificationType.WARNING).notify(source) },
            done = { message -> notifications.createNotification(message, NotificationType.INFORMATION).notify(source) },
            pending = pending,
            trace = trace,
        ).request(checkout, options)
    }

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
