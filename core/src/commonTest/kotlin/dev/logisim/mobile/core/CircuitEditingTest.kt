package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CircuitEditingTest {
    @Test
    fun copyingAGroupKeepsInternalWiresAndExcludesExternalBranches() {
        val editor = CircuitEditor(branchedCircuit())

        val fragment = editor.copy(setOf("a", "b"))

        assertEquals(listOf("a", "b"), fragment.components.map { it.id })
        assertEquals(setOf(
            WireSegment(GridPoint(0, 0), GridPoint(50, 0)),
            WireSegment(GridPoint(50, 0), GridPoint(100, 0)),
        ), fragment.wires.toSet())
        assertEquals(3, editor.document.wires.size)
        assertFalse(editor.canUndo)
    }

    @Test
    fun repeatedPastesAllocateDistinctIdsAndPreserveLabelsAndGeometry() {
        val editor = CircuitEditor(branchedCircuit())
        val fragment = editor.copy(setOf("a", "b"))
        val first = editor.paste(fragment, GridPoint(200, 200))
        val second = editor.paste(fragment, GridPoint(400, 200))

        assertEquals(2, first.size)
        assertEquals(2, second.size)
        assertTrue(first.intersect(second).isEmpty())
        assertEquals(7, editor.document.components.map { it.id }.distinct().size)
        assertEquals(listOf("A", "B"), editor.document.components.filter { it.id in first }.map { it.label })
        assertEquals(listOf(GridPoint(200, 200), GridPoint(300, 200)),
            editor.document.components.filter { it.id in first }.map { it.position })
        assertTrue(editor.undo())
        assertEquals(5, editor.document.components.size)
        assertTrue(editor.undo())
        assertEquals(branchedCircuit(), editor.document)
    }

    @Test
    fun groupMovementAndItsBoundaryConnectionsAreOneUndoStep() {
        val original = branchedCircuit()
        val editor = CircuitEditor(original)

        editor.move(setOf("a", "b"), GridPoint(0, 100))

        assertEquals(GridPoint(0, 100), editor.document.components.first { it.id == "a" }.position)
        assertEquals(GridPoint(100, 100), editor.document.components.first { it.id == "b" }.position)
        assertEquals(GridPoint(50, -100), editor.document.components.first { it.id == "c" }.position)
        assertTrue(editor.document.wires.any { it.contains(GridPoint(50, 0)) })
        assertTrue(editor.undo())
        assertEquals(original, editor.document)
        assertFalse(editor.canUndo)
    }

    @Test
    fun emptySelectionAndClipboardDoNotCreateHistory() {
        val editor = CircuitEditor(branchedCircuit())
        assertTrue(editor.copy(emptySet()).isEmpty)
        assertEquals(emptySet(), editor.paste(CircuitFragment(), GridPoint(10, 10)))
        editor.move(emptySet(), GridPoint(10, 10))
        editor.remove(emptySet())
        assertFalse(editor.canUndo)
    }

    @Test
    fun aBranchAtTheMiddleOfAWireSplitsItAndCanBeUndoneTogether() {
        val horizontal = WireSegment(GridPoint(0, 0), GridPoint(100, 0))
        val editor = CircuitEditor(CircuitDocument(wires = listOf(horizontal)))

        editor.connect(GridPoint(50, 0), GridPoint(50, 100))

        assertEquals(setOf(
            WireSegment(GridPoint(0, 0), GridPoint(50, 0)),
            WireSegment(GridPoint(50, 0), GridPoint(100, 0)),
            WireSegment(GridPoint(50, 0), GridPoint(50, 100)),
        ), editor.document.wires.toSet())
        assertTrue(editor.undo())
        assertEquals(listOf(horizontal), editor.document.wires)
        assertFalse(editor.canUndo)
    }

    @Test
    fun unrelatedUnmarkedCrossingsRemainUnsplit() {
        val crossing = listOf(
            WireSegment(GridPoint(0, 50), GridPoint(100, 50)),
            WireSegment(GridPoint(50, 0), GridPoint(50, 100)),
        )
        val editor = CircuitEditor(CircuitDocument(wires = crossing))
        editor.connect(GridPoint(200, 0), GridPoint(200, 100))
        assertTrue(editor.document.wires.containsAll(crossing))
        assertFalse(editor.document.wires.any { it.from == GridPoint(50, 50) || it.to == GridPoint(50, 50) })

        editor.splitWire(GridPoint(50, 50))
        assertEquals(5, editor.document.wires.size)
        assertEquals(4, editor.document.wires.count { it.from == GridPoint(50, 50) || it.to == GridPoint(50, 50) })
    }

    @Test
    fun movingUsesTheOtherElbowToAvoidAnUnrelatedNetwork() {
        val original = CircuitDocument(components = listOf(
            CircuitComponent("a", ComponentKind.INPUT, GridPoint(0, 0)),
            CircuitComponent("b", ComponentKind.OUTPUT, GridPoint(100, 0)),
            CircuitComponent("c", ComponentKind.INPUT, GridPoint(100, 100)),
            CircuitComponent("d", ComponentKind.OUTPUT, GridPoint(200, 100)),
        ), wires = listOf(
            WireSegment(GridPoint(0, 0), GridPoint(100, 0)),
            WireSegment(GridPoint(100, 100), GridPoint(200, 100)),
        ))
        val editor = CircuitEditor(original)

        editor.move("a", GridPoint(50, 100))

        assertEquals(GridPoint(50, 100), editor.document.components.first { it.id == "a" }.position)
        assertTrue(editor.document.wires.contains(WireSegment(GridPoint(50, 0), GridPoint(100, 0))))
        assertFalse(editor.document.wires.any { it.from == GridPoint(100, 100) && it.to == GridPoint(100, 0) })
        assertTrue(editor.undo())
        assertEquals(original, editor.document)
    }

    @Test
    fun copyingPreservesAttributesSubcircuitReferencesAndSymbolPorts() {
        val original = CircuitComponent("alu", ComponentKind.SUBCIRCUIT, GridPoint(20, 40),
            label = "ALU", attributes = mapOf("facing" to "north", "labelcolor" to "#112233"),
            circuitName = "Arithmetic", explicitPorts = listOf(
                ComponentPort("a", GridPoint(-20, 0), PortDirection.INPUT, 8),
                ComponentPort("y", GridPoint(0, 0), PortDirection.OUTPUT, 8),
            ))
        val editor = CircuitEditor(CircuitDocument(components = listOf(original)))
        val ids = editor.paste(editor.copy(setOf("alu")), GridPoint(100, 100))
        val pasted = editor.document.components.single { it.id in ids }
        assertEquals(original.copy(id = pasted.id, position = GridPoint(120, 140)), pasted)
    }

    @Test
    fun changingFacingReconnectsPortsWithoutShortingInputToOutput() {
        val buffer = CircuitComponent("buffer", ComponentKind.BUFFER, GridPoint(100, 50))
        val original = CircuitDocument(components = listOf(
            CircuitComponent("a", ComponentKind.INPUT, GridPoint(0, 50)), buffer,
            CircuitComponent("y", ComponentKind.OUTPUT, GridPoint(150, 50)),
        ), wires = listOf(
            WireSegment(GridPoint(0, 50), GridPoint(80, 50)),
            WireSegment(GridPoint(100, 50), GridPoint(150, 50)),
        ))
        val editor = CircuitEditor(original)
        editor.updateComponent("buffer") { it.copy(attributes = mapOf("facing" to "north")) }
        assertEquals("north", editor.document.components.first { it.id == "buffer" }.facing)
        assertTrue(editor.document.wires.any { it.from == GridPoint(100, 70) || it.to == GridPoint(100, 70) })
        assertTrue(editor.undo())
        assertEquals(original, editor.document)
    }

    @Test
    fun deletingComponentsAndExplicitWiresIsAtomic() {
        val original = branchedCircuit()
        val editor = CircuitEditor(original)
        val wire = original.wires.first()

        editor.remove(setOf("a", "b"), setOf(wire))

        assertEquals(listOf("c"), editor.document.components.map { it.id })
        assertEquals(2, editor.document.wires.size)
        assertTrue(editor.undo())
        assertEquals(original, editor.document)
    }

    @Test
    fun movingWireKeepsItsOriginalEndsConnected() {
        val original = CircuitDocument(components = listOf(
            CircuitComponent("a", ComponentKind.INPUT, GridPoint(0, 0)),
            CircuitComponent("b", ComponentKind.OUTPUT, GridPoint(100, 0)),
        ), wires = listOf(WireSegment(GridPoint(0, 0), GridPoint(100, 0))))
        val editor = CircuitEditor(original)
        editor.moveWire(original.wires.single(), GridPoint(0, 50))

        assertTrue(editor.document.wires.any { it.contains(GridPoint(0, 0)) })
        assertTrue(editor.document.wires.any { it.contains(GridPoint(100, 0)) })
        assertTrue(editor.document.wires.any { it.from.y == 50 && it.to.y == 50 })
        assertTrue(editor.undo())
        assertEquals(original, editor.document)
    }

    private fun branchedCircuit() = CircuitDocument(components = listOf(
        CircuitComponent("a", ComponentKind.INPUT, GridPoint(0, 0), "A"),
        CircuitComponent("b", ComponentKind.OUTPUT, GridPoint(100, 0), "B"),
        CircuitComponent("c", ComponentKind.OUTPUT, GridPoint(50, -100), "C"),
    ), wires = listOf(
        WireSegment(GridPoint(0, 0), GridPoint(50, 0)),
        WireSegment(GridPoint(50, 0), GridPoint(100, 0)),
        WireSegment(GridPoint(50, -100), GridPoint(50, 0)),
    ))
}
