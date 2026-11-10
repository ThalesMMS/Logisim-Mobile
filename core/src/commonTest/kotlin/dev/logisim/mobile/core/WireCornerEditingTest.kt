package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WireCornerEditingTest {
    private fun circuit() = CircuitDocument(components = listOf(
        CircuitComponent("in", ComponentKind.INPUT, GridPoint(0, 0)),
        CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(100, 100)),
    ), wires = listOf(WireSegment(GridPoint(0, 0), GridPoint(100, 0)),
        WireSegment(GridPoint(100, 0), GridPoint(100, 100))))

    @Test
    fun movingAnElbowKeepsTheElectricalConnectionAndUsesOneUndoEntry() {
        val original = circuit()
        val editor = CircuitEditor(original)
        editor.moveWirePoint(GridPoint(100, 0), GridPoint(80, 20))
        assertEquals(listOf(
            WireSegment(GridPoint(0, 0), GridPoint(80, 0)),
            WireSegment(GridPoint(80, 0), GridPoint(80, 20)),
            WireSegment(GridPoint(80, 20), GridPoint(100, 20)),
            WireSegment(GridPoint(100, 20), GridPoint(100, 100))), editor.document.wires)
        assertEquals(LogicValue.HIGH, CircuitSimulator().settle(editor.document,
            inputs = mapOf("in" to LogicValue.HIGH)).componentValues["out"])
        assertTrue(editor.undo())
        assertEquals(original, editor.document)
        assertFalse(editor.canUndo)
    }

    @Test
    fun aRoutingHandleCannotMoveAComponentTerminalOrMergeAnotherPinNet() {
        val original = circuit().copy(components = circuit().components +
            CircuitComponent("other", ComponentKind.INPUT, GridPoint(80, 20)))
        val editor = CircuitEditor(original)
        editor.moveWirePoint(GridPoint(0, 0), GridPoint(20, 20))
        assertEquals(original, editor.document)
        editor.moveWirePoint(GridPoint(100, 0), GridPoint(80, 20))
        assertEquals(original, editor.document)
        assertFalse(editor.canUndo)
    }
}
