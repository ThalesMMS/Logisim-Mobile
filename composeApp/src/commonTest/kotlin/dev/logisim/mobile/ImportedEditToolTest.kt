package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.Direction
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.ProjectMouseButton
import dev.logisim.mobile.core.ProjectMouseGesture
import dev.logisim.mobile.core.ProjectMouseMappings
import dev.logisim.mobile.core.ProjectToolbar
import dev.logisim.mobile.core.ProjectToolbarBinding
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.orthogonalWireRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportedEditToolTest {
    @Test
    fun importedToolbarSelectsWithAClickAndWiresWithADrag() {
        val state = EditorState(projectXml)
        val item = ProjectToolbar.read(state.project).single()
        val tool = assertIs<ProjectToolbarBinding.Mode>(ProjectToolbar.binding(item, state.project, state.componentRegistry))
        state.changeMode(tool.editorMode())
        selectAndWire(state, state.mode)
        assertEquals(EditorMode.EDIT, state.mode)
    }

    @Test
    fun mappedEditToolUsesTheSameContextWithoutChangingTheActiveTool() {
        val state = EditorState(projectXml)
        state.changeMode(EditorMode.INTERACT)
        val gesture = ProjectMouseGesture(ProjectMouseButton.SECONDARY, ctrl = true)
        val action = ProjectMouseMappings.read(state.project)[gesture]?.action
        selectAndWire(state, mappedEditorMode(action, state.mode))
        assertEquals(EditorMode.INTERACT, state.mode)
    }

    @Test
    fun altInvertsWiringAndASelectedWireInteriorCanBeMoved() {
        val state = EditorState(projectXml)
        state.drawEditToolWire(GridPoint(100, 100), GridPoint(160, 100))
        val wire = state.document.wires.single()
        val middle = Offset(130f, 100f)
        assertEquals(GridPoint(130, 100), wireStart(state, middle))
        state.selectWire(wire, false)
        assertNull(wireStart(state, middle))
        assertEquals(GridPoint(130, 100), wireStart(state, middle, alt = true))
        assertNull(wireStart(state, Offset(100f, 100f), alt = true))
        assertEquals(GridPoint(130, 140), wireStart(state, Offset(130f, 140f), alt = true))
    }

    @Test
    fun directionKeyRefacesTheSelectedPinInOneUndoWithoutMovingIt() {
        val state = EditorState(projectXml)
        val item = ProjectToolbar.read(state.project).single()
        val tool = assertIs<ProjectToolbarBinding.Mode>(ProjectToolbar.binding(item, state.project, state.componentRegistry))
        state.changeMode(tool.editorMode())
        val pin = state.document.components.first()
        state.select(pin.id, false)

        assertTrue(state.handleToolFacing(Direction.NORTH))
        assertEquals(pin.position, state.document.components.first().position)
        assertEquals("north", state.document.components.first().facing)
        assertEquals(pin.id, state.selectedId)
        assertEquals("east", state.document.components.last().facing)

        state.undo()
        assertEquals(pin, state.document.components.first())
    }

    @Test
    fun directionKeyChangesTheImportedPlacementPreviewAndNextPin() {
        val state = EditorState(projectXml.replace(
            "<tool lib=\"6\" name=\"Edit Tool\"/>", "<tool lib=\"0\" name=\"Pin\"/>",
        ))
        val item = ProjectToolbar.read(state.project).single()
        val tool = assertIs<ProjectToolbarBinding.Place>(ProjectToolbar.binding(item, state.project, state.componentRegistry))
        state.place(tool.component, projectTool = true)
        val originalDocument = state.document

        assertTrue(state.handleToolFacing(Direction.NORTH))
        assertEquals("north", state.placingTemplate?.facing)
        assertEquals(tool.component, state.projectPlacementTool)
        assertEquals(originalDocument, state.document)
        state.tap(GridPoint(220, 150))
        assertEquals("north", state.document.components.last().facing)

        state.place(ComponentKind.INPUT)
        assertFalse(state.handleToolFacing(Direction.WEST))
        state.changeMode(EditorMode.MOVE)
        assertFalse(state.handleToolFacing(Direction.WEST))
    }

    @Test
    fun bothDragDirectionsPreviewAndSaveTheSameElbowAtAPinOrJunction() {
        val from = GridPoint(100, 100)
        val to = GridPoint(160, 160)
        val target = Offset(162f, 162f)
        val verticalElbow = GridPoint(100, 160)
        val horizontalElbow = GridPoint(160, 100)
        val pinProject = projectXml.replace("(160,100)", "(160,160)")
        val junctionProject = projectXml.replace(
            """<comp lib="0" loc="(160,100)" name="Pin"><a name="output" val="true"/></comp>""",
            """<wire from="(160,160)" to="(160,220)"/><wire from="(160,160)" to="(220,160)"/>""",
        )

        for (mode in listOf(EditorMode.EDIT, EditorMode.WIRE)) {
            for (xml in listOf(pinProject, junctionProject)) {
                for (elbow in listOf(verticalElbow, horizontalElbow)) {
                    val state = EditorState(xml)
                    state.changeMode(mode)
                    val existing = state.document.wires.toSet()
                    val start = Offset(100f, 100f)
                    val action = canvasDragAction(mode, CanvasHit.Empty, false, wireStart(state, start))
                    val gesture = CanvasGestureMachine(start, action)
                    val direction = WireDragDirection(from)

                    gesture.move(Offset(elbow.x.toFloat(), elbow.y.toFloat()), 8f, 1)
                    direction.update(elbow)
                    gesture.move(target, 8f, 0)
                    val drag = assertIs<CanvasGestureCompletion.Drag>(gesture.finish())
                    assertEquals(CanvasDragAction.DRAW_WIRE, drag.action)
                    val snapped = canvasPoint(drag.to, snapToGrid = true)
                    direction.update(snapped)
                    val end = state.document.editToolConnectionPoint(drag.to, snapped, 8f, state.componentRegistry)
                    assertEquals(to, end)

                    val expected = listOf(WireSegment(from, elbow), WireSegment(elbow, to))
                    assertEquals(expected, orthogonalWireRoute(from, to, direction.verticalFirst))
                    state.drawEditToolWire(from, to, direction.verticalFirst)
                    assertEquals(existing + expected, EditorState(state.exportXml()).document.wires.toSet())
                    state.undo()
                    assertEquals(existing, state.document.wires.toSet())
                }
            }
        }
    }

    private fun selectAndWire(state: EditorState, mode: EditorMode) {
        val from = GridPoint(100, 100)
        val to = GridPoint(160, 100)
        val terminal = Offset(100f, 100f)
        val target = CanvasHit.Component(state.document.components.first().id)
        val action = canvasDragAction(mode, target, false, wireStart(state, terminal))
        val click = CanvasGestureMachine(terminal, action)
        assertIs<CanvasGestureCompletion.Tap>(click.finish())
        state.tap(from, mode)
        assertEquals(target.id, state.selectedId)
        assertTrue(state.document.wires.isEmpty())

        val drag = CanvasGestureMachine(terminal, action)
        drag.move(Offset(160f, 100f), touchSlop = 8f, pointerCount = 1)
        assertEquals(CanvasDragAction.DRAW_WIRE, assertIs<CanvasGestureCompletion.Drag>(drag.finish()).action)
        state.drawEditToolWire(from, to)
        assertEquals(listOf(WireSegment(from, to)), state.document.wires)
        state.undo()
        assertTrue(state.document.wires.isEmpty())
    }

    private fun wireStart(state: EditorState, point: Offset, alt: Boolean = false): GridPoint? =
        state.document.editToolWireStart(point, 8f, state.componentRegistry, true, state.selectedWires, alt)

    companion object {
        private const val projectXml = """
            <project source="2.7.1" version="1.0">
              <lib name="0" desc="#Wiring"/><lib name="6" desc="#Base"/>
              <main name="main"/>
              <toolbar><tool lib="6" name="Edit Tool"/></toolbar>
              <mappings><tool lib="6" map="Ctrl Button3" name="Edit Tool"/></mappings>
              <circuit name="main">
                <comp lib="0" loc="(100,100)" name="Pin"/>
                <comp lib="0" loc="(160,100)" name="Pin"><a name="output" val="true"/></comp>
              </circuit>
            </project>"""
    }
}
