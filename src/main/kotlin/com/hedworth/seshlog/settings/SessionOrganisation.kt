package com.hedworth.seshlog.settings

import com.hedworth.seshlog.model.Session
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.util.messages.Topic

/** User-owned labels and preferences; provider titles and agent storage are never modified. */
@Service(Service.Level.APP)
@State(name = "SeshlogOrganisation", storages = [Storage("seshlog.xml")])
class SessionOrganisation : PersistentStateComponent<SessionOrganisation.State> {
    class Metadata {
        var pinned: Boolean = false
        var title: String = ""
        var hidden: Boolean = false
        var replacementDirectory: String = ""
    }
    class State { var sessions: MutableMap<String, Metadata> = linkedMapOf() }
    private var state = State()
    override fun getState() = state
    override fun loadState(state: State) { this.state = state }
    fun metadata(id: String): Metadata = state.sessions[id] ?: Metadata()
    fun title(session: Session): String = metadata(session.id).title.ifBlank { session.title }
    fun edit(id: String, edit: (Metadata) -> Unit) {
        edit(state.sessions.getOrPut(id) { Metadata() })
        ApplicationManager.getApplication()?.messageBus?.syncPublisher(TOPIC)?.changed()
    }
    interface Listener { fun changed() }
    companion object {
        val TOPIC = Topic.create("Seshlog organisation", Listener::class.java)
        fun getInstance(): SessionOrganisation = ApplicationManager.getApplication().getService(SessionOrganisation::class.java)
    }
}
