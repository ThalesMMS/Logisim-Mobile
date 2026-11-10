package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CircuitEditorTest {
    @Test
    fun exclusiveTtlBusPadRejectsDirectPlacementAndAllowsWiring() {
        val kind = ComponentKind("TTL_74299")
        val anchor = GridPoint(400, 400)
        val busPad = GridPoint(530, 430)
        val adjacent = GridPoint(540, 430)
        val ttl = CircuitComponent("ttl", kind, anchor)
        val constant = CircuitComponent("constant", ComponentKind.CONSTANT, busPad, attributes = mapOf("facing" to "north"))
        val original = CircuitDocument(components = listOf(ttl))
        val editor = CircuitEditor(original)

        val error = assertFailsWith<LocalizedArgumentException> { editor.add(constant, busPad) }
        assertEquals("Já existe um terminal exclusivo neste ponto.", error.text.resolve(AppLanguage.PORTUGUESE))
        assertEquals(original, editor.document)
        assertFalse(editor.canUndo)

        editor.add(ComponentKind.PROBE, busPad)
        assertTrue(editor.undo())
        assertFailsWith<LocalizedArgumentException> { editor.add(ComponentKind.CONSTANT, busPad) }
        assertTrue(editor.canRedo)
        editor.add(constant, adjacent)
        editor.connect(adjacent, busPad)
        assertEquals(listOf(WireSegment(busPad, adjacent)), editor.document.wires)

        val reversed = CircuitEditor(CircuitDocument(components = listOf(constant)))
        assertFailsWith<LocalizedArgumentException> { reversed.add(kind, anchor) }
        assertEquals(listOf(constant), reversed.document.components)
    }

    @Test
    fun tracingAnExistingWireCannotShortenAcrossAnInteriorTerminalOrJunction() {
        val left = GridPoint(100, 100)
        val right = GridPoint(180, 100)
        val junction = GridPoint(120, 100)
        val terminal = GridPoint(140, 100)
        val branch = WireSegment(junction, GridPoint(120, 130))
        val pin = CircuitComponent("pin", ComponentKind.INPUT, terminal)
        val original = CircuitDocument(components = listOf(pin), wires = listOf(WireSegment(left, right), branch))
        val editor = CircuitEditor(original)
        val registry = ComponentCatalog.registry
        assertNull(original.wireBacktrack(right, left, registry))
        assertNull(original.wireBacktrack(right, junction, registry))
        assertFalse(editor.backtrackWire(right, left))
        assertFalse(editor.canUndo)
        assertEquals(WireSegment(right, terminal), original.wireBacktrack(right, terminal, registry))
        assertTrue(editor.backtrackWire(right, terminal))
        assertEquals(listOf(WireSegment(left, terminal), branch), editor.document.wires)
        assertEquals(listOf(pin), editor.document.components)
        assertTrue(editor.undo())
        assertEquals(original, editor.document)
        assertFalse(editor.undo())
    }

    @Test
    fun connectIsOneUndoableOperationEvenWhenItNeedsAnElbow() {
        val editor = CircuitEditor()
        editor.connect(GridPoint(0, 0), GridPoint(100, 40))
        assertEquals(listOf(
            WireSegment(GridPoint(0, 0), GridPoint(100, 0)),
            WireSegment(GridPoint(100, 0), GridPoint(100, 40))), editor.document.wires)
        assertTrue(editor.undo())
        assertEquals(emptyList(), editor.document.wires)
        assertTrue(editor.redo())
        assertEquals(2, editor.document.wires.size)
    }

    @Test
    fun addingMovingDeletingAndUndoRestoreTheDocument() {
        val editor = CircuitEditor()
        val input = editor.add(ComponentKind.INPUT, GridPoint(100, 100))
        val afterAdd = editor.document
        editor.move(input, GridPoint(120, 130))
        assertEquals(GridPoint(120, 130), editor.document.components.single().position)
        editor.remove(input)
        assertEquals(emptyList(), editor.document.components)
        assertTrue(editor.undo())
        assertEquals(GridPoint(120, 130), editor.document.components.single().position)
        assertTrue(editor.undo())
        assertEquals(afterAdd, editor.document)
        assertTrue(editor.undo())
        assertEquals(CircuitDocument(), editor.document)
        assertFalse(editor.undo())
    }

    @Test
    fun movingAnEndpointPreservesItsConnectionAndCanBeUndone() {
        val original = CircuitDocument(components = listOf(
            CircuitComponent("in", ComponentKind.INPUT, GridPoint(0, 0)),
            CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(100, 0))),
            wires = listOf(WireSegment(GridPoint(0, 0), GridPoint(100, 0))))
        val editor = CircuitEditor(original)
        editor.move("out", GridPoint(120, 50))
        assertEquals(LogicValue.HIGH, CircuitSimulator().settle(editor.document,
            inputs = mapOf("in" to LogicValue.HIGH)).componentValues["out"])
        assertTrue(editor.undo())
        assertEquals(original, editor.document)
    }

    @Test
    fun newEditDiscardsRedoAndReplacingKeepsUndoHistory() {
        val editor = CircuitEditor()
        editor.add(ComponentKind.INPUT, GridPoint(0, 0))
        editor.undo()
        editor.add(ComponentKind.OUTPUT, GridPoint(100, 0))
        assertFalse(editor.canRedo)
        val beforeReplace = editor.document
        editor.replace(DemoCircuits.andGate())
        assertTrue(editor.canUndo)
        assertFalse(editor.canRedo)
        assertEquals("Minha_primeira_porta_AND", editor.document.name)
        assertTrue(editor.undo())
        assertEquals(beforeReplace, editor.document)
        assertTrue(editor.redo())
        assertEquals(DemoCircuits.andGate(), editor.document)
    }

    @Test
    fun newDocumentCanBeUndoneWithoutLosingPreviousCircuit() {
        val original = DemoCircuits.flipFlop()
        val editor = CircuitEditor(original)
        editor.replace(CircuitDocument())
        assertEquals(CircuitDocument(), editor.document)
        assertTrue(editor.undo())
        assertEquals(original, editor.document)
    }

    @Test
    fun documentRejectsDuplicateIdsAndDiagonalWireSegments() {
        val input = CircuitComponent("a", ComponentKind.INPUT, GridPoint(0, 0))
        assertFailsWith<IllegalArgumentException> { CircuitDocument(components = listOf(input, input)) }
        assertFailsWith<IllegalArgumentException> { WireSegment(GridPoint(0, 0), GridPoint(10, 10)) }
    }
}
