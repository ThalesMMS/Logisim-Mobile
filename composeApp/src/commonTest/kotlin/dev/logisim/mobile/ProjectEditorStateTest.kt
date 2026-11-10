package dev.logisim.mobile

import dev.logisim.mobile.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectEditorStateTest {
    private fun editorWithTwoCircuits(): EditorState {
        val main = DemoCircuits.andGate().copy(name = "main")
        val child = CircuitDocument(name = "child", components = listOf(
            CircuitComponent("pin", ComponentKind.INPUT, GridPoint(100, 100), "Child input", mapOf("width" to "4"))
        ))
        return EditorState(CircCodec.writeProject(CircuitProject(listOf(main, child), "main")))
    }

    @Test
    fun navigationDoesNotModifyTheProjectOrLoseOtherCircuits() {
        val state = editorWithTwoCircuits()
        val before = state.project
        state.selectCircuit("child")
        assertEquals("child", state.document.name)
        assertEquals(before, state.project)
        assertEquals(0, state.revision)
        state.selectCircuit("missing")
        assertEquals("child", state.activeCircuitName)
        val decoded = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(state.exportXml()))
        assertEquals(listOf("main", "child"), decoded.project.circuits.map { it.name })
    }

    @Test
    fun editingChildAndUndoPreserveTheMainCircuit() {
        val state = editorWithTwoCircuits()
        val main = state.project.mainCircuit
        state.selectCircuit("child")
        val originalChild = state.document
        state.selectComponent(state.document.components.single().id)
        state.updateSelectedLabel("Modified")
        assertEquals("Modified", state.document.components.single().label)
        assertEquals(main, state.project.mainCircuit)
        state.undo()
        assertEquals(originalChild, state.document)
        assertEquals("child", state.activeCircuitName)
        assertEquals(main, state.project.mainCircuit)
        state.redo()
        assertEquals("Modified", state.document.components.single().label)
    }

    @Test
    fun pasteRemapsIdsPreservesAttributesAndUndoesOneGroup() {
        val state = editorWithTwoCircuits()
        state.selectCircuit("child")
        state.selectAll()
        state.copySelected()
        state.paste()
        state.tap(GridPoint(120, 120))
        state.paste()
        state.tap(GridPoint(140, 140))
        assertEquals(3, state.document.components.size)
        assertEquals(3, state.document.components.map { it.id }.distinct().size)
        assertTrue(state.document.components.all { it.attributes["width"] == "4" })
        state.undo()
        assertEquals(2, state.document.components.size)
        state.undo()
        assertEquals(1, state.document.components.size)
    }

    @Test
    fun invalidImportPreservesWholeProjectAndActiveCircuit() {
        val state = editorWithTwoCircuits()
        state.selectCircuit("child")
        val before = state.project
        val simulation = state.simulation
        assertFalse(state.importXml("<project><broken/></project>"))
        assertEquals(before, state.project)
        assertEquals("child", state.activeCircuitName)
        assertEquals(simulation, state.simulation)
        assertEquals(0, state.revision)
    }

    @Test
    fun importingSap1RetainsAllSevenCircuitsInExport() {
        val state = EditorState(null)
        state.loadSap1()
        assertEquals(7, state.project.circuits.size)
        assertEquals("main", state.project.mainCircuitName)
        val decoded = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(state.exportXml()))
        assertEquals(7, decoded.project.circuits.size)
    }

    @Test
    fun importedInitialValuesAreUsedOnOpenAndReset() {
        val circuit = CircuitDocument(components = listOf(
            CircuitComponent("bit", ComponentKind.INPUT, GridPoint(20, 20), attributes = mapOf("initial" to "1")),
            CircuitComponent("bus", ComponentKind.INPUT, GridPoint(20, 60), attributes = mapOf("width" to "4", "initial" to "0xa")),
        ))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(circuit))))
        val bit = state.document.components.first { it.position == GridPoint(20, 20) }.id
        val bus = state.document.components.first { it.position == GridPoint(20, 60) }.id
        assertEquals(1uL, state.simulation.vectorValues[bit]?.toULongOrNull())
        assertEquals(10uL, state.simulation.vectorValues[bus]?.toULongOrNull())
        state.toggle(bit)
        state.setInputValue(bus, 3uL)
        assertEquals(0uL, state.simulation.vectorValues[bit]?.toULongOrNull())
        assertEquals(3uL, state.simulation.vectorValues[bus]?.toULongOrNull())
        state.resetSimulation()
        assertEquals(1uL, state.simulation.vectorValues[bit]?.toULongOrNull())
        assertEquals(10uL, state.simulation.vectorValues[bus]?.toULongOrNull())
    }

    @Test
    fun builtInSap1RunsAndResetsFromEditorControls() {
        val state = EditorState(null)
        state.loadSap1()
        val output = state.document.components.single {
            it.kind == ComponentKind.REGISTER && it.position == GridPoint(600, 340)
        }.id
        repeat(60) { state.tick() }
        assertEquals(8uL, state.simulation.vectorValues[output]?.toULongOrNull())
        assertFalse(state.simulation.oscillating)

        state.resetSimulation()
        assertEquals(0uL, state.simulation.vectorValues[output]?.toULongOrNull())
        repeat(60) { state.tick() }
        assertEquals(8uL, state.simulation.vectorValues[output]?.toULongOrNull())
    }
}
