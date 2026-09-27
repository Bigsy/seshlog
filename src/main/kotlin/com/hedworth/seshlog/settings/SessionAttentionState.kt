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
    /** Replaced after every mutation; the save thread never iterates the live EDT-owned map. */
    @Volatile private var unreadSnapshot: Map<String, SessionAttention.Completion> = emptyMap()
    private var started = false
    override fun getState() = State().also { state ->
        state.unread = unreadSnapshot.mapValuesTo(linkedMapOf()) { entry -> entry.value.copy() }
    }
    override fun loadState(state: State) {
        val unread = state.unread.mapValuesTo(linkedMapOf()) { it.value.copy() }
        tracker = SessionAttention(unread)
        replaceSnapshot()
    }
    val unreadIds: Set<String> get() = unreadSnapshot.keys.toSet()
    fun receipt(session: Session): SessionAttention.Completion? = tracker.receipt(session)

    fun start() {
        if (started) return
        started = true
        tracker.observe(SessionIndex.getInstance().sessions, runningIds())
        replaceSnapshot()
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(SessionIndex.TOPIC,
            object : SessionIndex.SessionIndexListener {
                override fun sessionsUpdated(sessions: List<Session>) = observe(sessions, runningIds())
            })
    }

    internal fun observe(sessions: List<Session>, running: Set<String>) {
        val before = unreadSnapshot
        tracker.observe(sessions, running)
        // An index notification represents a completed scan. Keep persisted attention only for
        // sessions that are still present; the initial empty index is handled by start() above.
        tracker.unread.keys.retainAll(sessions.asSequence().map { it.id }.toSet())
        replaceSnapshot()
        if (before != unreadSnapshot) publish()
    }

    fun viewed(id: String, receipt: SessionAttention.Completion?) {
        if (tracker.viewed(id, receipt)) {
            replaceSnapshot()
            publish()
        }
    }

    /** The actual live terminal is visible: there is no asynchronous transcript delivery. */
    fun viewedTerminal(id: String) = viewed(id, unreadSnapshot[id])

    private fun replaceSnapshot() {
        unreadSnapshot = unreadSnapshotOf(tracker.unread)
    }

    private fun unreadSnapshotOf(source: Map<String, SessionAttention.Completion>): Map<String, SessionAttention.Completion> =
        source.mapValues { it.value.copy() }

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
