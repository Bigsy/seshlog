package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.SessionDateFilter
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/** Project-level copy of the transient list filters needed by attention surfaces outside the tree. */
@Service(Service.Level.PROJECT)
class AttentionViewState(private val project: Project) {
    data class Snapshot(
        val showHidden: Boolean = false,
        val dateFilter: SessionDateFilter = SessionDateFilter(),
    )

    @Volatile var snapshot: Snapshot = Snapshot()
        private set

    fun update(showHidden: Boolean = snapshot.showHidden, dateFilter: SessionDateFilter = snapshot.dateFilter) {
        val next = Snapshot(showHidden, dateFilter)
        if (next == snapshot) return
        snapshot = next
        project.messageBus.syncPublisher(TOPIC).changed()
    }

    interface Listener { fun changed() }

    companion object {
        val TOPIC = Topic.create("Seshlog attention view filters", Listener::class.java)
        fun getInstance(project: Project): AttentionViewState = project.getService(AttentionViewState::class.java)
    }
}
