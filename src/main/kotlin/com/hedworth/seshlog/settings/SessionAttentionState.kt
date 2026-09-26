package com.hedworth.seshlog.settings

import com.hedworth.seshlog.index.SessionAttention
import com.hedworth.seshlog.index.SessionIndex
import com.hedworth.seshlog.model.Session
import com.hedworth.seshlog.terminal.OwnedTerminalTabs
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.messages.Topic

/** App-wide unread completion timestamps, shared by every project. All mutations run on EDT. */
@Service(Service.Level.APP)
@State(name = "SeshlogAttention", storages = [Storage("seshlog.xml")])
class SessionAttentionState : PersistentStateComponent<SessionAttentionState.State>, Disposable {
    class State { var unread: MutableMap<String, SessionAttention.Completion> = linkedMapOf() }
    private var tracker = SessionAttention()
    private var started = false
    override fun getState() = State().also { it.unread = tracker.unread.mapValuesTo(linkedMapOf()) { entry -> entry.value.copy() } }
    override fun loadState(state: State) {
        tracker = SessionAttention(state.unread.mapValuesTo(linkedMapOf()) { it.value.copy() })
    }
    val unreadIds: Set<String> get() = tracker.unread.keys.toSet()
    fun receipt(session: Session): SessionAttention.Completion? = tracker.receipt(session)

    fun start() {
        if (started) return
        started = true
        tracker.observe(SessionIndex.getInstance().sessions, runningIds())
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(SessionIndex.TOPIC,
            object : SessionIndex.SessionIndexListener {
                override fun sessionsUpdated(sessions: List<Session>) = observe(sessions, runningIds())
            })
    }

    internal fun observe(sessions: List<Session>, running: Set<String>) {
        val before = tracker.unread.toMap()
        tracker.observe(sessions, running)
        if (before != tracker.unread) publish()
    }

    fun viewed(id: String, receipt: SessionAttention.Completion?) {
        if (tracker.viewed(id, receipt)) publish()
    }

    /** The actual live terminal is visible: there is no asynchronous transcript delivery. */
    fun viewedTerminal(id: String) = viewed(id, tracker.unread[id])

    private fun runningIds(): Set<String> = ProjectManager.getInstance().openProjects
        .filterNot { it.isDisposed }.flatMap { OwnedTerminalTabs.getInstance(it).runningSessionIds() }.toSet()

    private fun publish() = ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).changed()
    override fun dispose() {}
    interface Listener { fun changed() }
    companion object {
        val TOPIC = Topic.create("Seshlog unread completions", Listener::class.java)
        fun getInstance(): SessionAttentionState = ApplicationManager.getApplication().getService(SessionAttentionState::class.java)
    }
}
