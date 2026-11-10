package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.WireSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorSelectionTest {
    @Test
    fun wireOnlySelectionMovesAndUndoesAsOneOperation() {
        val original = WireSegment(GridPoint(100, 100), GridPoint(200, 100))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(CircuitDocument(wires = listOf(original))))))
        state.selectWire(original)
        state.moveSelected(GridPoint(20, 30))
        val moved = WireSegment(GridPoint(120, 130), GridPoint(220, 130))
        assertEquals(listOf(moved), state.document.wires)
        assertEquals(setOf(moved), state.selectedWires)
        state.undo()
        assertEquals(listOf(original), state.document.wires)
    }

    @Test
    fun disconnectedMoveLeavesUnselectedWiresAndUndoesAsOneOperation() {
        val wire = WireSegment(GridPoint(100, 100), GridPoint(200, 100))
        val circuit = CircuitDocument(components = listOf(
            CircuitComponent("in", ComponentKind.INPUT, wire.from, "IN"),
            CircuitComponent("out", ComponentKind.OUTPUT, wire.to, "OUT"),
        ), wires = listOf(wire))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(circuit))))
        val original = state.project
        val input = state.document.components.first { it.kind == ComponentKind.INPUT }
        val delta = GridPoint(0, 40)
        val destination = GridPoint(100, 140)
        state.changeMode(EditorMode.MOVE)

        state.select(input.id)
        state.moveSelected(delta)
        assertTrue(state.document.wires.any { it.contains(destination) })
        state.undo()

        state.select(input.id)
        state.keepConnectionsWhenMoving = false
        state.moveSelected(delta)
        assertEquals(destination, state.document.components.first { it.id == input.id }.position)
        assertEquals(listOf(wire), state.document.wires)
        state.undo()
        assertEquals(original, state.project)
        assertFalse(state.canUndo)

        state.select(input.id)
        state.selectWire(wire, additive = true)
        state.moveSelected(delta)
        assertEquals(listOf(WireSegment(destination, GridPoint(200, 140))), state.document.wires)
    }

    @Test
    fun wireOnlyCopyRepeatedPasteAndDeleteKeepThePastedGroupSelected() {
        val wire = WireSegment(GridPoint(0, 0), GridPoint(100, 0))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(CircuitDocument(wires = listOf(wire))))))
        state.selectAll()
        state.handleCanvasCommand(CanvasCommand.COPY)
        state.handleCanvasCommand(CanvasCommand.PASTE)
        state.confirmPaste()
        assertEquals(setOf(WireSegment(GridPoint(20, 20), GridPoint(120, 20))), state.selectedWires)
        state.handleCanvasCommand(CanvasCommand.PASTE)
        state.tap(GridPoint(40, 40))
        assertEquals(3, state.document.wires.size)
        state.handleCanvasCommand(CanvasCommand.DELETE)
        assertEquals(2, state.document.wires.size)
        state.undo()
        assertEquals(3, state.document.wires.size)
    }

    @Test
    fun pastePreviewMovesAndCancelsWithoutEditingBeforeOneUndoableCommit() {
        val wire = WireSegment(GridPoint(100, 100), GridPoint(200, 100))
        val circuit = CircuitDocument(components = listOf(
            CircuitComponent("in", ComponentKind.INPUT, wire.from, "IN"),
            CircuitComponent("out", ComponentKind.OUTPUT, wire.to, "OUT"),
        ), wires = listOf(wire))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(circuit))))
        val original = state.project
        val originalSelection = state.document.components.map { it.id }.toSet()
        state.selectAll()
        state.handleCanvasCommand(CanvasCommand.COPY)
        state.handleCanvasCommand(CanvasCommand.PASTE)
        state.movePastePreview(GridPoint(300, 200))
        assertEquals(GridPoint(200, 100), state.pendingPaste?.offset)
        assertEquals(original, state.project)
        assertEquals(originalSelection, state.selectedIds)
        assertEquals(0, state.revision)
        assertFalse(state.canUndo)

        state.handleCanvasCommand(CanvasCommand.CANCEL)
        assertNull(state.pendingPaste)
        assertEquals(original, state.project)
        assertEquals(originalSelection, state.selectedIds)
        assertFalse(state.canUndo)

        state.handleCanvasCommand(CanvasCommand.PASTE)
        state.movePastePreview(GridPoint(400, 300))
        state.confirmPaste()
        val newWire = WireSegment(GridPoint(400, 300), GridPoint(500, 300))
        assertNull(state.pendingPaste)
        assertEquals(setOf(newWire.from, newWire.to),
            state.document.components.filter { it.id in state.selectedIds }.map { it.position }.toSet())
        assertEquals(setOf(newWire), state.selectedWires)
        assertEquals(setOf(wire, newWire), state.document.wires.toSet())
        assertEquals(1, state.revision)
        state.undo()
        assertEquals(original, state.project)
        assertFalse(state.canUndo)
    }

    @Test
    fun duplicatePreviewPlacesInOneUndoAndKeepsTheCopiedSelection() {
        val pin = CircuitComponent("pin", ComponentKind.INPUT, GridPoint(100, 100), "copy_pin")
        val led = CircuitComponent("led", ComponentKind.LED, GridPoint(160, 160), "duplicate_led")
        val circuit = CircuitDocument(components = listOf(pin, led))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(circuit))))
        val original = state.project
        state.selectComponent(state.document.components.single { it.label == pin.label }.id)
        state.handleCanvasCommand(CanvasCommand.COPY)
        state.selectComponent(state.document.components.single { it.label == led.label }.id)

        state.handleCanvasCommand(CanvasCommand.DUPLICATE)
        state.movePastePreview(GridPoint(300, 200))
        assertEquals(GridPoint(140, 40), state.pendingPaste?.offset)
        assertEquals(original, state.project)
        assertFalse(state.canUndo)
        state.handleCanvasCommand(CanvasCommand.CANCEL)
        assertNull(state.pendingPaste)
        assertEquals(original, state.project)
        assertFalse(state.canUndo)

        state.handleCanvasCommand(CanvasCommand.DUPLICATE)
        state.tap(GridPoint(400, 300))
        val placed = state.document.components.single { it.id in state.selectedIds }
        assertEquals(led.label, placed.label)
        assertEquals(GridPoint(400, 300), placed.position)
        assertEquals(3, state.document.components.size)
        state.handleCanvasCommand(CanvasCommand.PASTE)
        assertEquals(pin.label, state.pendingPaste?.fragment?.components?.single()?.label)
        state.handleCanvasCommand(CanvasCommand.CANCEL)
        state.undo()
        assertEquals(original, state.project)
        assertFalse(state.canUndo)

        state.handleCanvasCommand(CanvasCommand.PASTE)
        state.tap(GridPoint(500, 400))
        assertEquals(pin.label, state.document.components.single { it.position == GridPoint(500, 400) }.label)
    }

    @Test
    fun cancellingAnUnfinishedWireKeepsTheDocumentUnchanged() {
        val state = EditorState(null)
        val original = state.project
        state.changeMode(EditorMode.WIRE)
        state.tap(GridPoint(20, 20))
        state.handleCanvasCommand(CanvasCommand.CANCEL)
        assertEquals(null, state.pendingWire)
        assertEquals(original, state.project)
        assertTrue(state.status.contains("cancelada"))
    }
}
