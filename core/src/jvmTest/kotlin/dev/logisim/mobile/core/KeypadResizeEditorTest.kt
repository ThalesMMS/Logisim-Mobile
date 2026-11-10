package dev.logisim.mobile.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KeypadResizeEditorTest {
    @Test
    fun connectedKeypadCanShrinkAndGrowWithoutJoiningScanRowsOrColumns() {
        val loose = WireSegment(GridPoint(900, 1200), GridPoint(1000, 1200))
        val original = fixture().let { it.copy(wires = it.wires + loose) }
        val editor = CircuitEditor(original)
        val keypadId = original.components.single { it.label == "IO_KEYPAD" }.id
        val connections = terminalNets(original)

        for (size in listOf("normal", "large", "normal")) {
            val keypad = editor.document.components.single { it.id == keypadId }
            val next = ComponentCatalog.registry.reconfigure(keypad, mapOf("size" to size))
            editor.updateComponents(mapOf(keypadId to next))

            assertEquals(next, editor.document.components.single { it.id == keypadId })
            assertEquals(8, next.layout().ports.size)
            assertEquals(connections, terminalNets(editor.document))
            assertTrue(editor.document.wires.any { it == loose })
            assertKeypadScans(editor.document, keypadId)
            val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(CircCodec.write(editor.document)))
                .project.mainCircuit
            assertEquals(connections, terminalNets(reopened))
        }

        val resized = editor.document
        repeat(3) { assertTrue(editor.undo()) }
        assertEquals(original, editor.document)
        assertFalse(editor.canUndo)
        repeat(3) { assertTrue(editor.redo()) }
        assertEquals(resized, editor.document)
    }

    @Test
    fun reroutingKeepsAJunctionAndItsFixedBranch() {
        val junction = GridPoint(450, 950)
        val branch = WireSegment(junction, GridPoint(450, 900))
        val output = CircuitComponent("branch", ComponentKind.OUTPUT, GridPoint(450, 900))
        val original = fixture().let { it.copy(components = it.components + output, wires = it.wires + branch) }
        val editor = CircuitEditor(original)

        resize(editor, "normal")

        assertEquals(terminalNets(original), terminalNets(editor.document))
        assertTrue(editor.document.wires.any { it == branch || it == WireSegment(branch.to, branch.from) })
        assertKeypadScans(editor.document, "io_keypad")
        assertTrue(editor.undo())
        assertEquals(original, editor.document)
    }

    @Test
    fun reroutingRetainsAClosedWireLoopAttachedToAMovingTerminal() {
        val start = GridPoint(280, 1060)
        val points = listOf(start, GridPoint(300, 1060), GridPoint(300, 1080), GridPoint(280, 1080), start)
        val loop = points.zipWithNext().map { (from, to) -> WireSegment(from, to) }
        val original = fixture().let { circuit -> circuit.copy(wires = circuit.wires.filterNot {
            it.contains(start)
        } + loop) }
        val editor = CircuitEditor(original)

        resize(editor, "normal")

        assertEquals(terminalNets(original), terminalNets(editor.document))
        val moved = GridPoint(310, 1030)
        val net = CircuitTopology.elaborate(editor.document).netAt(moved)!!
        assertEquals(setOf(DiagnosticTerminalRef("io_keypad", "row-3")), net.terminals.map { it.reference }.toSet())
        assertEquals(4, net.wires.size)
        assertEquals(setOf(moved, GridPoint(330, 1030), GridPoint(330, 1050), GridPoint(310, 1050)), net.points.toSet())
        assertTrue(editor.undo())
        assertEquals(original, editor.document)
    }

    @Test
    fun reroutingKeepsAPortSharedWithAFixedComponentConnected() {
        val marker = CircuitComponent("marker", ComponentKind.OUTPUT, GridPoint(360, 950))
        val original = fixture().let { it.copy(components = it.components + marker) }
        val editor = CircuitEditor(original)

        resize(editor, "normal")

        assertEquals(terminalNets(original), terminalNets(editor.document))
        assertEquals(marker, editor.document.components.single { it.id == marker.id })
        assertKeypadScans(editor.document, "io_keypad")
        assertTrue(editor.undo())
        assertEquals(original, editor.document)
    }

    @Test
    fun reroutingKeepsAnOpenChainWhoseLooseEndIsTheNewPortPosition() {
        val base = ComponentCatalog.registry.requireDefinition(IoKinds.MatrixKeypad)
        val end = GridPoint(630, 1070)
        val registry = ComponentCatalog.registry.withDefinitions(listOf(base.copy(layout = { component, attributes ->
            val port = if (attributes[IoAttributes.KeypadSize] == "large") GridPoint(600, 1100) else end
            val layout = base.layout(component, attributes)
            layout.copy(ports = layout.ports + ComponentTerminal("stub", port, PortDirection.INPUT))
        })))
        val points = listOf(GridPoint(600, 1100), GridPoint(600, 1120), GridPoint(630, 1120), end)
        val stub = points.zipWithNext().map { (from, to) -> WireSegment(from, to) }
        val original = fixture().let { it.copy(wires = it.wires + stub) }
        val editor = CircuitEditor(original, registry)
        val keypad = original.components.single { it.id == "io_keypad" }
        val next = registry.reconfigure(keypad, mapOf("size" to "normal"))

        editor.updateComponents(mapOf(keypad.id to next))

        assertEquals(next, editor.document.components.single { it.id == keypad.id })
        assertEquals(terminalNets(original, registry), terminalNets(editor.document, registry))
        val net = CircuitTopology.elaborate(editor.document, registry).netAt(end)!!
        assertEquals(3, net.wires.size)
        assertEquals(points.toSet(), net.points.toSet())
        assertTrue(editor.undo())
        assertEquals(original, editor.document)
    }

    @Test
    fun movingAPortOntoAnotherNetStillRejectsTheWholeEdit() {
        val original = fixture()
        val editor = CircuitEditor(original)
        val keypad = original.components.single { it.id == "io_keypad" }
        val next = ComponentCatalog.registry.reconfigure(keypad, mapOf("size" to "normal"))
            .copy(position = GridPoint(220, 1170))

        assertFailsWith<IllegalArgumentException> { editor.updateComponents(mapOf(keypad.id to next)) }

        assertEquals(original, editor.document)
        assertFalse(editor.canUndo)
    }

    private fun resize(editor: CircuitEditor, size: String) {
        val keypad = editor.document.components.single { it.id == "io_keypad" }
        val next = ComponentCatalog.registry.reconfigure(keypad, mapOf("size" to size))
        editor.updateComponents(mapOf(keypad.id to next))
        assertEquals(next, editor.document.components.single { it.id == keypad.id })
    }

    private fun fixture(): CircuitDocument {
        val fixture = sequenceOf(File("examples/io-showcase.circ"), File("../examples/io-showcase.circ"))
            .first { it.isFile }
        return assertIs<CircProjectImportResult.Success>(CircCodec.readProject(fixture.readText())).project.mainCircuit
    }

    private fun terminalNets(
        document: CircuitDocument,
        registry: ComponentRegistry = ComponentCatalog.registry,
    ): Set<Set<DiagnosticTerminalRef>> =
        CircuitTopology.elaborate(document, registry).nets.map { net -> net.terminals.map { it.reference }.toSet() }.toSet()

    private fun assertKeypadScans(document: CircuitDocument, keypadId: String) {
        val simulator = CircuitSimulator()
        for (row in 0..3) {
            val inputs = (0..3).associate { index ->
                val id = document.components.single { it.label == "IN_SCAN_ROW_$index" }.id
                id to LogicVector.of(1, if (row == index) 0u else 1u)
            }
            for (column in 0..3) {
                val state = simulator.settle(document, vectorInputs = inputs,
                    events = mapOf(keypadId to listOf(IoInputEvent.KeypadKey(row, column, true))))
                assertFalse(state.oscillating)
                assertTrue(state.diagnostics.isEmpty(), state.diagnostics.toString())
                for (output in 0..3) {
                    val id = document.components.single { it.label == "OUT_KEYPAD_C$output" }.id
                    assertEquals(if (output == column) "0" else "1", state.vectorValues.getValue(id).display,
                        "row=$row column=$column output=$output")
                }
            }
        }
    }
}
