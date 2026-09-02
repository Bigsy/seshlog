package com.hedworth.seshlog.settings

import com.hedworth.seshlog.index.SessionIndex
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import kotlin.reflect.KMutableProperty0

class SeshlogConfigurable : BoundConfigurable("Seshlog") {

    override fun createPanel(): DialogPanel {
        val settings = SeshlogSettings.getInstance()
        return panel {
            agentGroup(
                "Claude Code", settings::claudeDataDir, settings::claudeExecutable,
                "Leave empty to use \$CLAUDE_CONFIG_DIR or ~/.claude (currently ${settings.resolvedClaudeDataDir()}).",
            )
            agentGroup(
                "Codex", settings::codexDataDir, settings::codexExecutable,
                "Leave empty to use \$CODEX_HOME or ~/.codex (currently ${settings.resolvedCodexDataDir()}).",
            )
            agentGroup(
                "opencode", settings::opencodeDataDir, settings::opencodeExecutable,
                "Leave empty to use \$XDG_DATA_HOME/opencode or ~/.local/share/opencode (currently ${settings.resolvedOpenCodeDataDir()}).",
            ) {
                row {
                    checkBox("Show archived sessions")
                        .bindSelected(settings::opencodeShowArchived)
                        .comment("Sessions archived in opencode's own session list are hidden unless this is on.")
                }
            }
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
        }
    }

    /** The per-agent settings group: data directory with a folder chooser, executable, optional extras. */
    private fun Panel.agentGroup(
        title: String,
        dataDir: KMutableProperty0<String>,
        executable: KMutableProperty0<String>,
        dataDirComment: String,
        extraRows: Panel.() -> Unit = {},
    ) {
        group(title) {
            row("Data directory:") {
                // Row.textFieldWithBrowseButton's signature differs between 2024.1 (sinceBuild) and 2026.x
                // (old overload is a compile error); a plain field + FileChooser works on both.
                val field = TextFieldWithBrowseButton().apply {
                    addActionListener {
                        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                        FileChooser.chooseFile(descriptor, null, null)?.let { text = it.path }
                    }
                }
                cell(field)
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
            extraRows()
        }
    }

    override fun apply() {
        super.apply()
        SessionIndex.getInstance().settingsChanged()
        SeshlogSettingsListener.fire()
    }
}
