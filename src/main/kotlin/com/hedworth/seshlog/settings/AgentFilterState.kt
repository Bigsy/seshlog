package com.hedworth.seshlog.settings

import com.hedworth.seshlog.model.AgentKind
import com.hedworth.seshlog.model.Session
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

/**
 * Which agent's sessions the tool window shows. [Only] wraps an [AgentKind] so adding an agent
 * never touches this class; the persisted form is `"AUTO"`, `"ALL"` or the kind's name.
 */
sealed class AgentFilterMode(val displayName: String) {
    /** The agent with the most sessions in the current scope. */
    object Auto : AgentFilterMode("Auto")
    object All : AgentFilterMode("All agents")
    data class Only(val kind: AgentKind) : AgentFilterMode(kind.displayName)

    fun serialize(): String = when (this) {
        Auto -> "AUTO"
        All -> "ALL"
        is Only -> kind.name
    }

    companion object {
        /** Auto, All, then one entry per agent — the order the toolbar popup shows them in. */
        val entries: List<AgentFilterMode>
            get() = listOf(Auto, All) + AgentKind.entries.map(::Only)

        /** Inverse of [serialize]; unknown values (a removed agent, a hand-edited file) fall back to [Auto]. */
        fun parse(value: String): AgentFilterMode = when (value) {
            "AUTO" -> Auto
            "ALL" -> All
            else -> AgentKind.entries.firstOrNull { it.name == value }?.let(::Only) ?: Auto
        }
    }
}

/** Project-specific agent-filter preference, stored in workspace.xml. */
@Service(Service.Level.PROJECT)
@State(name = "SeshlogAgentFilter", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class AgentFilterState : PersistentStateComponent<AgentFilterState.State> {
    class State {
        var includeWorktrees: Boolean = true
        var mode: String = AgentFilterMode.Auto.serialize()
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    var includeWorktrees: Boolean
        get() = state.includeWorktrees
        set(value) { state.includeWorktrees = value }

    var mode: AgentFilterMode
        get() = AgentFilterMode.parse(state.mode)
        set(value) { state.mode = value.serialize() }

    companion object {
        fun getInstance(project: Project): AgentFilterState = project.getService(AgentFilterState::class.java)
    }
}

/** Pure provider-selection logic used by the tool window and tests. */
object AgentSessionFilter {
    fun apply(sessions: List<Session>, mode: AgentFilterMode): List<Session> {
        val kind = when (mode) {
            AgentFilterMode.All -> null
            is AgentFilterMode.Only -> mode.kind
            AgentFilterMode.Auto -> sessions.groupingBy { it.kind }.eachCount()
                .maxWithOrNull(compareBy<Map.Entry<AgentKind, Int>> { it.value }.thenByDescending { it.key.ordinal })
                ?.key
        }
        return if (kind == null) sessions else sessions.filter { it.kind == kind }
    }

    fun effectiveKind(sessions: List<Session>, mode: AgentFilterMode): AgentKind? =
        apply(sessions, mode).firstOrNull()?.kind
}
