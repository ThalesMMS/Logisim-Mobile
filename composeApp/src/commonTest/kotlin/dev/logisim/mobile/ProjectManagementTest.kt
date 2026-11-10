package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectManagementTest {
    @Test
    fun renamingLegacyMainCircuitPreservesSelectionAndExportedMain() {
        val state = EditorState(null)
        assertTrue(state.importXml("""
            <?xml version="1.0" encoding="UTF-8"?>
            <project source="2.7.1" version="1.0">
              <lib desc="#Wiring" name="0"/>
              <main name="Data path"/>
              <circuit name="first"/>
              <circuit name="Data path"/>
            </project>
        """.trimIndent()))
        val mainId = state.document.id
        assertEquals("Data path", state.document.name)
        assertEquals(mainId, state.project.mainCircuit.id)

        assertTrue(state.renameCircuit(mainId, "Renamed data path"))
        assertEquals(mainId, state.document.id)
        assertEquals("Renamed data path", state.document.name)
        assertEquals("Renamed data path", state.project.mainCircuitName)
        assertEquals(mainId, state.project.mainCircuit.id)
        val saved = state.exportXml()
        assertTrue(saved.contains("<main name=\"Renamed data path\"/>"))

        val reopened = EditorState(saved)
        assertEquals(mainId, reopened.document.id)
        assertEquals("Renamed data path", reopened.document.name)
        assertEquals(mainId, reopened.project.mainCircuit.id)
        assertEquals("Renamed data path", reopened.project.mainCircuitName)
        assertEquals(listOf("first", "Renamed data path"), reopened.project.circuits.map { it.name })
    }

    @Test
    fun createRenameOrderSetMainAndRemoveUseOneUndoEach() {
        val state = EditorState(null)
        val original = state.project
        assertTrue(state.createCircuit("Novo"))
        val added = state.document
        assertEquals("Novo", added.name)
        assertTrue(state.renameCircuit(added.id, "Renomeado"))
        assertEquals(added.id, state.document.id)
        assertEquals("Renomeado", state.document.name)
        state.undo()
        assertEquals("Novo", state.document.name)
        state.redo()
        assertEquals("Renomeado", state.document.name)
        assertTrue(state.moveCircuit(added.id, -1))
        assertEquals(added.id, state.project.circuits.first().id)
        assertTrue(state.setMainCircuit(added.id))
        assertEquals("Renomeado", state.project.mainCircuitName)
        assertFalse(state.removeCircuit(added.id))
        state.undo()
        assertEquals(original.mainCircuitName, state.project.mainCircuitName)
        assertTrue(state.removeCircuit(added.id))
        assertEquals(1, state.project.circuits.size)
        state.undo()
        assertEquals(2, state.project.circuits.size)
    }

    @Test
    fun invalidNamesAndReferencedDeletionPreserveTheProject() {
        val child = CircuitDocument("child")
        val main = CircuitDocument("main", listOf(CircuitComponent("instance", ComponentKind.SUBCIRCUIT,
            GridPoint(100, 100), circuitName = "child")))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(main, child))))
        val before = state.project
        assertFalse(state.createCircuit(" "))
        assertFalse(state.createCircuit("main"))
        assertFalse(state.renameCircuit(state.project.circuit("child")!!.id, "main"))
        assertFalse(state.removeCircuit(state.project.circuit("child")!!.id))
        assertEquals(before, state.project)
    }

    @Test
    fun openingASubcircuitFromTheCanvasUsesTheClickedInstanceAndItsParentPath() {
        val leaf = CircuitDocument("leaf")
        val child = CircuitDocument("child", components = listOf(
            CircuitComponent("nested", ComponentKind.SUBCIRCUIT, GridPoint(50, 50), circuitName = "leaf"),
        ))
        val main = CircuitDocument("main", components = listOf(
            CircuitComponent("left", ComponentKind.SUBCIRCUIT, GridPoint(100, 100), circuitName = "child"),
            CircuitComponent("right", ComponentKind.SUBCIRCUIT, GridPoint(200, 100), circuitName = "child"),
        ))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(main, child, leaf))))
        val left = state.document.components.single { it.position == GridPoint(100, 100) }.id
        val right = state.document.components.single { it.position == GridPoint(200, 100) }.id

        state.selectComponent(left)
        state.viewSubcircuitInstance(right)
        assertEquals("child", state.document.name)
        assertEquals(listOf(right), state.activeInstancePath)
        assertEquals("Instância main / child [2] · child.", state.status)

        val nested = state.document.components.single().id
        state.viewSubcircuitInstance(nested)
        assertEquals("leaf", state.document.name)
        assertEquals(listOf(right, nested), state.activeInstancePath)
        assertEquals("Instância main / child [2] / leaf · leaf.", state.status)

        state.selectCircuit("child")
        state.viewSubcircuitInstance(nested)
        assertEquals("leaf", state.document.name)
        assertTrue(state.activeInstancePath.isEmpty())
    }

    @Test
    fun definitionsAndInstancesHaveIndependentSelectionAndViewport() {
        val child = CircuitDocument("child", listOf(CircuitComponent("pin", ComponentKind.INPUT, GridPoint(0, 0))))
        val main = CircuitDocument("main", listOf(CircuitComponent("instance", ComponentKind.SUBCIRCUIT,
            GridPoint(100, 100), circuitName = "child")))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(main, child))))
        val instance = state.project.mainCircuit.components.single().id
        val childId = state.project.circuit("child")!!.id
        state.navigate(CircuitNavigationTarget(childId, "child"))
        val definitionView = CanvasViewportState(CircuitViewport(2f, Offset(20f, 30f)), IntSize(800, 600), true, false)
        state.updateViewport(definitionView)
        state.selectAll()
        val pin = state.selectedId
        state.navigate(CircuitNavigationTarget(childId, "child", listOf(instance)))
        assertFalse(state.viewport.initialized)
        assertTrue(state.selectedIds.isEmpty())
        assertEquals(listOf(instance), state.activeInstancePath)
        state.navigate(CircuitNavigationTarget(childId, "child"))
        assertEquals(definitionView, state.viewport)
        assertEquals(pin, state.selectedId)
    }
}
