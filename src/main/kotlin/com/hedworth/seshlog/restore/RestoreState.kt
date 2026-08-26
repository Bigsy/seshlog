package com.hedworth.seshlog.restore

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

/**
 * Per-project, in `workspace.xml`: the sessions that were live for this project when the index
 * last looked. After a restart these are the sessions to offer for restoring.
 */
@Service(Service.Level.PROJECT)
@State(name = "SeshlogRestore", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class RestoreState : PersistentStateComponent<RestoreState.State> {

    class State {
        var liveSessionIds: MutableList<String> = ArrayList()
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    var liveSessionIds: List<String>
        get() = state.liveSessionIds.toList()
        set(value) {
            state.liveSessionIds = ArrayList(value)
        }

    companion object {
        fun getInstance(project: Project): RestoreState = project.getService(RestoreState::class.java)
    }
}
