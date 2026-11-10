package dev.logisim.mobile

import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.ProjectMouseAction
import dev.logisim.mobile.core.ProjectMouseButton
import dev.logisim.mobile.core.ProjectMouseGesture
import dev.logisim.mobile.core.ProjectMouseMappings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CanvasMouseMappingTest {
    @Test
    fun mappedPokeTogglesInputWithoutChangingTheSelectedTool() {
        val state = EditorState(projectXml)
        state.changeMode(EditorMode.WIRE)
        val gesture = ProjectMouseGesture(ProjectMouseButton.SECONDARY, ctrl = true)
        val action = ProjectMouseMappings.read(state.project)[gesture]?.action
        val pin = state.document.components.single()
        assertEquals(LogicValue.LOW, state.simulation.componentValues[pin.id])

        state.tap(pin.position, mappedEditorMode(action, state.mode))

        assertEquals(LogicValue.HIGH, state.simulation.componentValues[pin.id])
        assertEquals(EditorMode.WIRE, state.mode)
    }

    @Test
    fun mappedPlacementUsesImportedToolAttributesAndParticipatesInUndo() {
        val state = EditorState(projectXml)
        state.changeMode(EditorMode.MOVE)
        val gesture = ProjectMouseGesture(ProjectMouseButton.MIDDLE, shift = true)
        val action = assertIs<ProjectMouseAction.Place>(ProjectMouseMappings.read(state.project)[gesture]?.action)
        val point = GridPoint(200, 160)

        state.insertMappedComponent(action.prototype, point)

        val inserted = state.document.components.single { it.position == point }
        assertEquals(ComponentKind.INPUT, inserted.kind)
        assertEquals(8, inserted.width)
        assertEquals(EditorMode.MOVE, state.mode)
        state.undo()
        assertEquals(1, state.document.components.size)
    }

    companion object {
        private const val projectXml = """
            <project source="2.7.1" version="1.0">
              <lib name="0" desc="#Wiring"/><lib name="6" desc="#Base"/>
              <main name="main"/>
              <mappings>
                <tool lib="6" map="Ctrl Button3" name="Poke Tool"/>
                <tool lib="0" map="Shift Button2" name="Pin"><a name="width" val="8"/></tool>
              </mappings>
              <circuit name="main"><comp lib="0" loc="(100,100)" name="Pin"/></circuit>
            </project>"""
    }
}
