package com.hedworth.seshlog.selection

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EditorReferenceTest : BasePlatformTestCase() {
    fun testLineRangeExcludesTheLineAtAnExclusiveEnd() {
        val file = myFixture.addFileToProject("src/example.txt", "one\ntwo\nthree\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val editor = myFixture.editor
        editor.selectionModel.setSelection(0, 8)
        val reference = EditorReference.capture(project, editor)
        assertEquals(file.virtualFile.path, reference.path)
        assertEquals(1, reference.firstLine)
        assertEquals(2, reference.lastLine)
        editor.selectionModel.setSelection(5, 6)
        assertEquals(2, EditorReference.capture(project, editor).firstLine)
        assertEquals(2, EditorReference.capture(project, editor).lastLine)
        editor.selectionModel.setSelection(0, editor.document.textLength)
        assertEquals(3, EditorReference.capture(project, editor).lastLine)
    }

    fun testUnsavedDocumentIsLabelledAndNeverSavedOrCopied() {
        val file = myFixture.addFileToProject("unsaved.txt", "disk text\n")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val editor = myFixture.editor
        WriteCommandAction.runWriteCommandAction(project) { editor.document.setText("unsaved fictional code\nnext\n") }
        editor.selectionModel.setSelection(0, editor.document.textLength)
        val reference = EditorReference.capture(project, editor)
        assertTrue(reference.unsaved)
        assertTrue(reference.text().endsWith(":1-2 (unsaved editor) "))
        assertFalse(reference.text().contains("fictional code"))
        assertEquals("disk text\n", String(file.virtualFile.contentsToByteArray()))
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(editor.document))
    }

    fun testUnsupportedEditorsAndMultipleCaretsCannotBeCaptured() {
        val file = myFixture.addFileToProject("multiple.txt", "fictional text")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val editor = myFixture.editor
        assertFalse(EditorReference.supported(project, editor))
        editor.selectionModel.setSelection(0, 2)
        assertTrue(EditorReference.supported(project, editor))
        assertNotNull(editor.caretModel.addCaret(VisualPosition(0, 6)))
        assertFalse(EditorReference.supported(project, editor))
        assertFalse(EditorReference.supported(null, editor))
        val factory = EditorFactory.getInstance()
        val memory = factory.createEditor(factory.createDocument("memory"), project)
        val viewer = factory.createViewer(editor.document, project)
        try {
            memory.selectionModel.setSelection(0, 2)
            viewer.selectionModel.setSelection(0, 2)
            assertFalse(EditorReference.supported(project, memory))
            assertFalse(EditorReference.supported(project, viewer))
        } finally {
            factory.releaseEditor(memory)
            factory.releaseEditor(viewer)
        }
        assertFalse(EditorReference.supported(project, memory))
    }

    fun testActionIsInTheEditorPopupAndAvailableForKeymapAssignment() {
        val manager = ActionManager.getInstance()
        val action = manager.getAction("Seshlog.SendFileReference")
        assertNotNull(action)
        assertEquals("Seshlog: Send File and Lines to Terminal", action.templatePresentation.text)
        assertTrue(action.shortcutSet.shortcuts.isEmpty())
        val popup = manager.getAction("EditorPopupMenu") as ActionGroup
        assertTrue(popup.getChildren(null).any { it === action })
    }
}
