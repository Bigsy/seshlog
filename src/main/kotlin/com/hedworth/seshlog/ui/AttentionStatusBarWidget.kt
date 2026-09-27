package com.hedworth.seshlog.ui

import com.hedworth.seshlog.index.SessionAttention
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

class AttentionStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = AttentionStatusBarWidget.ID
    override fun getDisplayName(): String = "Seshlog attention"
    override fun isEnabledByDefault(): Boolean = false
    override fun createWidget(project: Project): StatusBarWidget =
        AttentionStatusBarWidget(project).also { it.update(AttentionPresentation.getInstance(project).counts) }
}

internal class AttentionStatusBarWidget(private val project: Project) : CustomStatusBarWidget {
    companion object {
        const val ID = "Seshlog.Attention"
        internal fun textFor(counts: SessionAttention.Counts): String = counts.summary()
    }

    private var counts = SessionAttention.Counts(0, 0)
    private var componentInstance: JBLabel? = null
    private val component: JBLabel
        get() = componentInstance ?: JBLabel().apply {
            border = JBUI.Borders.empty(0, 6)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Next session needing attention"
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.button == MouseEvent.BUTTON1) activateSeshlog()
                }
            })
            updateText(this)
        }.also { componentInstance = it }

    internal fun update(next: SessionAttention.Counts) {
        counts = next
        componentInstance?.let(::updateText)
    }

    private fun updateText(label: JBLabel) {
        label.text = textFor(counts)
        label.isVisible = counts.hasAttention
        label.accessibleContext.accessibleName = label.text
    }

    private fun activateSeshlog() {
        val window = ToolWindowManager.getInstance(project).getToolWindow("Seshlog") ?: return
        window.activate {
            if (!project.isDisposed) {
                window.contentManager.contents.mapNotNull { it.component as? SessionTreePanel }
                    .firstOrNull()?.showNextAttention()
            }
        }
    }

    override fun getComponent(): JComponent = component
    override fun ID(): String = ID
    override fun install(statusBar: StatusBar) = Unit
    override fun dispose() = Unit

}
