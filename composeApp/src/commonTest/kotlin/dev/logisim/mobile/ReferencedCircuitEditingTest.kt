package dev.logisim.mobile

import dev.logisim.mobile.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReferencedCircuitEditingTest {
    private fun state(custom: Boolean = true): EditorState {
        val child = CircuitDocument("child", components = listOf(
            CircuitComponent("a", ComponentKind.INPUT, GridPoint(20, 30), "A", mapOf("width" to "8", "facing" to "east")),
            CircuitComponent("y", ComponentKind.OUTPUT, GridPoint(90, 30), "Y", mapOf("width" to "8", "facing" to "west")),
            CircuitComponent("spare", ComponentKind.INPUT, GridPoint(20, 70), "Spare", mapOf("width" to "8", "facing" to "east")),
            CircuitComponent("gate", ComponentKind.NOT, GridPoint(100, 130), "Gate"),
        ).filter { custom || it.label != "Spare" })
        val appearance = SymbolAppearance(SymbolAnchor(GridPoint(30, 15)), listOf(
            SymbolPort("a", GridPoint(0, 15)),
            SymbolPort("y", GridPoint(30, 15)),
            SymbolPort("spare", GridPoint(0, 25)),
        ).filter { custom || it.pinId != "spare" }, listOf(SymbolObject.Shape("box", "rect", mapOf("x" to "0", "y" to "0", "width" to "30", "height" to "40"))))
        val east = AppearanceEdits.bind(CircuitComponent("instance", ComponentKind.SUBCIRCUIT, GridPoint(200, 100),
            "Instance east", mapOf("facing" to "east"), circuitName = "child"), child, appearance)
        val north = AppearanceEdits.bind(CircuitComponent("north", ComponentKind.SUBCIRCUIT, GridPoint(400, 200),
            "Instance north", mapOf("facing" to "north"), circuitName = "child"), child, appearance)
        val main = CircuitDocument("main", components = listOf(east, north,
            CircuitComponent("source", ComponentKind.INPUT, GridPoint(120, 100), "Source", mapOf("width" to "8")),
            CircuitComponent("sink", ComponentKind.OUTPUT, GridPoint(260, 100), "Sink", mapOf("width" to "8", "facing" to "west")),
        ), wires = listOf(WireSegment(GridPoint(120, 100), GridPoint(170, 100)),
            WireSegment(GridPoint(200, 100), GridPoint(260, 100))))
        val project = AppearanceEdits.apply(CircuitProject(listOf(main, child), "main"), "child", appearance)
        return EditorState(CircCodec.writeProject(if (custom) project else AppearanceEdits.useClassic(project, "child"))).also {
            it.selectCircuit("child")
        }
    }

    private fun pin(state: EditorState, label: String = "A") = state.document.components.single { it.label == label }
    private fun instances(project: CircuitProject) = project.mainCircuit.components.filter { it.kind == ComponentKind.SUBCIRCUIT }

    private fun assertSingleUndo(state: EditorState, before: CircuitProject) {
        val after = state.project
        assertNotEquals(before, after)
        assertEquals(1, state.revision)
        assertTrue(state.canUndo)
        state.undo()
        assertEquals(before, state.project)
        assertFalse(state.canUndo)
        assertTrue(state.canRedo)
        state.redo()
        assertEquals(after, state.project)
        assertFalse(state.canRedo)
    }

    @Test
    fun movingARealPinPreservesCustomSymbolPortsAndParentWiresInOneUndoAction() {
        val state = state()
        val a = pin(state)
        val before = state.project
        state.move(a.id, GridPoint(40, 60))
        assertEquals(GridPoint(40, 60), state.document.components.single { it.id == a.id }.position)
        assertTrue(state.document.extraXml.single().contains("pin=\"40,60\""))
        assertEquals(instances(before), instances(state.project))
        assertEquals(before.mainCircuit.wires, state.project.mainCircuit.wires)
        assertSingleUndo(state, before)
    }

    @Test
    fun pinWidthAndFacingUpdateEveryInstanceWithoutMovingItsCustomPorts() {
        val state = state()
        val a = pin(state)
        val before = state.project
        state.selectComponent(a.id)
        state.updateSelectedAttributes(mapOf("width" to "4", "facing" to "north"))
        assertEquals(4, pin(state).width)
        assertEquals("north", pin(state).facing)
        for ((old, changed) in instances(before).zip(instances(state.project))) {
            assertEquals(old.facing, changed.facing)
            assertEquals(old.explicitPorts.map { it.name }, changed.explicitPorts.map { it.name })
            assertEquals(old.explicitPorts.map { it.position }, changed.explicitPorts.map { it.position })
            assertEquals(4, changed.explicitPorts.single { it.name == a.id }.width)
        }
        assertEquals(before.mainCircuit.wires, state.project.mainCircuit.wires)
        assertSingleUndo(state, before)
    }

    @Test
    fun pinLabelsReachParentTerminalsAndUndoTogether() {
        val state = state()
        val a = pin(state)
        val before = state.project
        state.selectComponent(a.id)
        state.updateSelectedLabel("Renamed_pin")
        assertEquals("Renamed_pin", state.document.components.single { it.id == a.id }.label)
        assertTrue(instances(state.project).all { instance -> instance.explicitPorts.single { it.name == a.id }.label == "Renamed_pin" })
        assertEquals(before.mainCircuit.wires, state.project.mainCircuit.wires)
        assertSingleUndo(state, before)
    }

    @Test
    fun deletingAnUnconnectedPinRemovesOnlyItsSymbolTerminals() {
        val state = state()
        val spare = pin(state, "Spare")
        val before = state.project
        state.selectComponent(spare.id)
        state.removeSelected()
        assertTrue(state.document.components.none { it.id == spare.id })
        assertTrue(SymbolAppearance.read(state.document).ports.none { it.pinId == spare.id })
        for ((old, changed) in instances(before).zip(instances(state.project))) {
            assertEquals(old.explicitPorts.filterNot { it.name == spare.id }, changed.explicitPorts)
        }
        assertEquals(before.mainCircuit.wires, state.project.mainCircuit.wires)
        assertSingleUndo(state, before)
    }

    @Test
    fun insertingDuplicatingPastingAndPlacingPinsAllReconcileTheirNewTerminals() {
        val edits: List<(EditorState) -> Unit> = listOf(
            { assertTrue(it.insertComponent(ComponentKind.INPUT, GridPoint(160, 160))) },
            // Duplicate previews the copy until a destination is confirmed (#139).
            { it.duplicateSelected(); it.tap(GridPoint(60, 200)) },
            { it.copySelected(); it.pasteAt(GridPoint(60, 80)) },
            { it.place(ComponentKind.OUTPUT); it.tap(GridPoint(160, 160)) },
        )
        for (edit in edits) {
            val state = state()
            state.selectComponent(pin(state, "Spare").id)
            val before = state.project
            val originalIds = state.document.components.map { it.id }.toSet()
            edit(state)
            val inserted = state.document.components.single { it.id !in originalIds }
            assertTrue(inserted.kind in setOf(ComponentKind.INPUT, ComponentKind.OUTPUT))
            assertEquals(4, SymbolAppearance.read(state.document).ports.size)
            for ((old, changed) in instances(before).zip(instances(state.project))) {
                assertEquals(old.explicitPorts, changed.explicitPorts.filterNot { it.name == inserted.id })
                assertEquals(inserted.width, changed.explicitPorts.single { it.name == inserted.id }.width)
            }
            assertEquals(before.mainCircuit.wires, state.project.mainCircuit.wires)
            assertSingleUndo(state, before)
        }
    }

    @Test
    fun connectedPinRemovalPreservesProjectSelectionRedoAndTheInternalEditor() {
        val state = state()
        val a = pin(state)
        state.selectComponent(a.id)
        state.updateSelectedLabel("Temporary_label")
        state.undo()
        state.selectComponent(a.id)
        val before = state.project
        val revision = state.revision
        val simulation = state.rootSimulation
        var invalidations = 0
        state.onSimulationInvalidated = { invalidations++ }
        state.removeSelected()
        assertEquals(before, state.project)
        assertEquals(before.circuit("child"), state.document)
        assertEquals(setOf(a.id), state.selectedIds)
        assertEquals(revision, state.revision)
        assertFalse(state.canUndo)
        assertTrue(state.canRedo)
        assertEquals(simulation, state.rootSimulation)
        assertEquals(0, invalidations)
        assertTrue(state.status.contains("terminal"))
        state.updateSelectedLabel("Accepted_label")
        assertEquals("Accepted_label", state.document.components.single { it.id == a.id }.label)
        assertTrue(instances(state.project).all { it.explicitPorts.single { port -> port.name == a.id }.label == "Accepted_label" })
        assertFalse(state.canRedo)
        state.undo()
        assertEquals(before, state.project)
        assertFalse(state.canUndo)
    }

    @Test
    fun applyingAToolDocumentReconcilesAllItsPinEditsAsOneAction() {
        val state = state()
        val a = pin(state)
        val spare = pin(state, "Spare")
        val before = state.project
        val added = CircuitComponent("new-pin", ComponentKind.INPUT, GridPoint(180, 160), "New", mapOf("width" to "2"))
        state.applyDocument(state.document.copy(components = state.document.components.filterNot { it.id == spare.id }.map {
            if (it.id == a.id) it.copy(position = GridPoint(40, 60), attributes = it.attributes + ("width" to "4")) else it
        } + added))
        assertEquals(GridPoint(40, 60), pin(state).position)
        assertTrue(state.document.extraXml.single().contains("pin=\"40,60\""))
        for (instance in instances(state.project)) {
            assertEquals(4, instance.explicitPorts.single { it.name == a.id }.width)
            assertEquals(2, instance.explicitPorts.single { it.name == added.id }.width)
            assertTrue(instance.explicitPorts.none { it.name == spare.id })
        }
        assertEquals(before.mainCircuit.wires, state.project.mainCircuit.wires)
        assertSingleUndo(state, before)
    }

    @Test
    fun aRejectedToolDocumentCannotPublishOrPoisonTheNextEditorAction() {
        val state = state()
        val a = pin(state)
        state.selectComponent(a.id)
        val before = state.project
        var invalidations = 0
        state.onSimulationInvalidated = { invalidations++ }
        assertFailsWith<IllegalArgumentException> {
            state.applyDocument(state.document.copy(components = state.document.components.filterNot { it.id == a.id }))
        }
        assertEquals(before, state.project)
        assertEquals(before.circuit("child"), state.document)
        assertEquals(setOf(a.id), state.selectedIds)
        assertEquals(0, state.revision)
        assertFalse(state.canUndo)
        assertEquals(0, invalidations)
        state.updateSelectedLabel("Still_present")
        assertEquals("Still_present", state.document.components.single { it.id == a.id }.label)
        assertSingleUndo(state, before)
    }

    @Test
    fun deletingAConnectedPinThroughAnActionPreservesThePreviousSelection() {
        val state = state()
        val a = pin(state)
        val gate = pin(state, "Gate")
        state.selectComponent(gate.id)
        val before = state.project
        assertFalse(state.removeComponent(a.id))
        assertEquals(before, state.project)
        assertEquals(setOf(gate.id), state.selectedIds)
        assertFalse(state.canUndo)
    }

    @Test
    fun changingClassicPinFacingReroutesTheParentWireWithoutLosingItsNet() {
        val state = state(custom = false)
        val a = pin(state)
        val before = state.project
        val oldInstance = instances(before).single { it.facing == "east" }
        state.selectComponent(a.id)
        state.updateSelectedAttribute("facing", "north")
        val changed = instances(state.project).single { it.facing == "east" }
        val newTerminal = changed.layout().ports.single { it.name == a.id }.position
        assertNotEquals(oldInstance.layout().ports.single { it.name == a.id }.position, newTerminal)
        assertEquals("classic", state.document.attributes["appearance"])
        val source = state.project.mainCircuit.components.single { it.label == "Source" }
        val topology = CircuitTopology.elaborate(state.project.mainCircuit)
        assertEquals(topology.netAt(source.position)?.id, topology.netAt(newTerminal)?.id)
        assertTrue(topology.netAt(newTerminal)?.terminals?.any { it.reference.componentId == source.id } == true)
        assertSingleUndo(state, before)
    }
}
