package com.hedworth.seshlog.terminal

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.nio.file.Path

/**
 * Opens a new tab in the Terminal tool window and runs a command in it. All terminal-plugin API
 * usage lives here so version differences have a single place to be handled.
 */
object TerminalLauncher {
    private val LOG = logger<TerminalLauncher>()

    /** Must be called on the EDT. Returns a handle for the new tab. */
    fun launch(project: Project, workingDirectory: Path, tabTitle: String, command: String): TerminalHandle {
        TerminalTabs.reworked.launch(project, workingDirectory.toString(), tabTitle)?.let { terminal ->
            terminal.execute(command)
            return terminal
        }
        val manager = TerminalToolWindowManager.getInstance(project)
        // Available since 2024.1 (241); replaces ShellTerminalWidget.executeCommand on older builds.
        val widget = manager.createShellWidget(
            workingDirectory.toString(),
            tabTitle,
            /* requestFocus = */ true,
            /* deferSessionStartUntilUiShown = */ true,
        )
        LOG.debug("Launching in terminal tab '$tabTitle' at $workingDirectory: $command")
        widget.sendCommandToExecute(command)
        return TerminalTabs.classic(widget, TerminalTabs.contentOf(project, widget))
    }
}
