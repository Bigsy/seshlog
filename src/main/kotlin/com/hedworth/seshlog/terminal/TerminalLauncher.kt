package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.diagnostics.DiagnosticRef
import com.hedworth.seshlog.diagnostics.SessionDiagnostics
import com.intellij.openapi.project.Project
import java.nio.file.Path
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/** [TerminalLauncher.launch]'s shape: project, working directory, tab title, command, on created. */
internal typealias Launcher = (Project, Path, String, String, (TerminalHandle) -> Unit) -> TerminalHandle

/**
 * Opens a new tab in the Terminal tool window and runs a command in it. All terminal-plugin API
 * usage lives here so version differences have a single place to be handled.
 */
object TerminalLauncher {

    /** Must be called on the EDT. Returns a handle for the new tab. */
    fun launch(project: Project, workingDirectory: Path, tabTitle: String, command: String, onCreated: (TerminalHandle) -> Unit = {}): TerminalHandle {
        val trace = SessionDiagnostics.trace("launch", "project" to DiagnosticRef.of(project.basePath), "cwd" to DiagnosticRef.path(workingDirectory))
        trace.event("launch_requested")
        try {
            TerminalTabs.reworked.launch(project, workingDirectory.toString(), tabTitle)?.let { terminal ->
                trace.event("terminal_created", "engine" to terminal.diagnosticEngine, "tab" to DiagnosticRef.tab(terminal.content))
                onCreated(terminal)
                TerminalCommands.execute(terminal, command)
                return terminal
            }
            val manager = TerminalToolWindowManager.getInstance(project)
            // Available on the 2026.2 platform baseline; replaces ShellTerminalWidget.executeCommand.
            val widget = manager.createShellWidget(
                workingDirectory.toString(),
                tabTitle,
                /* requestFocus = */ true,
                /* deferSessionStartUntilUiShown = */ true,
            )
            return TerminalTabs.classic(widget, TerminalTabs.contentOf(project, widget)).also {
                trace.event("terminal_created", "engine" to it.diagnosticEngine, "tab" to DiagnosticRef.tab(it.content))
                onCreated(it)
                TerminalCommands.execute(it, command)
            }
        } catch (error: Throwable) {
            trace.failure("launch_failed", error)
            throw error
        }
    }
}
