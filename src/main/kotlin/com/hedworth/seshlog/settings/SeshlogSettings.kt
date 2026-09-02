package com.hedworth.seshlog.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import java.nio.file.Path
import java.nio.file.Paths

/** What to do with sessions that were live when the project was last closed. */
enum class RestoreMode { ASK, ALWAYS, NEVER }

@Service(Service.Level.APP)
@State(name = "SeshlogSettings", storages = [Storage("seshlog.xml")])
class SeshlogSettings : PersistentStateComponent<SeshlogSettings.State> {

    class State {
        /** Empty = auto (`$CLAUDE_CONFIG_DIR` or `~/.claude`). */
        var claudeDataDir: String = ""
        var claudeExecutable: String = "claude"
        /** Empty = auto (`$CODEX_HOME` or `~/.codex`). */
        var codexDataDir: String = ""
        var codexExecutable: String = "codex"
        /** Empty = auto (`$XDG_DATA_HOME/opencode` or `~/.local/share/opencode`). */
        var opencodeDataDir: String = ""
        var opencodeExecutable: String = "opencode"
        /** opencode lets the user archive a session to hide it from its own list; hidden here too unless set. */
        var opencodeShowArchived: Boolean = false
        var showAllProjects: Boolean = false
        /** Hide sessions that have no explicit title and fewer than this many real user prompts. */
        var minPromptsForUntitled: Int = 1
        /** How many of the most recent messages the preview pane shows. */
        var previewMessageCount: Int = 2
        /** Whether the preview pane is shown below the session list. */
        var showPreview: Boolean = true
        var restoreMode: String = RestoreMode.ASK.name
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) = XmlSerializerUtil.copyBean(state, this.state)

    var claudeDataDir: String
        get() = state.claudeDataDir
        set(value) { state.claudeDataDir = value }

    var claudeExecutable: String
        get() = state.claudeExecutable.ifBlank { "claude" }
        set(value) { state.claudeExecutable = value }

    var codexDataDir: String
        get() = state.codexDataDir
        set(value) { state.codexDataDir = value }

    var codexExecutable: String
        get() = state.codexExecutable.ifBlank { "codex" }
        set(value) { state.codexExecutable = value }

    var opencodeDataDir: String
        get() = state.opencodeDataDir
        set(value) { state.opencodeDataDir = value }

    var opencodeExecutable: String
        get() = state.opencodeExecutable.ifBlank { "opencode" }
        set(value) { state.opencodeExecutable = value }

    var opencodeShowArchived: Boolean
        get() = state.opencodeShowArchived
        set(value) { state.opencodeShowArchived = value }

    var showAllProjects: Boolean
        get() = state.showAllProjects
        set(value) { state.showAllProjects = value }

    var minPromptsForUntitled: Int
        get() = state.minPromptsForUntitled
        set(value) { state.minPromptsForUntitled = value }

    var previewMessageCount: Int
        get() = state.previewMessageCount.coerceIn(1, 50)
        set(value) { state.previewMessageCount = value.coerceIn(1, 50) }

    var showPreview: Boolean
        get() = state.showPreview
        set(value) { state.showPreview = value }

    var restoreMode: RestoreMode
        get() = runCatching { RestoreMode.valueOf(state.restoreMode) }.getOrDefault(RestoreMode.ASK)
        set(value) { state.restoreMode = value.name }

    /** Resolved Claude data directory. */
    fun resolvedClaudeDataDir(): Path = resolveClaudeDataDir(state.claudeDataDir)

    /** Resolved Codex data directory. */
    fun resolvedCodexDataDir(): Path = resolveCodexDataDir(state.codexDataDir)

    /** Resolved opencode data directory (the one holding `opencode.db`). */
    fun resolvedOpenCodeDataDir(): Path = resolveOpenCodeDataDir(state.opencodeDataDir)

    companion object {
        fun getInstance(): SeshlogSettings = ApplicationManager.getApplication().getService(SeshlogSettings::class.java)

        fun defaultClaudeDataDir(env: Map<String, String> = System.getenv()): Path {
            env["CLAUDE_CONFIG_DIR"]?.takeIf { it.isNotBlank() }?.let { return Paths.get(expandTilde(it)) }
            return Paths.get(System.getProperty("user.home"), ".claude")
        }

        fun resolveClaudeDataDir(configured: String, env: Map<String, String> = System.getenv()): Path =
            if (configured.isBlank()) defaultClaudeDataDir(env) else Paths.get(expandTilde(configured.trim()))

        fun defaultCodexDataDir(env: Map<String, String> = System.getenv()): Path {
            env["CODEX_HOME"]?.takeIf { it.isNotBlank() }?.let { return Paths.get(expandTilde(it)) }
            return Paths.get(System.getProperty("user.home"), ".codex")
        }

        fun resolveCodexDataDir(configured: String, env: Map<String, String> = System.getenv()): Path =
            if (configured.isBlank()) defaultCodexDataDir(env) else Paths.get(expandTilde(configured.trim()))

        /** `$XDG_DATA_HOME/opencode`, else `~/.local/share/opencode` — what `opencode debug paths` reports as `data`. */
        fun defaultOpenCodeDataDir(env: Map<String, String> = System.getenv()): Path {
            val dataHome = env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }?.let { Paths.get(expandTilde(it)) }
                ?: Paths.get(System.getProperty("user.home"), ".local", "share")
            return dataHome.resolve("opencode")
        }

        fun resolveOpenCodeDataDir(configured: String, env: Map<String, String> = System.getenv()): Path =
            if (configured.isBlank()) defaultOpenCodeDataDir(env) else Paths.get(expandTilde(configured.trim()))

        private fun expandTilde(p: String): String =
            if (p == "~" || p.startsWith("~/")) System.getProperty("user.home") + p.substring(1) else p
    }
}
