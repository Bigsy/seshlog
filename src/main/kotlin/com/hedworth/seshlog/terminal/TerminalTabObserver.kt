package com.hedworth.seshlog.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener
import javax.swing.SwingUtilities

/** Observes terminal tabs even when the platform moves them between pane content managers. */
internal class TerminalTabObserver(
    private val rootManager: () -> ContentManager?,
    private val openContents: () -> List<Content>,
    private val selectionChanged: (Content?) -> Unit,
    private val tabClosed: (Content) -> Unit,
    private val later: (() -> Unit) -> Unit = { ApplicationManager.getApplication().invokeLater(it) },
) : Disposable {
    private val managers = LinkedHashSet<ContentManager>()
    private var contents = emptyList<Content>()
    private var selected: Content? = null
    private var disposed = false
    private val keyboard = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    private val focusListener = PropertyChangeListener { focusChanged(it.newValue as? Component) }
    private val listener = object : ContentManagerListener {
        override fun contentAdded(event: ContentManagerEvent) = refreshLater()

        override fun contentRemoved(event: ContentManagerEvent) {
            // Dragging detaches Content at mouse-down and reattaches it at mouse-up, potentially
            // many event turns later. A missing manager is not evidence that the tab was closed.
            // Wait for removeContent(..., true) to finish disposing an actually closed tab.
            later {
                if (!disposed) {
                    if (Disposer.isDisposed(event.content)) tabClosed(event.content)
                    refresh()
                }
            }
        }

        override fun selectionChanged(event: ContentManagerEvent) {
            if (event.operation == ContentManagerEvent.ContentOperation.add) select(event.content)
            else if (selected === event.content) select(null)
        }
    }

    init {
        keyboard.addPropertyChangeListener("permanentFocusOwner", focusListener)
    }

    fun refresh() {
        if (disposed) return
        contents = openContents().filter { it.manager != null }
        val root = rootManager()
        val currentManagers = contents.mapNotNull { it.manager }.toSet() + listOfNotNull(root)
        for (manager in managers - currentManagers) manager.removeContentManagerListener(listener)
        for (manager in currentManagers - managers) manager.addContentManagerListener(listener)
        managers.clear()
        managers.addAll(currentManagers)
        select(contentAt(keyboard.permanentFocusOwner)
            ?: selected?.takeIf { it in contents && it.manager?.isSelected(it) == true }
            ?: root?.selectedContent?.takeIf { it in contents }
            ?: contents.firstOrNull { it.manager?.isSelected(it) == true })
    }

    internal fun focusChanged(component: Component?) {
        if (disposed || component == null) return
        // A newly created pane may not have emitted events through an observed manager yet.
        refresh()
        contentAt(component)?.let(::select)
    }

    private fun contentAt(component: Component?): Content? = component?.let { focus ->
        contents.firstOrNull { SwingUtilities.isDescendingFrom(focus, it.component) }
    }

    private fun refreshLater() = later { if (!disposed) refresh() }

    private fun select(content: Content?) {
        selected = content
        selectionChanged(content) // Re-resolve session ownership after each index refresh too.
    }

    override fun dispose() {
        disposed = true
        keyboard.removePropertyChangeListener("permanentFocusOwner", focusListener)
        managers.forEach { it.removeContentManagerListener(listener) }
        managers.clear()
        contents = emptyList()
        selected = null
    }
}
