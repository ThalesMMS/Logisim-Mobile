package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import dev.logisim.mobile.core.localization.AppLanguage

class CircuitDiagnosticsTest {
    private fun pin(id: String, point: GridPoint, width: Int = 1, output: Boolean = false) =
        CircuitComponent(id, if (output) ComponentKind.OUTPUT else ComponentKind.INPUT, point,
            attributes = mapOf("width" to width.toString()))

    @Test
    fun widthMismatchKeepsIdentityAcrossSplitsAndOrderingChanges() {
        val from = pin("source", GridPoint(0, 0), 8)
        val to = pin("sink", GridPoint(100, 0), 4, output = true)
        val original = CircuitDocument("bus", listOf(from, to), listOf(WireSegment(from.position, to.position)), id = "stable-bus")
        val split = original.copy(components = original.components.reversed(), wires = listOf(
            WireSegment(GridPoint(100, 0), GridPoint(50, 0)), WireSegment(GridPoint(50, 0), GridPoint(0, 0))))
        val first = CircuitDiagnosticAnalyzer.analyze(original).diagnostics.single { it.code == DiagnosticCode.WIDTH_MISMATCH }
        val second = CircuitDiagnosticAnalyzer.analyze(split).diagnostics.single { it.code == DiagnosticCode.WIDTH_MISMATCH }
        assertEquals(first.id, second.id)
        assertEquals(first.id, CircuitDiagnosticAnalyzer.analyze(original.copy(name = "renamed"))
            .diagnostics.single { it.code == DiagnosticCode.WIDTH_MISMATCH }.id)
        val editor = CircuitEditor(original)
        editor.splitWire(GridPoint(50, 0))
        editor.connect(from.position, to.position)
        val normalized = CircuitTopology.elaborate(editor.document)
        assertEquals(first.id, CircuitDiagnosticAnalyzer.analyze(editor.document).diagnostics
            .single { it.code == DiagnosticCode.WIDTH_MISMATCH }.id)
        assertEquals(normalized.bitAt(from.position, 3)?.id, normalized.bitAt(to.position, 3)?.id)
        assertEquals(2, editor.document.wires.size)
        assertEquals(setOf("source", "sink"), first.target.componentIds)
        assertEquals(DiagnosticCategory.ELECTRICAL, first.category)
        assertEquals(setOf(4, 8), CircuitTopology.elaborate(original).nets.single().declaredWidths)
        assertEquals("The connected terminals disagree on width: 4 / 8 bits.", first.notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Os terminais conectados têm larguras diferentes: 4 / 8 bits.", first.notice.resolve(AppLanguage.PORTUGUESE))
    }

    @Test
    fun partialDriverConflictUsesRawDriverValuesAndNamesTheConflictingBit() {
        val a = pin("a", GridPoint(0, 0), 8)
        val b = pin("b", GridPoint(100, 0), 8)
        val sink = pin("sink", GridPoint(50, 0), 8, output = true)
        val document = CircuitDocument("conflict", listOf(a, b, sink), listOf(WireSegment(a.position, b.position)))
        val state = CircuitSimulator().settle(document, vectorInputs = mapOf(
            "a" to LogicVector.of(8, 0x0fuL), "b" to LogicVector.of(8, 0x07uL)))
        val report = CircuitDiagnosticAnalyzer.analyze(document, state)
        val conflict = report.diagnostics.single { it.code == DiagnosticCode.DRIVER_CONFLICT }
        assertEquals(LogicValue.ERROR, state.portVectors.getValue(sink.position).bit(3))
        assertEquals("3", conflict.notice.arguments["bit"])
        assertEquals(setOf("a", "b"), conflict.target.componentIds)
        assertEquals(2, conflict.target.terminals.size)
        assertFalse(report.diagnostics.any { it.code == DiagnosticCode.VALUE_ERROR })
    }

    @Test
    fun splitterConflictPointsToDriversOnBothSidesOfTheSplit() {
        val wide = pin("wide", GridPoint(0, 100), 4)
        val narrow = pin("narrow", GridPoint(120, 60))
        val splitter = CircuitComponent("split", ComponentKind.SPLITTER, GridPoint(100, 100),
            attributes = mapOf("incoming" to "4", "fanout" to "4"))
        val document = CircuitDocument("split", listOf(wide, narrow, splitter),
            listOf(WireSegment(wide.position, splitter.position)))
        val state = CircuitSimulator().settle(document, vectorInputs = mapOf(
            "wide" to LogicVector.of(4, 5uL), "narrow" to LogicVector.low(1)))
        val diagnostic = CircuitDiagnosticAnalyzer.analyze(document, state).diagnostics
            .single { it.code == DiagnosticCode.DRIVER_CONFLICT }
        assertEquals(setOf("wide", "narrow"), diagnostic.target.componentIds)
        assertTrue(diagnostic.target.anchors.containsAll(listOf(wide.position, narrow.position)))
    }

    @Test
    fun weakPullConflictsAreSelectableAndStrongDriversOverrideThem() {
        fun definition(name: String, value: LogicVector) = ComponentDefinition(ComponentKind(name), name, "#Test",
            layout = { component, _ -> ComponentLayout(
                listOf(ComponentTerminal("line", component.position, PortDirection.INOUT, 0)),
                ComponentBounds(component.position.x, component.position.y, component.position.x + 10, component.position.y + 10)) },
            pullDrivers = { _, _ -> mapOf("line" to value) })
        val up = definition("PULL_UP", LogicVector.high(1))
        val down = definition("PULL_DOWN", LogicVector.low(1))
        val registry = ComponentCatalog.registry.withAdditional(listOf(up, down))
        val document = CircuitDocument("pulls", listOf(
            CircuitComponent("up", up.kind, GridPoint(0, 0)),
            CircuitComponent("down", down.kind, GridPoint(100, 0)),
            pin("sink", GridPoint(50, 0), 8, output = true)), listOf(WireSegment(GridPoint(0, 0), GridPoint(100, 0))))
        val resolved = CircuitNetworks(document, registry).resolve(emptyMap())
        val weakErrors = CircuitDiagnosticAnalyzer.analyze(document, SimulationState(portVectors = resolved), registry).diagnostics
        assertEquals(8, weakErrors.size)
        assertTrue(weakErrors.all { it.code == DiagnosticCode.DRIVER_CONFLICT })
        assertTrue(weakErrors.all { it.target.componentIds == setOf("up", "down") })
        val source = pin("strong", GridPoint(0, 0), 8)
        val drivenDocument = document.copy(components = document.components + source)
        val strongValue = LogicVector.low(8)
        val drivenState = SimulationState(
            portVectors = CircuitNetworks(drivenDocument, registry).resolve(mapOf(source.position to strongValue)),
            componentOutputs = mapOf("strong" to mapOf(source.layout(registry).outputs.single().name to strongValue)))
        assertEquals(0uL, drivenState.portVectors.getValue(GridPoint(50, 0)).toULongOrNull())
        assertTrue(CircuitDiagnosticAnalyzer.analyze(drivenDocument, drivenState, registry).diagnostics.isEmpty())
    }

    @Test
    fun editUndoAndRemoveRecomputeDiagnosticsWithoutReusingAnOldGraph() {
        val source = pin("source", GridPoint(0, 0), 8)
        val sink = pin("sink", GridPoint(100, 0), 8, output = true)
        val editor = CircuitEditor(CircuitDocument("editing", listOf(source, sink),
            listOf(WireSegment(source.position, sink.position))))
        assertTrue(CircuitDiagnosticAnalyzer.analyze(editor.document).diagnostics.isEmpty())
        editor.updateComponent("sink") { it.copy(attributes = mapOf("width" to "4")) }
        val mismatch = CircuitDiagnosticAnalyzer.analyze(editor.document).diagnostics.single()
        assertEquals(DiagnosticCode.WIDTH_MISMATCH, mismatch.code)
        editor.undo()
        assertTrue(CircuitDiagnosticAnalyzer.analyze(editor.document).diagnostics.isEmpty())
        editor.remove("source")
        assertFalse(CircuitDiagnosticAnalyzer.analyze(editor.document).diagnostics.any { it.code == DiagnosticCode.WIDTH_MISMATCH })
    }

    @Test
    fun unconnectedInputIsAWarningWithAStableNamedTerminalTarget() {
        val gate = CircuitComponent("gate", ComponentKind.NOT, GridPoint(100, 100))
        val report = CircuitDiagnosticAnalyzer.analyze(CircuitDocument("floating", listOf(gate)))
        val warning = report.diagnostics.single { it.code == DiagnosticCode.UNCONNECTED_INPUT }
        assertEquals(DiagnosticSeverity.WARNING, warning.severity)
        assertEquals(setOf("gate"), warning.target.componentIds)
        assertEquals(setOf(GridPoint(70, 100)), warning.target.anchors)
        assertFalse(CircuitDiagnosticAnalyzer.analyze(CircuitDocument("floating", listOf(gate)),
            includeUnconnectedInputs = false).diagnostics.any { it.code == DiagnosticCode.UNCONNECTED_INPUT })
    }

    @Test
    fun unknownTypeAndInvalidReferenceRemainDocumentErrors() {
        val unknown = CircuitComponent("unknown", ComponentKind("MISSING_PLUGIN"), GridPoint(0, 0))
        val missing = CircuitComponent("missing", ComponentKind.SUBCIRCUIT, GridPoint(100, 0), circuitName = "absent")
        val report = CircuitDiagnosticAnalyzer.analyze(CircuitDocument("main", listOf(unknown, missing)),
            availableCircuitNames = setOf("main"))
        assertEquals(setOf(DiagnosticCode.UNKNOWN_COMPONENT, DiagnosticCode.INVALID_REFERENCE), report.diagnostics.map { it.code }.toSet())
        assertEquals(setOf("unknown", "missing"), report.diagnostics.flatMap { it.target.componentIds }.toSet())
        assertTrue(report.diagnostics.all { it.category == DiagnosticCategory.DOCUMENT })
    }

    @Test
    fun recursiveReferencesAreFiniteAndPointAtTheOffendingInstance() {
        val recursive = CircuitDocument("recursive", listOf(CircuitComponent("self", ComponentKind.SUBCIRCUIT,
            GridPoint(0, 0), circuitName = "recursive")))
        val report = CircuitDiagnosticAnalyzer.analyze(CircuitProject(listOf(recursive)))
        val error = report.diagnostics.single { it.code == DiagnosticCode.RECURSIVE_REFERENCE }
        assertEquals(setOf("self"), error.target.componentIds)
        assertEquals("recursive", error.target.circuitId)
    }

    @Test
    fun unusedDefinitionsValidateCyclesAndDoNotBorrowTheMainSimulation() {
        val unused = CircuitDocument("unused", listOf(
            CircuitComponent("self", ComponentKind.SUBCIRCUIT, GridPoint(100, 0), circuitName = "unused")))
        val report = CircuitDiagnosticAnalyzer.analyze(CircuitProject(listOf(CircuitDocument("main"), unused)),
            SimulationState(oscillating = true))
        val unusedErrors = report.diagnostics.filter { it.target.circuitId == "unused" }
        assertEquals(listOf(DiagnosticCode.RECURSIVE_REFERENCE), unusedErrors.map { it.code })
        assertEquals(setOf("self"), unusedErrors.flatMap { it.target.componentIds }.toSet())
        assertEquals(1, report.diagnostics.count { it.code == DiagnosticCode.EXECUTION_LIMIT })
    }

    @Test
    fun twoInstancesHaveDifferentDiagnosticIdentitiesAndNavigablePaths() {
        val child = CircuitDocument("child", listOf(CircuitComponent("gate", ComponentKind.NOT, GridPoint(50, 50))))
        fun instance(id: String) = CircuitComponent(id, ComponentKind.SUBCIRCUIT, GridPoint(0, 0), circuitName = "child")
        val project = CircuitProject(listOf(CircuitDocument("main", listOf(instance("first"), instance("second"))), child))
        val report = CircuitDiagnosticAnalyzer.analyze(project)
        assertFalse(report.diagnostics.any { it.code == DiagnosticCode.UNKNOWN_COMPONENT })
        val diagnostics = report.diagnostics.filter { it.code == DiagnosticCode.UNCONNECTED_INPUT }
        assertEquals(2, diagnostics.size)
        assertNotEquals(diagnostics[0].id, diagnostics[1].id)
        assertEquals(setOf(listOf("first"), listOf("second")), diagnostics.map { it.target.instancePath }.toSet())
    }

    @Test
    fun actualFeedbackAndAnExecutionLimitHaveDifferentCodes() {
        val definition = ComponentCatalog.registry.requireDefinition(ComponentKind.NOT).copy(isCombinational = true)
        val registry = ComponentCatalog.registry.withDefinitions(listOf(definition))
        val gate = CircuitComponent("ring", ComponentKind.NOT, GridPoint(100, 100))
        val document = CircuitDocument("feedback", listOf(gate), listOf(WireSegment(GridPoint(70, 100), gate.position)))
        val state = SimulationState(oscillating = true)
        val feedback = CircuitDiagnosticAnalyzer.analyze(document, state, registry).diagnostics
            .single { it.category == DiagnosticCategory.EXECUTION }
        assertEquals(DiagnosticCode.OSCILLATION, feedback.code)
        assertEquals(setOf("ring"), feedback.target.componentIds)
        val exhausted = CircuitDiagnosticAnalyzer.analyze(CircuitDocument("empty"), state).diagnostics.single()
        assertEquals(DiagnosticCode.EXECUTION_LIMIT, exhausted.code)
        val passive = CircuitDocument("passive", listOf(
            CircuitComponent("tunnel", ComponentKind.TUNNEL, GridPoint(0, 0), label = "bus"),
            CircuitComponent("splitter", ComponentKind.SPLITTER, GridPoint(100, 0))))
        val passiveLimit = CircuitDiagnosticAnalyzer.analyze(passive, state).diagnostics
            .single { it.category == DiagnosticCategory.EXECUTION }
        assertEquals(DiagnosticCode.EXECUTION_LIMIT, passiveLimit.code)
        assertTrue(passiveLimit.target.componentIds.isEmpty())
    }

    @Test
    fun importErrorsDoNotPretendToBeElectricalConflicts() {
        val error = CircuitDiagnosticAnalyzer.importFailure("Malformed XML", "chosen-file")
        assertEquals(DiagnosticCode.IMPORT_ERROR, error.code)
        assertEquals(DiagnosticCategory.DOCUMENT, error.category)
        assertEquals("chosen-file", error.target.circuitId)
        assertTrue(error.target.componentIds.isEmpty())
    }

    @Test
    fun analysisBudgetIsExplicitInsteadOfFreezingOnAnExpandedHierarchy() {
        val child = CircuitDocument("child")
        val main = CircuitDocument("main", (1..20).map { index -> CircuitComponent("i$index",
            ComponentKind.SUBCIRCUIT, GridPoint(index * 10, 0), circuitName = "child") })
        val report = CircuitDiagnosticAnalyzer.analyze(CircuitProject(listOf(main, child)), maxInstances = 3)
        assertTrue(report.limited)
        assertEquals(1, report.diagnostics.count { it.code == DiagnosticCode.ANALYSIS_LIMIT })
    }
}
