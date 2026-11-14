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

class CircuitClipboardTest {
    @Test
    fun pasteCopiesTheSelectionAndItsWireIntoAnotherProject() {
        val clipboard = CircuitClipboard()
        val wire = WireSegment(GridPoint(100, 100), GridPoint(200, 100))
        val source = editor(clipboard, CircuitDocument("source", listOf(
            CircuitComponent("input", ComponentKind.INPUT, wire.from, "IN"),
            CircuitComponent("output", ComponentKind.OUTPUT, wire.to, "OUT"),
        ), listOf(wire)))
        val target = editor(clipboard, CircuitDocument("target"))
        val sourceBefore = source.project
        val targetBefore = target.project
        assertFalse(target.canPaste)

        source.selectAll()
        source.handleCanvasCommand(CanvasCommand.COPY)
        assertTrue(target.canPaste)
        target.handleCanvasCommand(CanvasCommand.PASTE)
        target.movePastePreview(GridPoint(300, 200))
        assertEquals(targetBefore, target.project)
        target.confirmPaste()

        assertEquals(listOf("IN", "OUT"), target.document.components.map { it.label })
        // The block's centre (150, 100) follows the preview point (C45).
        assertEquals(listOf(WireSegment(GridPoint(250, 200), GridPoint(350, 200))), target.document.wires)
        assertEquals(sourceBefore, source.project)
        target.undo()
        assertEquals(targetBefore, target.project)
    }

    @Test
    fun pasteRequiresTheSameSubcircuitAndTransitiveDefinitions() {
        val clipboard = CircuitClipboard()
        val leaf = CircuitDocument("Leaf", listOf(
            CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(60, 60)),
            CircuitComponent("input", ComponentKind.CONSTANT, GridPoint(40, 60)),
        ))
        val child = CircuitDocument("Child", listOf(instance("leaf", leaf.name)))
        val source = editor(clipboard, CircuitDocument("source", listOf(instance("child", child.name))), child, leaf)
        source.selectAll()
        source.copySelected()

        val absent = editor(clipboard, CircuitDocument("absent"))
        absent.paste()
        assertNull(absent.pendingPaste)
        assertEquals("status_paste_subcircuit_mismatch", absent.statusText.key)
        assertEquals("Child", absent.statusText.arguments["name"])
        assertFalse(absent.canUndo)

        val differentLeaf = leaf.copy(components = leaf.components.map {
            if (it.kind == ComponentKind.CONSTANT) it.copy(position = GridPoint(20, 60)) else it
        })
        val conflict = editor(clipboard, CircuitDocument("conflict"), child, differentLeaf)
        conflict.pasteAt(GridPoint(0, 0))
        assertEquals("status_paste_subcircuit_mismatch", conflict.statusText.key)
        assertEquals("Leaf", conflict.statusText.arguments["name"])
        assertTrue(conflict.document.components.isEmpty())
        assertFalse(conflict.canUndo)

        val compatible = editor(clipboard, CircuitDocument("compatible"), child, leaf)
        compatible.paste()
        compatible.confirmPaste()
        assertEquals("Child", compatible.document.components.single().circuitName)

        compatible.selectCircuit("Leaf")
        compatible.paste()
        assertNull(compatible.pendingPaste)
        assertEquals(listOf(ComponentKind.OUTPUT, ComponentKind.CONSTANT), compatible.document.components.map { it.kind })
    }

    @Test
    fun pasteWithinTheSourceProjectUsesTheCurrentDefinition() {
        val child = CircuitDocument("Child", listOf(
            CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(60, 60)),
        ))
        val state = editor(CircuitClipboard(), CircuitDocument("source", listOf(instance("child", child.name))), child)
        state.selectAll()
        state.copySelected()

        state.applyDocument(child.copy(components = child.components.map { it.copy(label = "Renamed output") }))
        state.pasteAt(GridPoint(100, 0))

        assertEquals(listOf("Child", "Child"), state.document.components.map { it.circuitName })
        assertEquals("Renamed output", state.project.circuit("Child")?.components?.single()?.label)
    }

    private fun instance(id: String, circuit: String) =
        CircuitComponent(id, ComponentKind.SUBCIRCUIT, GridPoint(100, 100), circuitName = circuit)

    private fun editor(clipboard: CircuitClipboard, vararg circuits: CircuitDocument): EditorState =
        EditorState(CircCodec.writeProject(CircuitProject(circuits.toList())), clipboard = clipboard)
}
