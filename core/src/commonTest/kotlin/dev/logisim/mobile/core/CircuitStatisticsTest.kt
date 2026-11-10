package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CircuitStatisticsTest {
    @Test
    fun componentOccurrencesFollowTheSelectedCircuitAndExcludeUnreachableDefinitions() {
        val main = CircuitDocument("main", components = listOf(
            subcircuit("first", "child"), subcircuit("second", "child"),
        ))
        val child = CircuitDocument("child", components = listOf(
            CircuitComponent("source", ComponentKind.CONSTANT, GridPoint(100, 100)),
        ))
        val unused = child.copy(name = "unused", id = "unused")
        val project = CircuitProject(listOf(main, child, unused))
        val mainStats = CircuitComponentStatistics.measure(project)
        assertEquals(listOf(
            ComponentStatisticsRow(null, "child", ComponentOccurrenceCounts(2, 2, 2)),
            ComponentStatisticsRow("#Wiring", "Constant", ComponentOccurrenceCounts(0, 1, 2), registeredComponent = true),
        ), mainStats.rows)
        assertEquals(listOf(
            ComponentStatisticsRow("#Wiring", "Constant", ComponentOccurrenceCounts(1, 1, 1), registeredComponent = true),
        ), CircuitComponentStatistics.measure(project, "child").rows)
    }

    @Test
    fun textAnnotationsAreNotCountedLikeTheDesktopStatistics() {
        val main = CircuitDocument("main", components = listOf(
            CircuitComponent("note", ComponentKind.TEXT, GridPoint(40, 40), label = "note"),
            CircuitComponent("source", ComponentKind.CONSTANT, GridPoint(100, 100)),
        ))
        val stats = CircuitComponentStatistics.measure(CircuitProject(listOf(main)))
        assertEquals(listOf(
            ComponentStatisticsRow("#Wiring", "Constant", ComponentOccurrenceCounts(1, 1, 1), registeredComponent = true),
        ), stats.rows)
        assertEquals(ComponentOccurrenceCounts(1, 1, 1), stats.totalWithSubcircuits)
    }

    @Test
    fun componentOccurrencesDistinguishExternalNamesAndKeepPinTypesGrouped() {
        val main = CircuitDocument("main", components = listOf(
            CircuitComponent("not", ComponentKind.NOT, GridPoint(100, 100)),
            subcircuit("external", "custom"),
            CircuitComponent("input", ComponentKind.INPUT, GridPoint(100, 200)),
            CircuitComponent("output", ComponentKind.OUTPUT, GridPoint(100, 300)),
        ))
        val rows = CircuitComponentStatistics.measure(
            CircuitProject(listOf(main, CircuitDocument("custom"))), main.name, ComponentCatalog.registry,
            mapOf("custom" to ExternalComponentType("#Gates", "NOT Gate")),
            checkCancellation = {},
        ).rows

        assertEquals(listOf(
            ComponentStatisticsRow("#Gates", "NOT Gate", ComponentOccurrenceCounts(1, 1, 1), registeredComponent = true),
            ComponentStatisticsRow("#Gates", "NOT Gate", ComponentOccurrenceCounts(1, 1, 1)),
            ComponentStatisticsRow("#Wiring", "Pin", ComponentOccurrenceCounts(2, 2, 2), registeredComponent = true),
        ), rows)
    }

    @Test
    fun countsCurrentComponentsWiresAndTerminals() {
        val document = connectedPins()
        val stats = CircuitStatistics.measure(document)
        assertEquals("main", stats.circuitId)
        assertEquals(2, stats.componentCount)
        assertEquals(mapOf("INPUT" to 1, "OUTPUT" to 1), stats.componentsByType)
        assertEquals(1, stats.wireSegmentCount)
        assertEquals(100L, stats.wireLength)
        assertEquals(2, stats.terminalCount)
        assertEquals(2, stats.terminalLocationCount)
        assertEquals(0, stats.junctionCount)
        assertEquals(1, stats.netCount)
    }

    @Test
    fun followsRealEditsUndoAndDeletionWithoutStaleSnapshots() {
        val editor = CircuitEditor(connectedPins())
        editor.remove("out")
        assertEquals(1, CircuitStatistics.measure(editor.document).componentCount)
        assertEquals(1, CircuitStatistics.measure(editor.document).terminalCount)
        assertTrue(editor.undo())
        assertEquals(2, CircuitStatistics.measure(editor.document).componentCount)
        assertEquals(2, CircuitStatistics.measure(editor.document).terminalCount)
        editor.removeWire(editor.document.wires.single())
        assertEquals(0, CircuitStatistics.measure(editor.document).wireSegmentCount)
        assertEquals(2, CircuitStatistics.measure(editor.document).netCount)
        assertTrue(editor.undo())
        assertEquals(1, CircuitStatistics.measure(editor.document).netCount)
        assertTrue(editor.redo())
        assertEquals(0L, CircuitStatistics.measure(editor.document).wireLength)
    }

    @Test
    fun splittingWirePreservesNetAndLengthWhileUpdatingSegmentCount() {
        val editor = CircuitEditor(connectedPins())
        editor.splitWire(GridPoint(50, 0))
        val divided = CircuitStatistics.measure(editor.document)
        assertEquals(2, divided.wireSegmentCount)
        assertEquals(100L, divided.wireLength)
        assertEquals(1, divided.netCount)
        assertEquals(0, divided.junctionCount)
        assertTrue(editor.undo())
        assertEquals(1, CircuitStatistics.measure(editor.document).wireSegmentCount)
    }

    @Test
    fun countsJunctionsButNotUnmarkedCrossings() {
        val crossing = CircuitDocument(wires = listOf(
            WireSegment(GridPoint(0, 50), GridPoint(100, 50)),
            WireSegment(GridPoint(50, 0), GridPoint(50, 100)),
        ))
        val plain = CircuitStatistics.measure(crossing)
        assertEquals(0, plain.junctionCount)
        assertEquals(2, plain.netCount)
        val branch = crossing.copy(wires = crossing.wires +
            WireSegment(GridPoint(50, 50), GridPoint(100, 50)))
        val joined = CircuitStatistics.measure(branch)
        assertEquals(1, joined.junctionCount)
        assertEquals(1, joined.netCount)
    }

    @Test
    fun nominalRegisteredComponentsAppearInTheTypeCounts() {
        val kind = ComponentKind("statistics.custom")
        val registry = ComponentCatalog.registry.withAdditional(listOf(ComponentDefinition(
            kind, "Statistics custom", "#Statistics", layout = { component, _ -> ComponentLayout(
                listOf(ComponentTerminal("in", component.position, PortDirection.INPUT, 8)),
                ComponentBounds(component.position.x, component.position.y, component.position.x + 20, component.position.y + 20),
            ) },
        )))
        val stats = CircuitStatistics.measure(CircuitDocument(components = listOf(
            CircuitComponent("custom", kind, GridPoint(0, 0)),
        )), registry)
        assertEquals(mapOf("statistics.custom" to 1), stats.componentsByType)
        assertEquals(1, stats.terminalCount)
        assertEquals(1, stats.netCount)
    }

    @Test
    fun unknownComponentsKeepDrawingCountsAndMarkTopologyAsPartial() {
        val unknown = ComponentKind("statistics.missing")
        val document = connectedPins().copy(components = listOf(
            CircuitComponent("in", ComponentKind.INPUT, GridPoint(0, 0)),
            CircuitComponent("missing", unknown, GridPoint(100, 0)),
        ))
        val stats = CircuitStatistics.measure(document)
        assertEquals(2, stats.componentCount)
        assertEquals(1, stats.terminalCount)
        assertEquals(1, stats.netCount)
        assertEquals(setOf("missing"), stats.unresolvedComponentIds)
        assertTrue(stats.hasIncompleteTopology)
        assertEquals(mapOf("INPUT" to 1, "statistics.missing" to 1), stats.componentsByType)
    }

    @Test
    fun unexpectedLayoutBugsAreNotSilencedAsUnsupportedComponents() {
        val kind = ComponentKind("statistics.broken")
        val registry = ComponentCatalog.registry.withAdditional(listOf(ComponentDefinition(
            kind, "Broken", "#Statistics", layout = { _, _ -> throw IllegalStateException("broken") },
        )))
        assertFailsWith<IllegalStateException> {
            CircuitStatistics.measure(CircuitDocument(components = listOf(
                CircuitComponent("broken", kind, GridPoint(0, 0)),
            )), registry)
        }
    }

    @Test
    fun expandsRepeatedAndNestedInstancesWithoutCountingTheSymbolsAsPrimitives() {
        val leaf = CircuitDocument("leaf", components = listOf(
            CircuitComponent("register", ComponentKind.REGISTER, GridPoint(100, 100)),
        ))
        val child = CircuitDocument("child", components = listOf(
            CircuitComponent("and", ComponentKind.AND, GridPoint(100, 100)),
            subcircuit("nested", "leaf"),
        ), wires = listOf(
            WireSegment(GridPoint(0, 0), GridPoint(20, 0)),
            WireSegment(GridPoint(20, 0), GridPoint(20, 20)),
        ))
        val main = CircuitDocument("main", components = listOf(
            subcircuit("first", "child"), subcircuit("second", "child"),
        ), wires = listOf(WireSegment(GridPoint(0, 0), GridPoint(10, 0))))
        val stats = CircuitStatistics.measure(CircuitProject(listOf(main, child, leaf)))
        assertEquals(3, stats.circuitCount)
        assertEquals(5L, stats.definitionComponentCount)
        assertEquals(3L, stats.definitionWireSegmentCount)
        assertEquals(50L, stats.definitionWireLength)
        val expanded = assertIs<CircuitExpansionResult.Complete>(stats.expansion).statistics
        assertEquals(5L, expanded.instanceCount)
        assertEquals(mapOf("child" to 2L, "leaf" to 2L, "main" to 1L), expanded.instancesByCircuit)
        assertEquals(4L, expanded.componentCount)
        assertEquals(mapOf("AND" to 2L, "REGISTER" to 2L), expanded.componentsByType)
        assertEquals(5L, expanded.wireSegmentCount)
        assertEquals(90L, expanded.wireLength)
    }

    @Test
    fun recursionReturnsAStableNavigableProblemInsteadOfExpandingForever() {
        val first = CircuitDocument("first", components = listOf(subcircuit("to-second", "second")))
        val second = CircuitDocument("second", components = listOf(subcircuit("to-first", "first")))
        val project = CircuitProject(listOf(first, second))
        val result = assertIs<CircuitExpansionResult.Invalid>(CircuitStatistics.measure(project).expansion)
        val problem = result.problems.single()
        assertEquals(CircuitExpansionProblemKind.RECURSION, problem.kind)
        assertEquals("second", problem.circuitId)
        assertEquals(listOf("to-second", "to-first"), problem.instancePath)
        assertEquals("to-first", problem.componentId)
        val moved = project.withCircuit(second.copy(components = second.components.map { it.copy(position = GridPoint(500, 100)) }))
        val afterMove = assertIs<CircuitExpansionResult.Invalid>(CircuitStatistics.measure(moved).expansion)
        assertEquals(problem.id, afterMove.problems.single().id)
    }

    @Test
    fun exponentialHierarchyUsesMemoizedCountsAndReportsLongOverflow() {
        val circuits = (0..63).map { level -> CircuitDocument("level$level", components =
            if (level == 63) listOf(CircuitComponent("one", ComponentKind.CONSTANT, GridPoint(0, 0)))
            else listOf(subcircuit("a", "level${level + 1}"), subcircuit("b", "level${level + 1}"))) }
        val expansion = CircuitStatistics.measure(CircuitProject(circuits)).expansion
        val invalid = assertIs<CircuitExpansionResult.Invalid>(expansion)
        assertTrue(invalid.problems.any { it.kind == CircuitExpansionProblemKind.COUNT_OVERFLOW })
    }

    @Test
    fun definitionStatisticsFollowRenameAndRemoval() {
        val main = CircuitDocument("main", id = "main-id")
        val unused = CircuitDocument("unused", id = "stable-id", components = listOf(
            CircuitComponent("input", ComponentKind.INPUT, GridPoint(0, 0)),
        ))
        val project = CircuitProject(listOf(main, unused)).renameCircuit("unused", "renamed")
        val renamed = CircuitStatistics.measure(project)
        assertEquals("renamed", renamed.circuits.getValue("stable-id").circuitName)
        assertEquals(1L, renamed.definitionComponentCount)
        val removed = CircuitStatistics.measure(project.removeCircuit("renamed"))
        assertEquals(1, removed.circuitCount)
        assertEquals(0L, removed.definitionComponentCount)
    }

    private fun connectedPins() = CircuitDocument("main", components = listOf(
        CircuitComponent("in", ComponentKind.INPUT, GridPoint(0, 0)),
        CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(100, 0)),
    ), wires = listOf(WireSegment(GridPoint(0, 0), GridPoint(100, 0))))

    private fun subcircuit(id: String, name: String) =
        CircuitComponent(id, ComponentKind.SUBCIRCUIT, GridPoint(200, 200), circuitName = name)
}
