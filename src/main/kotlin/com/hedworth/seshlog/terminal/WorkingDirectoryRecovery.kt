package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.settings.SessionOrganisation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Pure directory choice policy; checks run on a worker, never from a renderer. */
object WorkingDirectoryChoice {
    fun available(original: Path, replacement: String, isDirectory: (Path) -> Boolean = { Files.isDirectory(it) }): Path? {
        if (replacement.isNotBlank()) {
            val path = try { Paths.get(replacement) } catch (_: Exception) { null }
            if (path != null && isDirectory(path)) return path
        }
        return original.takeIf(isDirectory)
    }
}

object WorkingDirectoryRecovery {
    fun run(project: Project, session: Session, launch: (Session) -> Unit) {
        val organisation = SessionOrganisation.getInstance()
        val replacement = organisation.metadata(session.id).replacementDirectory
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val directory = WorkingDirectoryChoice.available(session.cwd, replacement)
            app.invokeLater {
                if (project.isDisposed) return@invokeLater
                if (directory != null) {
                    if (directory != session.cwd) {
                        NotificationGroupManager.getInstance().getNotificationGroup("Seshlog")
                            .createNotification("Using replacement directory $directory for '${organisation.title(session)}' (original: ${session.cwd})", NotificationType.INFORMATION)
                            .notify(project)
                    }
                    launch(session.copy(cwd = directory))
                } else {
                    val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
                        title = "Choose Replacement Working Directory"
                        description = "Directory ${session.cwd} is unavailable. This choice will be remembered for this session."
                    }
                    val selected = FileChooser.chooseFile(descriptor, project, null) ?: return@invokeLater
                    organisation.edit(session.id) { it.replacementDirectory = selected.path }
                    run(project, session, launch) // Revalidate on the worker before using the selection.
                }
            }
        }
    }
}
