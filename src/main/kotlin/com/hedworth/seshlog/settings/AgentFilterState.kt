package com.hedworth.seshlog.settings

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

enum class AgentFilterMode(val displayName: String) {
    AUTO("Auto"),
    ALL("All agents"),
    CLAUDE_CODE("Claude Code"),
    CODEX("Codex"),
}

/** Project-specific agent-filter preference, stored in workspace.xml. */
@Service(Service.Level.PROJECT)
@State(name = "SeshlogAgentFilter", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class AgentFilterState : PersistentStateComponent<AgentFilterState.State> {
    class State {
        var mode: String = AgentFilterMode.AUTO.name
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    var mode: AgentFilterMode
        get() = runCatching { AgentFilterMode.valueOf(state.mode) }.getOrDefault(AgentFilterMode.AUTO)
        set(value) { state.mode = value.name }

    companion object {
        fun getInstance(project: Project): AgentFilterState = project.getService(AgentFilterState::class.java)
    }
}

/** Pure provider-selection logic used by the tool window and tests. */
object AgentSessionFilter {
    fun apply(sessions: List<Session>, mode: AgentFilterMode): List<Session> {
        val kind = when (mode) {
            AgentFilterMode.ALL -> null
            AgentFilterMode.CLAUDE_CODE -> AgentKind.CLAUDE_CODE
            AgentFilterMode.CODEX -> AgentKind.CODEX
            AgentFilterMode.AUTO -> sessions.groupingBy { it.kind }.eachCount()
                .maxWithOrNull(compareBy<Map.Entry<AgentKind, Int>> { it.value }.thenByDescending { it.key.ordinal })
                ?.key
        }
        return if (kind == null) sessions else sessions.filter { it.kind == kind }
    }

    fun effectiveKind(sessions: List<Session>, mode: AgentFilterMode): AgentKind? =
        apply(sessions, mode).firstOrNull()?.kind
}
