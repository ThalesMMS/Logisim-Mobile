package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HierarchicalDiagnosticFeedbackTest {
    @Test
    fun crossBoundaryFeedbackNamesBothTheMainGateAndTheChildGate() {
        val project = feedbackProject()
        val targets = hierarchicalDiagnosticFeedback(project, ComponentCatalog.registry, 20)
        assertEquals(2, targets.size)
        val main = targets.single { it.circuitId == "main" }
        val child = targets.single { it.circuitId == "child" }
        assertEquals(emptyList(), main.instancePath)
        assertEquals(setOf("inverter"), main.componentIds)
        assertEquals(listOf("child-instance"), child.instancePath)
        assertEquals(setOf("buffer"), child.componentIds)
        assertTrue(main.anchors.containsAll(listOf(GridPoint(70, 100), GridPoint(100, 100))))
        assertTrue(child.anchors.containsAll(listOf(GridPoint(30, 0), GridPoint(50, 0))))
        assertEquals(3, main.wires.size)
        assertEquals(2, child.wires.size)
        val report = CircuitDiagnosticAnalyzer.analyze(project, SimulationState(oscillating = true))
        val execution = report.diagnostics.filter { it.category == DiagnosticCategory.EXECUTION }
        assertEquals(2, execution.size)
        assertTrue(execution.all { it.code == DiagnosticCode.OSCILLATION })
        assertEquals(setOf(emptyList(), listOf("child-instance")), execution.map { it.target.instancePath }.toSet())
        assertEquals(setOf("inverter", "buffer"), execution.flatMap { it.target.componentIds }.toSet())
        assertEquals(execution.map { it.id }, CircuitDiagnosticAnalyzer.analyze(project,
            SimulationState(oscillating = true)).diagnostics.filter { it.category == DiagnosticCategory.EXECUTION }.map { it.id })
    }

    @Test
    fun theSameBoundaryMappingDoesNotInventFeedbackInAnAcyclicCircuit() {
        val original = feedbackProject()
        val main = original.mainCircuit.copy(wires = original.mainCircuit.wires.take(1))
        val project = original.withCircuit(main)
        assertEquals(emptyList(), hierarchicalDiagnosticFeedback(project, ComponentCatalog.registry, 20))
    }

    @Test
    fun busBoundariesPreserveAllBitsWithoutMixingInstanceIdentities() {
        for (width in listOf(1, 8, 64)) {
            val targets = hierarchicalDiagnosticFeedback(feedbackProject(width), ComponentCatalog.registry, 20)
            assertEquals(setOf(emptyList(), listOf("child-instance")), targets.map { it.instancePath }.toSet(), "width=$width")
            assertEquals(setOf("inverter", "buffer"), targets.flatMap { it.componentIds }.toSet(), "width=$width")
        }
    }

    @Test
    fun aWireOnlyChildCanCloseTheParentFeedbackLoop() {
        val original = feedbackProject()
        val child = original.circuit("child")!!.copy(components = original.circuit("child")!!.components
            .filterNot { it.id == "buffer" }, wires = listOf(WireSegment(GridPoint(0, 0), GridPoint(80, 0))))
        val targets = hierarchicalDiagnosticFeedback(original.withCircuit(child), ComponentCatalog.registry, 20)
        assertEquals(1, targets.size)
        assertEquals("main", targets.single().circuitId)
        assertEquals(setOf("inverter"), targets.single().componentIds)
    }

    @Test
    fun nestedInstancesRetainTheirWholeNavigationPath() {
        val original = feedbackProject()
        val child = original.circuit("child")!!
        val wrapper = CircuitDocument("wrapper", components = listOf(
            pin("input", ComponentKind.INPUT, GridPoint(0, 0)),
            pin("output", ComponentKind.OUTPUT, GridPoint(100, 0)),
            symbol("inner", "child", GridPoint(20, 20), "incoming", "outgoing"),
        ), wires = listOf(
            WireSegment(GridPoint(0, 0), GridPoint(20, 0)),
            WireSegment(GridPoint(20, 0), GridPoint(20, 20)),
            WireSegment(GridPoint(20, 60), GridPoint(100, 60)),
            WireSegment(GridPoint(100, 60), GridPoint(100, 0)),
        ))
        val main = original.mainCircuit.copy(components = original.mainCircuit.components.map {
            if (it.kind == ComponentKind.SUBCIRCUIT) symbol(it.id, "wrapper", it.position, "input", "output") else it
        })
        val targets = hierarchicalDiagnosticFeedback(CircuitProject(listOf(main, wrapper, child)), ComponentCatalog.registry, 20)
        assertEquals(setOf(emptyList(), listOf("child-instance", "inner")), targets.map { it.instancePath }.toSet())
    }

    @Test
    fun passiveInoutTerminalsDoNotBecomeCombinationalSelfLoops() {
        val kind = ComponentKind("feedback.passive")
        val registry = ComponentCatalog.registry.withAdditional(listOf(ComponentDefinition(
            kind, "Passive", "#Feedback", isCombinational = true,
            layout = { component, _ -> ComponentLayout(
                listOf(ComponentTerminal("node", component.position, PortDirection.INOUT)),
                ComponentBounds(0, 0, 20, 20),
            ) },
        )))
        val project = CircuitProject(listOf(CircuitDocument("main", components = listOf(
            CircuitComponent("passive", kind, GridPoint(0, 0)),
        ))))
        assertEquals(emptyList(), hierarchicalDiagnosticFeedback(project, registry, 20))
    }

    @Test
    fun hierarchyCyclesAndTheInstanceBudgetTerminate() {
        val recursive = CircuitDocument("main", components = listOf(
            CircuitComponent("self", ComponentKind.SUBCIRCUIT, GridPoint(0, 0), circuitName = "main"),
        ))
        assertEquals(emptyList(), hierarchicalDiagnosticFeedback(CircuitProject(listOf(recursive)), ComponentCatalog.registry, 1))
        assertEquals(emptyList(), hierarchicalDiagnosticFeedback(feedbackProject(), ComponentCatalog.registry, 1))
    }

    @Test
    fun invalidLayoutsAreSkippedButUnexpectedLayoutFailuresPropagate() {
        val original = feedbackProject()
        val unknown = CircuitComponent("unknown", ComponentKind("missing.plugin"), GridPoint(800, 800))
        val project = original.withCircuit(original.mainCircuit.copy(components = original.mainCircuit.components + unknown))
        assertEquals(2, hierarchicalDiagnosticFeedback(project, ComponentCatalog.registry, 20).size)
        val broken = ComponentDefinition(unknown.kind, "Broken", "#Feedback", isCombinational = true,
            layout = { _, _ -> throw IllegalStateException("broken layout") })
        assertFailsWith<IllegalStateException> {
            hierarchicalDiagnosticFeedback(project, ComponentCatalog.registry.withAdditional(listOf(broken)), 20)
        }
    }

    @Test
    fun validatedTopologiesCanBeReusedWithoutChangingTheOrigins() {
        val project = feedbackProject()
        val topologies = project.circuits.associate { it.id to CircuitTopology.elaborate(it) }
        assertEquals(hierarchicalDiagnosticFeedback(project, ComponentCatalog.registry, 20),
            hierarchicalDiagnosticFeedback(project, ComponentCatalog.registry, 20, topologies))
    }

    @Test
    fun anInvalidSymbolCannotJoinDistinctParentNetsThroughTheSameChildPin() {
        val original = feedbackProject()
        val main = original.mainCircuit.copy(components = original.mainCircuit.components.map { component ->
            if (component.kind != ComponentKind.SUBCIRCUIT) component else component.copy(
                explicitPorts = component.explicitPorts.map { it.copy(name = "incoming") },
            )
        })
        assertEquals(emptyList(), hierarchicalDiagnosticFeedback(original.withCircuit(main), ComponentCatalog.registry, 20))
    }

    @Test
    fun aBitwiseOutputFeedingAnotherInputBitDoesNotCreateFeedback() {
        for (kind in listOf(ComponentKind.BUFFER, ComponentKind.NOT)) {
            assertEquals(emptyList(), hierarchicalDiagnosticFeedback(splitBitProject(kind, 1), ComponentCatalog.registry, 20))
        }
    }

    @Test
    fun aBitwiseOutputFeedingTheSameInputBitRetainsFeedback() {
        for (kind in listOf(ComponentKind.BUFFER, ComponentKind.NOT)) {
            val targets = hierarchicalDiagnosticFeedback(splitBitProject(kind, 0), ComponentCatalog.registry, 20)
            assertEquals(1, targets.size)
            assertEquals(setOf("gate"), targets.single().componentIds)
            assertEquals(emptyList(), targets.single().instancePath)
        }
    }

    @Test
    fun aControlledGateEnableCanFeedBackFromAnyOutputBit() {
        val gate = CircuitComponent("gate", ComponentKind.CONTROLLED_BUFFER, GridPoint(100, 100),
            attributes = mapOf("width" to "2"))
        val split = CircuitComponent("split", ComponentKind.SPLITTER, GridPoint(200, 100),
            attributes = mapOf("incoming" to "2", "fanout" to "2"))
        val enable = gate.layout().ports.single { it.name == "enable" }.position
        val document = CircuitDocument("main", components = listOf(gate, split), wires = listOf(
            WireSegment(gate.position, split.position),
            WireSegment(GridPoint(220, 90), GridPoint(240, 90)),
            WireSegment(GridPoint(240, 90), GridPoint(240, 140)),
            WireSegment(GridPoint(240, 140), GridPoint(enable.x, 140)),
            WireSegment(GridPoint(enable.x, 140), enable),
        ))
        val targets = hierarchicalDiagnosticFeedback(CircuitProject(listOf(document)), ComponentCatalog.registry, 20)
        assertEquals(1, targets.size)
        assertEquals(setOf("gate"), targets.single().componentIds)
        assertTrue(DiagnosticTerminalRef("gate", "enable") in targets.single().terminals)
    }

    private fun splitBitProject(kind: ComponentKind, targetBit: Int): CircuitProject {
        val input = CircuitComponent("input-split", ComponentKind.SPLITTER, GridPoint(0, 100),
            attributes = mapOf("incoming" to "2", "fanout" to "2"))
        val output = input.copy(id = "output-split", position = GridPoint(200, 100))
        val gate = CircuitComponent("gate", kind, GridPoint(100, 100), attributes = mapOf("width" to "2"))
        val targetY = if (targetBit == 0) 80 else 90
        val otherY = if (targetBit == 0) 90 else 80
        val document = CircuitDocument("main", components = listOf(input, output, gate,
            pin("external", ComponentKind.INPUT, GridPoint(20, otherY))), wires = listOf(
            WireSegment(input.position, gate.layout().inputs.single().position),
            WireSegment(gate.position, output.position),
            WireSegment(GridPoint(220, 80), GridPoint(220, 40)),
            WireSegment(GridPoint(220, 40), GridPoint(40, 40)),
            WireSegment(GridPoint(40, 40), GridPoint(40, targetY)),
            WireSegment(GridPoint(40, targetY), GridPoint(20, targetY)),
        ))
        return CircuitProject(listOf(document))
    }

    private fun feedbackProject(width: Int = 1): CircuitProject {
        val child = CircuitDocument("child", components = listOf(
            pin("incoming", ComponentKind.INPUT, GridPoint(0, 0), width),
            CircuitComponent("buffer", ComponentKind.BUFFER, GridPoint(50, 0), attributes = mapOf("width" to width.toString())),
            pin("outgoing", ComponentKind.OUTPUT, GridPoint(80, 0), width),
        ), wires = listOf(
            WireSegment(GridPoint(0, 0), GridPoint(30, 0)),
            WireSegment(GridPoint(50, 0), GridPoint(80, 0)),
        ))
        val main = CircuitDocument("main", components = listOf(
            CircuitComponent("inverter", ComponentKind.NOT, GridPoint(100, 100), attributes = mapOf("width" to width.toString())),
            symbol("child-instance", "child", GridPoint(200, 100), "incoming", "outgoing", width),
        ), wires = listOf(
            WireSegment(GridPoint(100, 100), GridPoint(200, 100)),
            WireSegment(GridPoint(200, 140), GridPoint(70, 140)),
            WireSegment(GridPoint(70, 140), GridPoint(70, 100)),
        ))
        return CircuitProject(listOf(main, child))
    }

    private fun pin(id: String, kind: ComponentKind, point: GridPoint, width: Int = 1) =
        CircuitComponent(id, kind, point, attributes = mapOf("width" to width.toString()))

    private fun symbol(id: String, name: String, point: GridPoint, input: String, output: String, width: Int = 1) =
        CircuitComponent(id, ComponentKind.SUBCIRCUIT, point, circuitName = name, explicitPorts = listOf(
            ComponentPort(input, GridPoint(0, 0), PortDirection.INPUT, width),
            ComponentPort(output, GridPoint(0, 40), PortDirection.OUTPUT, width),
        ))
}
