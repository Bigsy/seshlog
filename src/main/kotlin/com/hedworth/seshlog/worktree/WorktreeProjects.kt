package com.hedworth.seshlog.worktree

import com.hedworth.seshlog.index.GitRepository
import com.hedworth.seshlog.index.SessionFilter
import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.SeshlogSettings
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** SDK effects; only capture/inspect methods access paths, and those are called on workers. */
object WorktreeProjects {
    private val pending = ConcurrentHashMap.newKeySet<Path>()

    fun projectSet(): Set<Project> = ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }.toSet()

    fun capture(): OpenProjectSnapshot<Project> {
        val projects = projectSet()
        val git = GitRepository.Snapshot()
        val roots = projects.mapNotNull { project ->
            project.basePath?.let { raw ->
                runCatching {
                    val path = SessionFilter.canonical(Path.of(raw))
                    project to (git.resolve(path)?.root ?: path)
                }.getOrNull()
            }
        }.toMap()
        return OpenProjectSnapshot(projects, roots)
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

    fun open(source: Project, session: Session) {
        val app = ApplicationManager.getApplication()
        val index = SessionIndex.getInstance()
        WorktreeOpening(
            current = index::sessionById,
            refresh = { index.scanNow() },
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
                }) != null
            },
            worker = { task -> app.executeOnPooledThread(task) },
            ui = { task -> app.invokeLater(task) },
            active = { !source.isDisposed && !app.isDisposed },
            report = { message ->
                NotificationGroupManager.getInstance().getNotificationGroup("Seshlog")
                    .createNotification(message, NotificationType.WARNING).notify(source)
            },
            pending = pending,
        ).request(session)
    }
}
