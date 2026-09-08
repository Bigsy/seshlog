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
 * Which agents the tool window shows. Legacy Auto, All and single-agent preferences remain readable.
 */
sealed class AgentFilterMode(val displayName: String) {
    /** The agent with the most sessions in the current scope. */
    object Auto : AgentFilterMode("Auto")
    object All : AgentFilterMode("All agents")
    data class Only(val kind: AgentKind) : AgentFilterMode(kind.displayName)
    data class Selected(val kinds: Set<AgentKind>) : AgentFilterMode(
        AgentKind.entries.filter { it in kinds }.joinToString(" and ") { it.displayName },
    )

    fun serialize(): String = when (this) {
        Auto -> "AUTO"
        All -> "ALL"
        is Only -> kind.name
        is Selected -> "SELECTED:" + AgentKind.entries.filter { it in kinds }.joinToString(",") { it.name }
    }

    companion object {
        /** Built-in presets, followed by each individual agent. */
        val entries: List<AgentFilterMode>
            get() = listOf(Auto, All) + AgentKind.entries.map(::Only)

        /** Inverse of [serialize]; unknown values (a removed agent, a hand-edited file) fall back to [Auto]. */
        fun parse(value: String): AgentFilterMode = when (value) {
            "AUTO" -> Auto
            "ALL" -> All
            else -> if (value.startsWith("SELECTED:")) {
                val names = value.removePrefix("SELECTED:").split(',')
                Selected(AgentKind.entries.filter { it.name in names }.toSet())
            } else AgentKind.entries.firstOrNull { it.name == value }?.let(::Only) ?: Auto
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
        val kinds = effectiveKinds(sessions, mode)
        return sessions.filter { it.kind in kinds }
    }

    fun effectiveKinds(sessions: List<Session>, mode: AgentFilterMode): Set<AgentKind> =
        when (mode) {
            AgentFilterMode.All -> AgentKind.entries.toSet()
            is AgentFilterMode.Only -> setOf(mode.kind)
            is AgentFilterMode.Selected -> mode.kinds
            AgentFilterMode.Auto -> sessions.groupingBy { it.kind }.eachCount()
                .maxWithOrNull(compareBy<Map.Entry<AgentKind, Int>> { it.value }.thenByDescending { it.key.ordinal })
                ?.key?.let { setOf(it) } ?: emptySet()
        }
}
