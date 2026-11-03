package com.hedworth.seshlog.settings

import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.terminal.AgentAvailability
import com.hedworth.seshlog.ui.actions.OpenSessionDiagnosticsAction
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import kotlin.reflect.KMutableProperty0

class SeshlogConfigurable : BoundConfigurable("Seshlog") {

    override fun createPanel(): DialogPanel {
        val settings = SeshlogSettings.getInstance()
        return panel {
            group("Keyboard Shortcuts") {
                row {
                    label("Seshlog: Copy Last Assistant Message")
                        .comment("Copy the latest assistant reply with Markdown and code blocks from Claude Code, Codex, opencode or Pi.")
                }
                row {
                    label("Seshlog: Copy Latest Plan")
                        .comment("Copy the latest identifiable plan from Claude Code or Codex.")
                }
                row {
                    comment("Both shortcuts are unassigned by default. Open Settings → Keymap and search for these action names to set them.")
                }
                row {
                    comment("Use a terminal associated with a Seshlog session, or select a session in the list or conversation viewer. Both actions are also in the session context menu.")
                }
            }
            agentGroup(
                AgentKind.CLAUDE_CODE, settings::claudeDataDir, settings::claudeExecutable, settings::claudeExtraArgs,
                "Leave empty to use \$CLAUDE_CONFIG_DIR or ~/.claude (currently ${settings.resolvedClaudeDataDir()}).",
            )
            agentGroup(
                AgentKind.CODEX, settings::codexDataDir, settings::codexExecutable, settings::codexExtraArgs,
                "Leave empty to use \$CODEX_HOME or ~/.codex (currently ${settings.resolvedCodexDataDir()}).",
            ) {
                row {
                    checkBox("Enable Codex tracking in new terminals")
                        .bindSelected(settings::codexTerminalWrapperEnabled)
                        .comment("Adds the thread ID to Codex's title when you type codex in a new local bash, zsh or fish terminal on macOS/Linux. Requires IntelliJ shell integration. Reopen terminal tabs after changing this setting.")
                }
                row { comment("Uses an IDE-local launcher; your Codex config is unchanged. Aliases, full executable paths and remote shells can bypass it.") }
            }
            agentGroup(
                AgentKind.OPENCODE, settings::opencodeDataDir, settings::opencodeExecutable, settings::opencodeExtraArgs,
                "Leave empty to use \$XDG_DATA_HOME/opencode or ~/.local/share/opencode (currently ${settings.resolvedOpenCodeDataDir()}).",
            ) {
                row {
                    checkBox("Show archived sessions")
                        .bindSelected(settings::opencodeShowArchived)
                        .comment("Sessions archived in opencode's own session list are hidden unless this is on.")
                }
            }
            agentGroup(
                AgentKind.PI, settings::piSessionsDir, settings::piExecutable, settings::piExtraArgs,
                "Leave empty to use PI_CODING_AGENT_SESSION_DIR, PI_CODING_AGENT_DIR/sessions or ~/.pi/agent/sessions (currently ${settings.resolvedPiSessionsDir()}).",
                directoryLabel = "Pi sessions directory:",
            )
            group("Session List") {
                row {
                    checkBox("Show sessions from all projects by default")
                        .bindSelected(settings::showAllProjects)
                }
                row("Hide untitled sessions with fewer than") {
                    intTextField(0..100)
                        .bindIntText(settings::minPromptsForUntitled)
                        .columns(4)
                    label("user prompts")
                }.comment("Sessions with 0 prompts are usually aborted starts. Set to 0 to show everything.")
            }
            group("Restore After Restart") {
                row("Sessions live at shutdown:") {
                    comboBox(RestoreMode.entries, textListCellRenderer<RestoreMode?> { mode ->
                        when (mode) {
                            RestoreMode.ASK -> "Ask"
                            RestoreMode.ALWAYS -> "Always restore"
                            RestoreMode.NEVER -> "Never restore"
                            null -> ""
                        }
                    }).bindItem(settings::restoreMode.toNullableProperty())
                }.comment("When a project opens, sessions that were running in it when the IDE closed can be resumed in new terminal tabs.")
            }
            group("Notifications") {
                row {
                    checkBox(NOTIFY_WAITING_LABEL)
                        .bindSelected(settings::notifyWhenWaiting)
                        .comment("A balloon names the session when its agent finishes a turn or is interrupted, unless you are already looking at its terminal tab.")
                }
            }
            group("Preview") {
                row {
                    checkBox("Show preview pane below the session list")
                        .bindSelected(settings::showPreview)
                }
                row("Messages to preview:") {
                    intTextField(1..50)
                        .bindIntText(settings::previewMessageCount)
                        .columns(4)
                }.comment("The most recent user prompts and assistant replies of the selected session.")
            }
            group("Search") {
                row { comment("Search stays on this device. The saved index can reveal conversation content.", maxLineLength = 45) }
                row { cell(SearchCachePanel()).align(AlignX.FILL).resizableColumn() }
            }
            group("Session Diagnostics") {
                row {
                    checkBox("Record session diagnostics")
                        .bindSelected(settings::sessionDiagnosticsEnabled)
                        .comment("Stored only on this computer. Never uploaded or sent anywhere.")
                }
                row {
                    button("Open session diagnostics") { OpenSessionDiagnosticsAction.show() }
                }
                row { comment("Records process matching and tab changes, without conversations or commands. Paths and session IDs are hashed. Keeps up to 20 MiB in the IDE log folder.") }
                row { comment("Turning this off stops new recording when you apply settings. Existing log files are kept.") }
            }
        }
    }

