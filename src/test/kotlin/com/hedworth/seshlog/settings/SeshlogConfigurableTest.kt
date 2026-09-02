package com.hedworth.seshlog.settings

import com.hedworth.seshlog.model.AgentKind
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * Guards the settings UI built by the Kotlin UI DSL: the Restore-mode combo's labels (which used to
 * come from the deprecated SimpleListCellRenderer.create(String, Function), so the swap to
 * textListCellRenderer is pinned as behaviour-neutral) and one settings group per agent.
 */
class SeshlogConfigurableTest : BasePlatformTestCase() {

    fun `test restore-mode combo renders a label for every mode`() {
        val configurable = SeshlogConfigurable()
        try {
            val root = configurable.createComponent()
            val combo = comboBoxes(root).single { box ->
                (0 until box.model.size).any { box.model.getElementAt(it) is RestoreMode }
            }

            @Suppress("UNCHECKED_CAST")
            val renderer = combo.renderer as ListCellRenderer<RestoreMode?>
            val list = JList<RestoreMode?>()

            fun render(mode: RestoreMode?): String =
                labelText(renderer.getListCellRendererComponent(list, mode, 0, false, false))

            assertEquals("Ask", render(RestoreMode.ASK))
            assertEquals("Always restore", render(RestoreMode.ALWAYS))
            assertEquals("Never restore", render(RestoreMode.NEVER))
            assertEquals("", render(null))

            // Every mode is offered, so none of the labels above is unreachable in the UI.
            val offered = (0 until combo.model.size).map { combo.model.getElementAt(it) }
            assertEquals(RestoreMode.entries.toList(), offered)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun `test every agent has a data-directory group and opencode has its archived toggle`() {
        val configurable = SeshlogConfigurable()
        try {
            val root = configurable.createComponent()
            val browseFields = components(root).filterIsInstance<TextFieldWithBrowseButton>()
            assertEquals(AgentKind.entries.size, browseFields.size)
            val archived = components(root).filterIsInstance<JCheckBox>().single { it.text == "Show archived sessions" }
            assertFalse(archived.isSelected)
        } finally {
            configurable.disposeUIResources()
        }
    }

    private fun components(root: Component): List<Component> = buildList {
        fun walk(c: Component) {
            add(c)
            if (c is Container) c.components.forEach(::walk)
        }
        walk(root)
    }

    private fun comboBoxes(root: Component): List<JComboBox<*>> = components(root).filterIsInstance<JComboBox<*>>()

    /** The DSL renderer returns a composite component; the text lives in its labels. */
    private fun labelText(c: Component): String = buildList {
        fun walk(x: Component) {
            if (x is JLabel) x.text?.takeIf { it.isNotEmpty() }?.let { add(it) }
            if (x is Container) x.components.forEach(::walk)
        }
        walk(c)
    }.joinToString(" ")
}
