package com.hedworth.seshlog.settings

import com.hedworth.seshlog.index.SearchSort
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/** Main-list preferences belong to this project; the quick switcher is independent. */
@Service(Service.Level.PROJECT)
@State(name = "SeshlogSessionView", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class SessionViewState(private val project: Project? = null) : PersistentStateComponent<SessionViewState.State> {
    class State {
        var searchSort: String = SearchSort.BEST_MATCH.name
        var groupSearch: Boolean = false
        var groupBrowsing: Boolean = true
    }

    private var state = State()
    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }

    var searchSort: SearchSort
        get() = SearchSort.entries.firstOrNull { it.name == state.searchSort } ?: SearchSort.BEST_MATCH
        set(value) { if (state.searchSort != value.name) { state.searchSort = value.name; changed() } }

    fun grouped(searching: Boolean): Boolean = if (searching) state.groupSearch else state.groupBrowsing

    fun setGrouped(searching: Boolean, value: Boolean) {
        if (grouped(searching) == value) return
        if (searching) state.groupSearch = value else state.groupBrowsing = value
        changed()
    }

    private fun changed() { project?.messageBus?.syncPublisher(TOPIC)?.changed() }
    interface Listener { fun changed() }
    companion object {
        val TOPIC = Topic.create("Seshlog session view", Listener::class.java)
        fun getInstance(project: Project): SessionViewState = project.getService(SessionViewState::class.java)
    }
}
