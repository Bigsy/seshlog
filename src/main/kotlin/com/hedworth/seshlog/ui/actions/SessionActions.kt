package com.hedworth.seshlog.ui.actions

import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.restore.SessionRestoreManager
import com.hedworth.seshlog.terminal.OwnedTerminalTabs
import com.hedworth.seshlog.terminal.TerminalTabs
import com.hedworth.seshlog.ui.SeshlogDataKeys
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import java.awt.datatransfer.StringSelection

/** Base for actions that need a selected session. */
abstract class SessionAction(text: String, description: String? = null, icon: javax.swing.Icon? = null) :
    DumbAwareAction(text, description, icon) {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(SeshlogDataKeys.SESSION) != null && e.project != null
    }

    final override fun actionPerformed(e: AnActionEvent) {
        val session = e.getData(SeshlogDataKeys.SESSION) ?: return
        val project = e.project ?: return
        perform(project, session)
    }

    abstract fun perform(project: Project, session: Session)
}

/**
 * Resume a session in a terminal tab. When this project already has a tab for the session the
 * action becomes "Show Tab" and reuses it (focus if busy, resume in it if idle); a live session
 * running elsewhere only gets a notification.
 */
class ResumeSessionAction : SessionAction("Resume", "Resume this session in a new terminal tab", AllIcons.Actions.Execute) {
    override fun update(e: AnActionEvent) {
        super.update(e)
        val session = e.getData(SeshlogDataKeys.SESSION) ?: return
        val project = e.project ?: return
        val owned = OwnedTerminalTabs.getInstance(project).owns(session.id)
        e.presentation.text = if (owned) "Show Tab" else "Resume"
        e.presentation.description = if (owned) "Focus the terminal tab running this session" else "Resume this session in a new terminal tab"
    }

    override fun perform(project: Project, session: Session) {
        if (session.isLive && !OwnedTerminalTabs.getInstance(project).owns(session.id)) {
            val process = session.livePid?.let { " (pid $it)" } ?: ""
            notify(project, "Session is already running$process", NotificationType.INFORMATION)
            return
        }
        val command = SessionIndex.getInstance().resumeCommand(session)
        try {
            TerminalTabs.resume(project, session, command)
            SessionRestoreManager.getInstance(project).recordLaunch(session)
        } catch (t: Throwable) {
            notify(project, "Could not open terminal: ${t.message}", NotificationType.ERROR)
        }
    }
}

/**
 * Fork a session into a new terminal tab, continuing the conversation under a new session id while
 * the original stays untouched. Works for live and dead sessions. Subclasses decide where the
 * [Session] comes from (Seshlog tree vs. Terminal tab).
 */
abstract class ForkSessionActionBase : DumbAwareAction("Fork Session", "Continue this session's conversation in a new session", AllIcons.Vcs.Branch) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    protected abstract fun sessionOf(e: AnActionEvent): Session?

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && sessionOf(e) != null
    }

    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = sessionOf(e) ?: return
        val command = SessionIndex.getInstance().forkCommand(session)
        try {
            TerminalTabs.fork(project, session, command)
        } catch (t: Throwable) {
            notify(project, "Could not open terminal: ${t.message}", NotificationType.ERROR)
        }
    }
}

/** Fork the session selected in the Seshlog tree. */
class ForkSessionAction : ForkSessionActionBase() {
    override fun sessionOf(e: AnActionEvent): Session? = e.getData(SeshlogDataKeys.SESSION)
}

/**
 * Fork from the Terminal tool window's tab context menu: the selected tab must be one
 * [OwnedTerminalTabs] knows a session for. Right-clicking a tab selects it, so the selected content
 * is the clicked tab.
 */
class ForkTerminalTabSessionAction : ForkSessionActionBase() {
    override fun sessionOf(e: AnActionEvent): Session? {
        val project = e.project ?: return null
        val toolWindow = e.getData(PlatformDataKeys.TOOL_WINDOW) ?: return null
        if (toolWindow.id != TerminalToolWindowFactory.TOOL_WINDOW_ID) return null
        val content = toolWindow.contentManager.selectedContent ?: return null
        val sessionId = OwnedTerminalTabs.getInstance(project).sessionFor(content) ?: return null
        return SessionIndex.getInstance().sessionById(sessionId)
    }
}

class CopyResumeCommandAction : SessionAction("Copy Resume Command", "Copy the shell command that resumes this session") {
    override fun perform(project: Project, session: Session) {
        val cmd = SessionIndex.getInstance().resumeCommand(session)
        CopyPasteManager.getInstance().setContents(StringSelection("cd ${com.hedworth.seshlog.terminal.ShellQuote.quote(session.cwd.toString())} && $cmd"))
    }
}

class CopySessionIdAction : SessionAction("Copy Session ID") {
    override fun perform(project: Project, session: Session) {
        CopyPasteManager.getInstance().setContents(StringSelection(session.id))
    }
}

class RevealTranscriptAction : SessionAction(RevealFileAction.getActionName(), "Show the transcript file") {
    override fun perform(project: Project, session: Session) {
        RevealFileAction.openFile(session.transcriptPath)
    }
}

class OpenTranscriptAction : SessionAction("Open Transcript", "Open the raw transcript in an editor tab") {
    override fun perform(project: Project, session: Session) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(session.transcriptPath) ?: run {
            notify(project, "Transcript not found: ${session.transcriptPath}", NotificationType.WARNING)
            return
        }
        FileEditorManager.getInstance(project).openFile(vf, true)
    }
}

class RefreshSessionsAction : DumbAwareAction("Refresh", "Rescan session transcripts", AllIcons.Actions.Refresh) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) = SessionIndex.getInstance().refresh()
}

internal fun notify(project: Project, content: String, type: NotificationType) {
    NotificationGroupManager.getInstance().getNotificationGroup("Seshlog")
        .createNotification(content, type)
        .notify(project)
}
