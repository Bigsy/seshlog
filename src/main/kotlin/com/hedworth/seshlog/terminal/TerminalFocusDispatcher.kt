package com.hedworth.seshlog.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.ide.DataManager
import com.intellij.openapi.project.Project
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener
import java.util.Collections
import java.util.WeakHashMap

/**
 * The one application-wide focus hook used by terminal observers. A project can have several
 * terminal panes, but focus changes are global; installing a KeyboardFocusManager listener per
 * project multiplies callbacks for every open project. Observers filter the event against their
 * own known contents.
 */
@Service(Service.Level.APP)
internal class TerminalFocusDispatcher : Disposable {
    private val observers = Collections.newSetFromMap(WeakHashMap<TerminalTabObserver, Boolean>())
    private val keyboard = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    private val listener = PropertyChangeListener { event -> dispatch(event.newValue as? Component) }

    init {
        keyboard.addPropertyChangeListener("permanentFocusOwner", listener)
    }

    fun register(observer: TerminalTabObserver) {
        synchronized(observers) { observers += observer }
    }

    fun unregister(observer: TerminalTabObserver) {
        synchronized(observers) { observers -= observer }
    }

    /** Forward a focus change only to the focused project. Must not hold the set lock callbacks. */
    internal fun dispatch(component: Component?, focusedProject: Project? = projectOf(component)) {
        val current = synchronized(observers) { observers.toList() }
        current.filter { observer ->
            if (focusedProject == null) observer.project == null else observer.project === focusedProject
        }
            .forEach { it.focusChanged(component) }
    }

    private fun projectOf(component: Component?): Project? = component?.let {
        runCatching { DataManager.getInstance().getDataContext(it).getData(CommonDataKeys.PROJECT) }.getOrNull()
    }

    override fun dispose() {
        keyboard.removePropertyChangeListener("permanentFocusOwner", listener)
        synchronized(observers) { observers.clear() }
    }

    companion object {
        fun getInstance(): TerminalFocusDispatcher =
            ApplicationManager.getApplication().getService(TerminalFocusDispatcher::class.java)
    }
}
