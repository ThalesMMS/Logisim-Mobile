package dev.logisim.mobile

import dev.logisim.mobile.core.AnnotationLibrary
import dev.logisim.mobile.core.ComponentBounds
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.ProjectMouseAction
import dev.logisim.mobile.core.ProjectMouseButton
import dev.logisim.mobile.core.ProjectMouseGesture
import dev.logisim.mobile.core.ProjectMouseMappings
import dev.logisim.mobile.core.ProjectToolbar
import dev.logisim.mobile.core.ProjectToolbarBinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class TextToolTest {
    @Test
    fun importedToolbarEditsTheHitLabelAndAddsTextWithItsOwnFont() {
        val state = EditorState(projectXml)
        val entry = ProjectToolbar.read(state.project).single()
        val binding = assertIs<ProjectToolbarBinding.Text>(ProjectToolbar.binding(entry, state.project, state.componentRegistry))
        state.activateTextTool(binding.prototype)
        val pin = state.document.components.single()

        val existing = assertNotNull(state.beginTextToolEdit(TextToolTarget.Existing(pin.id), null))
        assertEquals("Counter enable", existing.initialText)
        state.applyTextToolEdit(existing, "Clock enable")
        assertEquals("Clock enable", state.document.components.single().label)

        val location = GridPoint(222, 163)
        val empty = assertNotNull(state.beginTextToolEdit(TextToolTarget.Empty(location), null))
        state.applyTextToolEdit(empty, "Gate timing")
        val annotation = state.document.components.single { it.kind == ComponentKind.TEXT }
        assertEquals(location, annotation.position)
        assertEquals("Serif bold 18", annotation.attributes["font"])
        assertEquals("Gate timing", annotation.attributes["text"])

        state.undo()
        assertEquals("Clock enable", state.document.components.single().label)
        state.undo()
        assertEquals("Counter enable", state.document.components.single().label)
    }

    @Test
    fun mappedTextEditsAnnotationAndAddsTextWithoutChangingTheActiveTool() {
        val state = EditorState(projectXml)
        state.changeMode(EditorMode.WIRE)
        val gesture = ProjectMouseGesture(ProjectMouseButton.SECONDARY, ctrl = true)
        val binding = assertIs<ProjectMouseAction.Text>(ProjectMouseMappings.read(state.project)[gesture]?.action)
        assertEquals(EditorMode.TEXT, mappedEditorMode(binding, state.mode))

        val point = GridPoint(205, 170)
        val empty = assertNotNull(state.beginTextToolEdit(TextToolTarget.Empty(point), binding.prototype))
        state.applyTextToolEdit(empty, "Original note")
        val annotation = state.document.components.single { it.kind == ComponentKind.TEXT }
        assertEquals("Monospaced plain 14", annotation.attributes["font"])
        assertEquals("right", annotation.attributes["halign"])

        val existing = assertNotNull(state.beginTextToolEdit(TextToolTarget.Existing(annotation.id), binding.prototype))
        assertEquals("Original note", existing.initialText)
        state.applyTextToolEdit(existing, "Updated note")
        assertEquals(EditorMode.WIRE, state.mode)
        assertEquals("Updated note", state.document.components.single { it.id == annotation.id }.attributes["text"])

        state.undo()
        assertEquals("Original note", state.document.components.single { it.id == annotation.id }.attributes["text"])
        state.undo()
        assertEquals(1, state.document.components.size)
    }

    @Test
    fun textWithoutFontUsesTheProfileDefaultAndStaysImplicitOnSave() {
        fun text(source: String) = """
            <project source="$source" version="1.0">
              <lib name="1" desc="#Base"/><main name="main"/>
              <toolbar><tool lib="1" name="Text Tool"/></toolbar>
              <circuit name="main">
                <comp lib="1" loc="(220,160)" name="Text"><a name="text" val="Gate timing&#10;second line"/></comp>
              </circuit>
            </project>"""
        val evolution = EditorState(text("5.1.0dev"))
        val note = evolution.document.components.single { it.kind == ComponentKind.TEXT }
        assertEquals("SansSerif bold 16", note.attributes["font"])
        // Evolution d58a61e0 bounds of this Text: (162,128) 117x32 (scripts/oracle/base-tools TextToolOracle).
        assertEquals(ComponentBounds(162, 128, 279, 160), AnnotationLibrary.bounds(note))
        assertFalse("name=\"font\"" in evolution.exportXml())

        val original = EditorState(text("2.7.1")).document.components.single { it.kind == ComponentKind.TEXT }
        assertEquals("SansSerif plain 12", original.attributes["font"])
        assertEquals(ComponentBounds(176, 136, 264, 160), AnnotationLibrary.bounds(original))

        // A new Text from the Evolution toolbar tool takes bold 16 and is saved without a font, as Evolution does.
        val binding = assertIs<ProjectToolbarBinding.Text>(ProjectToolbar.binding(
            ProjectToolbar.read(evolution.project).single(), evolution.project, evolution.componentRegistry))
        evolution.activateTextTool(binding.prototype)
        val empty = assertNotNull(evolution.beginTextToolEdit(TextToolTarget.Empty(GridPoint(300, 100)), null))
        evolution.applyTextToolEdit(empty, "Old note")
        val added = evolution.document.components.single { it.attributes["text"] == "Old note" }
        assertEquals("SansSerif bold 16", added.attributes["font"])
        assertEquals(ComponentBounds(258, 84, 343, 100), AnnotationLibrary.bounds(added))
        assertFalse("name=\"font\"" in evolution.exportXml())

        // Clearing an existing Text keeps it with its previous text (Evolution's TextTool.editingStopped).
        val cleared = assertNotNull(evolution.beginTextToolEdit(TextToolTarget.Existing(added.id), null))
        evolution.applyTextToolEdit(cleared, "")
        assertEquals("Old note", evolution.document.components.single { it.id == added.id }.attributes["text"])
    }

    private companion object {
        const val projectXml = """
            <project source="2.7.1" version="1.0">
              <lib name="0" desc="#Wiring"/><lib name="6" desc="#Base"/>
              <main name="main"/>
              <mappings>
                <tool lib="6" map="Ctrl Button3" name="Text Tool">
                  <a name="font" val="Monospaced plain 14"/><a name="halign" val="right"/>
                </tool>
              </mappings>
              <toolbar><tool lib="6" name="Text Tool"><a name="font" val="Serif bold 18"/></tool></toolbar>
              <circuit name="main">
                <comp lib="0" loc="(100,100)" name="Pin"><a name="label" val="Counter enable"/></comp>
              </circuit>
            </project>"""
    }
}