    /**
     * The per-agent settings group: data directory with a folder chooser, executable, additional
     * arguments, the terminal-menu toggle, optional extras.
     */
    private fun Panel.agentGroup(
        kind: AgentKind,
        dataDir: KMutableProperty0<String>,
        executable: KMutableProperty0<String>,
        extraArgs: KMutableProperty0<String>,
        dataDirComment: String,
        directoryLabel: String = "Data directory:",
        extraRows: Panel.() -> Unit = {},
    ) {
        group(kind.displayName) {
            row(directoryLabel) {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor())
                    .bindText(dataDir)
                    .columns(40)
                    .comment(dataDirComment)
            }
            row("Executable:") {
                textField()
                    .bindText(executable)
                    .columns(20)
                    .comment("Resolved via the terminal shell's PATH.")
            }
            row(EXTRA_ARGS_LABEL) {
                textField()
                    .bindText(extraArgs)
                    .columns(40)
                    .comment("Appended as typed to the resume and fork commands, for example --model sonnet. Quote values as you would in the shell.")
            }
            row {
                val settings = SeshlogSettings.getInstance()
                checkBox(TERMINAL_MENU_LABEL)
                    .bindSelected({ settings.inTerminalMenu(kind) }, { settings.setInTerminalMenu(kind, it) })
                    .comment(terminalMenuComment(kind, settings.executable(kind)))
            }
            extraRows()
        }
    }

    override fun apply() {
        super.apply()
        SessionIndex.getInstance().settingsChanged()
        SeshlogSettingsListener.fire()
    }

    companion object {
        internal const val EXTRA_ARGS_LABEL = "Additional arguments:"
        internal const val TERMINAL_MENU_LABEL = "Show New Session in the terminal right-click menu"

        /** Reflects the saved executable; an edited one is checked again when the menu next opens. */
        internal fun terminalMenuComment(kind: AgentKind, executable: String, availability: AgentAvailability = AgentAvailability.shared): String {
            val found = availability.locate(executable)
            return if (found != null) {
                "Opens ${kind.displayName} in a new tab in the clicked terminal's directory. Found at $found."
            } else {
                "Hidden: $executable was not found on the IDE's PATH. Shell aliases and functions are not detected; enter a full path in Executable instead."
            }
        }
        internal const val NOTIFY_WAITING_LABEL = "Notify when a running session is waiting for input"
    }
}
