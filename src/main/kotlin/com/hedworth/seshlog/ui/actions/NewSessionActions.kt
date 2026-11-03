package com.hedworth.seshlog.ui.actions

import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.settings.SeshlogSettings
import com.hedworth.seshlog.terminal.AgentAvailability
import com.hedworth.seshlog.terminal.NewSessionCommand
import com.hedworth.seshlog.terminal.TerminalTabs
import com.hedworth.seshlog.ui.SeshlogDataKeys
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.view.TerminalView
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Start a fresh [kind] agent in a new terminal tab at [cwd], tracked until its session appears. */
internal fun startNewSession(project: Project, kind: AgentKind, cwd: Path) {
    val settings = SeshlogSettings.getInstance()
    val command = NewSessionCommand.build(kind, settings.executable(kind), settings.extraArgs(kind))
    val existingIds = SessionIndex.getInstance().sessions.mapTo(HashSet()) { it.id }
    try {
        // The terminal owner records this returned tab as pending, together with existingIds,
        // and resolves it only from exact process/PID/descriptor evidence for that tab.
        TerminalTabs.launchPending(project, cwd, kind.displayName, command, existingIds)
    } catch (t: Throwable) {
        notify(project, "Could not open terminal: ${t.message}", NotificationType.ERROR)
    }
}

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
        startNewSession(project, kind, cwd)
    }
}

class NewClaudeSessionAction : NewSessionAction(AgentKind.CLAUDE_CODE, "New Claude Code Session")
class NewCodexSessionAction : NewSessionAction(AgentKind.CODEX, "New Codex Session")
class NewOpenCodeSessionAction : NewSessionAction(AgentKind.OPENCODE, "New opencode Session")
class NewPiSessionAction : NewSessionAction(AgentKind.PI, "New Pi Session")

/**
 * The terminal right-click menu's New Session entry for one agent: offered only when enabled in
 * settings and its executable is found, and started in a new tab at the clicked terminal's directory.
 */
abstract class TerminalNewSessionAction internal constructor(
    private val kind: AgentKind,
    private val availability: AgentAvailability = AgentAvailability.shared,
    private val launch: (Project, AgentKind, Path) -> Unit = ::startNewSession,
) : DumbAwareAction("New ${kind.displayName} Session", "Start a fresh ${kind.displayName} session in a new terminal tab in this terminal's directory", AllIcons.Actions.Execute) {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /** Shared with [TerminalNewSessionGroup], which hides itself when no agent is offered. */
    internal fun isOffered(e: AnActionEvent): Boolean {
        if (e.project == null || e.getData(TerminalView.DATA_KEY) == null) return false
        val settings = SeshlogSettings.getInstance()
        return settings.inTerminalMenu(kind) && availability.locate(settings.executable(kind)) != null
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = isOffered(e)
    }

    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val terminal = try {
            e.getData(TerminalView.DATA_KEY)?.workingDirectoryFlow?.value
        } catch (_: InvalidPathException) {
            null
        }
        val cwd = launchDirectory(terminal, project.basePath) ?: return
        launch(project, kind, cwd)
    }

    internal companion object {
        /** The clicked terminal's directory when it is a local folder, else the project root. */
        fun launchDirectory(terminal: Path?, basePath: String?, isDirectory: (Path) -> Boolean = Files::isDirectory): Path? {
            terminal?.takeIf { it.fileSystem == FileSystems.getDefault() && isDirectory(it) }?.let { return it }
            return basePath?.let { try { Path.of(it) } catch (_: InvalidPathException) { null } }
        }
    }
}

class TerminalNewClaudeSessionAction : TerminalNewSessionAction(AgentKind.CLAUDE_CODE)
class TerminalNewCodexSessionAction : TerminalNewSessionAction(AgentKind.CODEX)
class TerminalNewOpenCodeSessionAction : TerminalNewSessionAction(AgentKind.OPENCODE)
class TerminalNewPiSessionAction : TerminalNewSessionAction(AgentKind.PI)

/** One offered agent appears inline; several share a New Agent Session submenu; none hides it. */
class TerminalNewSessionGroup : DefaultActionGroup(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val offered = getChildren(e).count { (it as? TerminalNewSessionAction)?.isOffered(e) == true }
        e.presentation.isVisible = offered > 0
        e.presentation.isPopupGroup = offered > 1
    }
}
