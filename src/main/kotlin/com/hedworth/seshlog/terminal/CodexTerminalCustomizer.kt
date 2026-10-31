package com.hedworth.seshlog.terminal

import com.hedworth.seshlog.settings.SeshlogSettings
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.platform.eel.provider.LocalEelDescriptor
import org.jetbrains.plugins.terminal.startup.MutableShellExecOptions
import org.jetbrains.plugins.terminal.startup.ShellExecOptionsCustomizer
import java.nio.file.Path

/** Applies to user-created terminals too; never sends commands into an already running shell. */
class CodexTerminalCustomizer : ShellExecOptionsCustomizer {
    override fun customizeExecOptions(project: Project, shellExecOptions: MutableShellExecOptions) {
        if (!SeshlogSettings.getInstance().codexTerminalWrapperEnabled || SystemInfo.isWindows ||
            shellExecOptions.eelDescriptor !== LocalEelDescriptor ||
            shellExecOptions.shellIntegrationConfigurer?.shellName?.value !in setOf("bash", "zsh", "fish")) return
        try {
            val directory = CodexTerminalWrapper.install(Path.of(PathManager.getConfigPath(), "seshlog", "terminal"))
            // The platform reapplies this after user startup files via its shell integration.
            shellExecOptions.prependEntryToPATH(directory)
        } catch (error: java.io.IOException) {
            // Failure must leave the ordinary terminal usable. Avoid logging environment or paths.
            logger<CodexTerminalCustomizer>().warn("Seshlog Codex terminal wrapper unavailable (${error.javaClass.simpleName})")
        } catch (error: IllegalArgumentException) {
            logger<CodexTerminalCustomizer>().warn("Seshlog Codex terminal wrapper unavailable (${error.javaClass.simpleName})")
        }
    }
}
