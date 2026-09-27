package com.hedworth.seshlog.ui.actions

import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.settings.SeshlogSettings
import com.hedworth.seshlog.terminal.NewSessionCommand
import com.hedworth.seshlog.terminal.OwnedTerminalTabs
import com.hedworth.seshlog.terminal.TerminalLauncher
import com.hedworth.seshlog.terminal.TerminalHandle
import com.hedworth.seshlog.ui.SeshlogDataKeys
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import java.nio.file.Path

/** Starts a fresh agent in the project row's working directory. */
abstract class NewSessionAction(private val kind: AgentKind, text: String) :
    DumbAwareAction(text, "Start a fresh ${kind.displayName} session", AllIcons.Actions.Execute) {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && (e.getData(SeshlogDataKeys.PROJECT_CWD) != null || e.project?.basePath != null)
    }

    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val cwd = e.getData(SeshlogDataKeys.PROJECT_CWD) ?: project.basePath?.let(Path::of) ?: return
        val settings = SeshlogSettings.getInstance()
        val command = NewSessionCommand.build(kind, settings.executable(kind), settings.extraArgs(kind))
        val existingIds = SessionIndex.getInstance().sessions.mapTo(HashSet()) { it.id }
        try {
            // The terminal owner records this returned tab as pending, together with existingIds,
            // and resolves it only from exact process/PID/descriptor evidence for that tab.
            NewSessionTab.start(project, cwd, kind, command, existingIds)
        } catch (t: Throwable) {
            notify(project, "Could not open terminal: ${t.message}", com.intellij.notification.NotificationType.ERROR)
        }
    }
}

class NewClaudeSessionAction : NewSessionAction(AgentKind.CLAUDE_CODE, "New Claude Code Session")
class NewCodexSessionAction : NewSessionAction(AgentKind.CODEX, "New Codex Session")
class NewOpenCodeSessionAction : NewSessionAction(AgentKind.OPENCODE, "New opencode Session")
class NewPiSessionAction : NewSessionAction(AgentKind.PI, "New Pi Session")

/** Terminal seam kept separate so OwnedTerminalTabs can add pending ownership without UI coupling. */
internal object NewSessionTab {
    fun start(project: Project, cwd: Path, kind: AgentKind, command: String, existingIds: Set<String>): TerminalHandle {
        val owned = OwnedTerminalTabs.getInstance(project)
        var created: TerminalHandle? = null
        return try {
            TerminalLauncher.launch(project, cwd, kind.displayName, command) { terminal ->
                created = terminal
                owned.trackPending(terminal, existingIds)
            }
        } catch (t: Throwable) {
            created?.let(owned::cancelPending)
            throw t
        }
    }
}
