package com.hedworth.seshlog.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * Guards the settings UI built by the Kotlin UI DSL. The Restore-mode combo used to render its
 * labels through the deprecated SimpleListCellRenderer.create(String, Function); this pins the
 * label for every mode (and for null) so the swap to textListCellRenderer stays behaviour-neutral.
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

    private fun comboBoxes(root: Component): List<JComboBox<*>> = buildList {
        fun walk(c: Component) {
            if (c is JComboBox<*>) add(c)
            if (c is Container) c.components.forEach(::walk)
        }
        walk(root)
    }

    /** The DSL renderer returns a composite component; the text lives in its labels. */
    private fun labelText(c: Component): String = buildList {
        fun walk(x: Component) {
            if (x is JLabel) x.text?.takeIf { it.isNotEmpty() }?.let { add(it) }
            if (x is Container) x.components.forEach(::walk)
        }
        walk(c)
    }.joinToString(" ")
}
