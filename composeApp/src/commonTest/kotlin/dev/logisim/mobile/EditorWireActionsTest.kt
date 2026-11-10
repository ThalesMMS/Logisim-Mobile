package dev.logisim.mobile

import dev.logisim.mobile.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorWireActionsTest {
    @Test
    fun draggingAnEndpointBackPreviewsAndTrimsTheSameSegmentInOneUndo() {
        val left = GridPoint(100, 100)
        val right = GridPoint(180, 100)
        val trimTo = GridPoint(150, 100)
        val state = EditorState(CircCodec.write(CircuitDocument(wires = listOf(WireSegment(left, right)))))
        val original = state.document
        assertEquals(WireSegment(right, trimTo), original.wireBacktrack(right, trimTo, state.componentRegistry))
        state.drawEditToolWire(right, trimTo)
        assertEquals(listOf(WireSegment(left, trimTo)), state.document.wires)
        state.undo()
        assertEquals(original, state.document)
        assertEquals(WireSegment(right, left), original.wireBacktrack(right, left, state.componentRegistry))
        state.drawEditToolWire(right, left)
        assertTrue(state.document.wires.isEmpty())
        state.undo()
        assertEquals(original, state.document)
    }

    @Test
    fun splitSelectsItsNewHandlesAndCornerMoveSharesTheDocumentUndoHistory() {
        val original = CircuitDocument(wires = listOf(WireSegment(GridPoint(0, 0), GridPoint(100, 0))))
        val state = EditorState(CircCodec.write(original))
        val imported = state.document
        assertFalse(state.splitWireAt(GridPoint(0, 0)))
        assertTrue(state.splitWireAt(GridPoint(40, 0)))
        assertEquals(2, state.selectedWires.size)
        val split = state.document
        assertTrue(state.moveWireCorner(GridPoint(40, 0), GridPoint(40, 20)))
        assertTrue(state.document.wires.any { it.from == GridPoint(40, 20) || it.to == GridPoint(40, 20) })
        state.undo()
        assertEquals(split, state.document)
        state.undo()
        assertEquals(imported, state.document)
    }
}
