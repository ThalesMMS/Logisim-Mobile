package dev.logisim.mobile

import dev.logisim.mobile.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AccessibilityContextOwnershipTest {
    private fun circuit(name: String, x: Int) = CircuitDocument(name = name, id = name,
        components = listOf(
            CircuitComponent("source", ComponentKind.INPUT, GridPoint(x, 20), "A"),
            CircuitComponent("sink", ComponentKind.OUTPUT, GridPoint(x + 120, 20), "Y"),
        ))

    private fun actions(state: EditorState, properties: () -> Unit = {}): CircuitAccessibilityActions {
        val ownership = state.accessibilityContext()
        return editorCircuitAccessibilityActions(EditorToolContext(
            project = state.project, document = state.document, simulation = state.simulation,
            selectedComponents = emptyList(), applyProject = state::applyProject, applyDocument = state::applyDocument,
            selectCircuit = state::selectCircuit, selectComponent = state::selectComponent, showStatus = state::reportStatus,
            documentIdentity = state.documentIdentity, activeInstancePath = ownership.instancePath,
            isCurrentEditorContext = { state.accessibilityContext() == ownership },
            connect = state::connectTerminals,
            removeComponent = state::removeComponent, activateComponent = state::activateComponent,
            selectWire = state::selectWire, removeWire = state::removeWire,
            insertComponent = state::insertComponent, openProperties = properties,
        ))
    }

    @Test
    fun retainedRemoveCannotMutateAnotherCircuitWithTheSameComponentId() {
        val project = CircuitProject(listOf(circuit("main", 0), circuit("other", 300)))
        val state = EditorState(CircCodec.writeProject(project))
        val retained = actions(state)
        state.selectCircuit("other")
        val before = state.exportXml()
        assertFalse(retained.removeComponent("source"), "The retained main action must reject the other circuit")
        assertEquals(before, state.exportXml())
    }

    @Test
    fun pendingConnectionCannotCrossProjectReplacementWithTheSameIds() {
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(circuit("main", 0)))))
        val graph = CircuitAccessibilityGraph(state.document, state.componentRegistry, state.accessibilityContext())
        val session = AccessibleConnectionSession().choose(graph, AccessiblePortId("source", "out"), actions(state).connect).session
        val identity = state.documentIdentity
        assertTrue(state.importXml(CircCodec.writeProject(CircuitProject(listOf(circuit("main", 300))))))
        assertNotEquals(identity, state.documentIdentity)
        val replacementGraph = CircuitAccessibilityGraph(state.document, state.componentRegistry, state.accessibilityContext())
        val before = state.exportXml()
        val result = session.choose(replacementGraph, AccessiblePortId("sink", "in"), actions(state).connect)
        assertFalse(result.connected, "A connection started in the previous project must not edit its replacement")
        assertEquals(before, state.exportXml())
    }

    @Test
    fun everyRetainedActionRejectsAnotherCircuitWithoutChangingSelectionOrHistory() {
        val main = circuit("main", 0).copy(wires = listOf(WireSegment(GridPoint(0, 20), GridPoint(120, 20))))
        val other = circuit("other", 300).copy(wires = main.wires)
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(main, other))))
        var propertiesOpened = false
        val retained = actions(state) { propertiesOpened = true }
        state.selectCircuit("other")
        state.selectComponent("sink")
        val before = state.exportXml()
        val selection = state.selectedIds
        val simulation = state.simulation
        retained.selectComponent("source")
        retained.openProperties("source")
        retained.selectWire(0)
        assertFalse(retained.removeComponent("source"))
        assertFalse(retained.activateComponent("source"))
        assertFalse(retained.removeWire(0))
        assertFalse(retained.connect(GridPoint(300, 20), GridPoint(420, 20)))
        assertFalse(retained.insertComponent(ComponentKind.NOT, GridPoint(500, 20)))
        assertFalse(propertiesOpened)
        assertEquals(before, state.exportXml())
        assertEquals(selection, state.selectedIds)
        assertEquals(simulation, state.simulation)
        assertFalse(state.canUndo)
    }

    @Test
    fun sameContextActionsSurviveRenameEditAndUndo() {
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(circuit("main", 0)))))
        val owner = state.accessibilityContext()
        val retained = actions(state)
        state.renameCircuit("main", "renamed")
        assertEquals(owner, state.accessibilityContext())
        retained.selectComponent("source")
        assertEquals(setOf("source"), state.selectedIds)
        assertTrue(retained.activateComponent("source"))
        assertEquals(LogicValue.HIGH, state.simulation.componentValues["source"])
        assertTrue(retained.removeComponent("sink"))
        assertEquals(listOf("source"), state.document.components.map { it.id })
        state.undo()
        assertEquals(owner, state.accessibilityContext())
        assertEquals(listOf("source", "sink"), state.document.components.map { it.id })
        retained.selectComponent("sink")
        assertEquals(setOf("sink"), state.selectedIds)
    }

    @Test
    fun pendingConnectionSurvivesRenameAndResolvesMovedTerminalsInTheSameContext() {
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(circuit("main", 0)))))
        val graph = CircuitAccessibilityGraph(state.document, state.componentRegistry, state.accessibilityContext())
        val session = AccessibleConnectionSession().choose(graph, AccessiblePortId("source", "out"), actions(state).connect).session
        state.renameCircuit("main", "renamed")
        state.applyDocument(state.document.copy(components = state.document.components.map {
            if (it.id == "source") it.copy(position = GridPoint(40, 20)) else it
        }))
        val current = CircuitAccessibilityGraph(state.document, state.componentRegistry, state.accessibilityContext())
        val result = session.choose(current, AccessiblePortId("sink", "in"), actions(state).connect)
        assertTrue(result.connected)
        assertEquals(listOf(WireSegment(GridPoint(40, 20), GridPoint(120, 20))), state.document.wires)
        state.undo()
        assertTrue(state.document.wires.isEmpty())
    }

    @Test
    fun retainedActionsAndPendingConnectionRejectAnotherInstanceOfTheSameCircuit() {
        val child = circuit("child", 0)
        val main = CircuitDocument(name = "main", id = "root", components = listOf(
            CircuitComponent("one", ComponentKind.SUBCIRCUIT, GridPoint(100, 20), circuitName = "child"),
            CircuitComponent("two", ComponentKind.SUBCIRCUIT, GridPoint(200, 20), circuitName = "child"),
        ))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(main, child))))
        state.navigate(CircuitNavigationTarget(child.id, child.name, listOf("one")))
        val retained = actions(state)
        val graph = CircuitAccessibilityGraph(state.document, state.componentRegistry, state.accessibilityContext())
        val session = AccessibleConnectionSession().choose(graph, AccessiblePortId("source", "out"), retained.connect).session
        val owner = state.accessibilityContext()
        state.navigate(CircuitNavigationTarget(child.id, child.name, listOf("two")))
        assertNotEquals(owner, state.accessibilityContext())
        state.selectComponent("sink")
        val before = state.exportXml()
        retained.selectComponent("source")
        assertEquals(setOf("sink"), state.selectedIds)
        assertFalse(retained.removeComponent("source"))
        val current = CircuitAccessibilityGraph(state.document, state.componentRegistry, state.accessibilityContext())
        val result = session.choose(current, AccessiblePortId("sink", "in"), actions(state).connect)
        assertFalse(result.connected)
        assertEquals("a11y_explorer_connection_missing", result.notice.key)
        assertEquals(null, result.session.start)
        assertEquals(before, state.exportXml())
    }

    @Test
    fun graphCopiesTheInstancePathAndRequiresTheMatchingCircuit() {
        val path = mutableListOf("one")
        val document = circuit("main", 0)
        val graph = CircuitAccessibilityGraph(document, ComponentCatalog.registry,
            CircuitAccessibilityContext(10L, document.id, path))
        path[0] = "two"
        assertEquals(listOf("one"), graph.context.instancePath)
        assertFailsWith<IllegalArgumentException> {
            CircuitAccessibilityGraph(document, ComponentCatalog.registry, CircuitAccessibilityContext(10L, "different"))
        }
    }

    @Test
    fun retainedActionsRejectReplacementEvenWhenCircuitAndComponentIdsMatch() {
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(circuit("main", 0)))))
        val retained = actions(state)
        val owner = state.accessibilityContext()
        assertTrue(state.importXml(CircCodec.writeProject(CircuitProject(listOf(circuit("main", 300))))))
        assertEquals(owner.circuitId, state.accessibilityContext().circuitId)
        assertNotEquals(owner.documentIdentity, state.documentIdentity)
        state.selectComponent("sink")
        val before = state.exportXml()
        retained.selectComponent("source")
        assertFalse(retained.removeComponent("source"))
        assertEquals(setOf("sink"), state.selectedIds)
        assertEquals(before, state.exportXml())
    }

}
