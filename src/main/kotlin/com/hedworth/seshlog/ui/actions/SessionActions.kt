package com.hedworth.seshlog.ui.actions

import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.claude.LiveSessionReader
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.settings.SeshlogSettings
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.restore.SessionRestoreManager
import com.hedworth.seshlog.terminal.OwnedTerminalTabs
import com.hedworth.seshlog.terminal.TerminalTabs
import com.hedworth.seshlog.terminal.SessionProcesses
import com.intellij.openapi.application.ApplicationManager
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
        val owned = OwnedTerminalTabs.getInstance(project)
        val widget = owned.widgetFor(session.id)
        if (widget != null && TerminalTabs.state(widget) != com.hedworth.seshlog.terminal.TerminalState.IDLE) {
            owned.focus(session.id)
            return
        }
        com.hedworth.seshlog.terminal.WorkingDirectoryRecovery.run(project, session) { target ->
            val command = SessionIndex.getInstance().resumeCommand(target)
            try {
                TerminalTabs.resume(project, target, command)
                SessionRestoreManager.getInstance(project).recordLaunch(target)
            } catch (t: Throwable) {
                notify(project, "Could not open terminal: ${t.message}", NotificationType.ERROR)
            }
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
        com.hedworth.seshlog.terminal.WorkingDirectoryRecovery.run(project, session) { target ->
            val command = SessionIndex.getInstance().forkCommand(target)
            try {
                TerminalTabs.fork(project, target, command)
            } catch (t: Throwable) {
                notify(project, "Could not open terminal: ${t.message}", NotificationType.ERROR)
            }
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

class KillSessionAction : SessionAction("Kill Session", "Stop this session and close its terminal tab", AllIcons.Actions.Suspend) {
    override fun update(e: AnActionEvent) {
        super.update(e)
        val session = e.getData(SeshlogDataKeys.SESSION) ?: return
        val project = e.project ?: return
        e.presentation.isEnabled = session.livePid != null || OwnedTerminalTabs.getInstance(project).owns(session.id)
    }

    override fun perform(project: Project, session: Session) {
        val index = SessionIndex.getInstance()
        val current = index.sessionById(session.id) ?: session
        val owned = OwnedTerminalTabs.getInstance(project)
        try {
            val shellPid = owned.widgetFor(current.id)?.let(TerminalTabs::shellPid)
            val pid = current.livePid?.takeIf { pid ->
                if (current.kind != AgentKind.CLAUDE_CODE) true else {
                    val file = SeshlogSettings.getInstance().resolvedClaudeDataDir().resolve("sessions/$pid.json")
                    val live = LiveSessionReader.parse(file)
                    live != null && live.sessionId == current.id && live.pid == pid && LiveSessionReader.isCurrent(file, live)
                }
            }
            val processes = SessionProcesses.capture(listOfNotNull(shellPid, pid))
            if (!owned.close(current.id)) {
                notify(project, "Could not close the session's terminal tab", NotificationType.WARNING)
                return
            }
            SessionRestoreManager.getInstance(project).recordStop(current.id)
            val app = ApplicationManager.getApplication()
            app.executeOnPooledThread {
                try {
                    val remaining = SessionProcesses.terminate(processes)
                    if (remaining.isNotEmpty()) app.invokeLater {
                        if (!project.isDisposed) notify(project, "Could not stop session processes: ${remaining.joinToString()}", NotificationType.WARNING)
                    }
                } catch (e: Exception) {
                    app.invokeLater {
                        if (!project.isDisposed) notify(project, "Could not stop session: ${e.message}", NotificationType.ERROR)
                    }
                } finally {
                    index.refresh()
                }
            }
        } catch (e: Exception) {
            notify(project, "Could not stop session: ${e.message}", NotificationType.ERROR)
            index.refresh()
        }
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

/** Base for actions on the session's transcript file; hidden for agents that keep no per-session file. */
abstract class TranscriptFileAction(text: String, description: String? = null) : SessionAction(text, description) {
    override fun update(e: AnActionEvent) {
        super.update(e)
        if (e.getData(SeshlogDataKeys.SESSION)?.transcriptPath == null) e.presentation.isEnabledAndVisible = false
    }

    final override fun perform(project: Project, session: Session) {
        perform(project, session, session.transcriptPath ?: return)
    }

    abstract fun perform(project: Project, session: Session, transcript: java.nio.file.Path)
}

class RevealTranscriptAction : TranscriptFileAction(RevealFileAction.getActionName(), "Show the transcript file") {
    override fun perform(project: Project, session: Session, transcript: java.nio.file.Path) {
        RevealFileAction.openFile(transcript)
    }
}

class OpenTranscriptAction : TranscriptFileAction("Open Transcript", "Open the raw transcript in an editor tab") {
    override fun perform(project: Project, session: Session, transcript: java.nio.file.Path) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(transcript) ?: run {
            notify(project, "Transcript not found: $transcript", NotificationType.WARNING)
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
