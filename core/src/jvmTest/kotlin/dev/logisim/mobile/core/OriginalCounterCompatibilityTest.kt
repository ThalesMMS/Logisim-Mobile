package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class OriginalCounterCompatibilityTest {
    private val simulator = CircuitSimulator()
    private val registry = ComponentCatalog.registry
    private val fixture = assertNotNull(javaClass.getResourceAsStream("/original/counter.circ"))
        .bufferedReader().use { it.readText() }

    @Test fun originalCounterPreservesAllFourLoadCountOperations() {
        val expected = listOf(0uL to 0uL, 1uL to 2uL, 5uL to 5uL, 15uL to 14uL)
        for ((index, controls) in controls().withIndex()) {
            val project = project(load = controls.first, count = controls.second)
            val first = firstEdge(project)
            assertEquals(expected[index].first, output(project, first), "First edge with $controls")
            val second = nextEdge(project, first)
            assertEquals(expected[index].second, output(project, second), "Second edge with $controls")
        }
    }

    @Test fun originalDisabledCounterRetainsItsLoadedValue() {
        val load = project(load = 1, count = 0)
        val loaded = firstEdge(load)
        assertEquals(5uL, output(load, loaded))
        val hold = project(load = 0, count = 0)
        val held = nextEdge(hold, loaded)
        assertEquals(5uL, output(hold, held))
    }

    @Test fun originalCounterControlsSurviveSavingAndReopening() {
        val expected = listOf(0uL, 1uL, 5uL, 15uL)
        for ((index, controls) in controls().withIndex()) {
            val original = project(load = controls.first, count = controls.second)
            val reopened = assertIs<CircProjectImportResult.Success>(
                CircCodec.readProject(CircCodec.writeProject(original))).project
            assertEquals("2.7.1", reopened.sourceVersion)
            assertEquals(expected[index], output(reopened, firstEdge(reopened)), "$controls after round trip")
        }
    }

    @Test fun evolutionCounterKeepsLoadPriorityAndSeparateUpDownControl() {
        for (up in 0..1) {
            val expected = listOf(0uL, if (up == 0) 15uL else 1uL, 5uL, 5uL)
            for ((index, controls) in controls().withIndex()) {
                val original = project("5.1.0-dev", controls.first, controls.second, up = up)
                val reopened = assertIs<CircProjectImportResult.Success>(
                    CircCodec.readProject(CircCodec.writeProject(original))).project
                assertEquals(expected[index], output(original, firstEdge(original)), "Evolution $controls, up=$up")
                assertEquals(expected[index], output(reopened, firstEdge(reopened)), "Reopened Evolution $controls, up=$up")
            }
        }
    }

    @Test fun originalDecrementAppliesEveryGoalModeAtZero() {
        val expected = mapOf("wrap" to 9uL, "stay" to 0uL, "continue" to 15uL, "load" to 5uL)
        for ((goal, value) in expected) {
            val project = project(attributes = mapOf("max" to "0x9", "ongoal" to goal))
            assertEquals(value, output(project, firstEdge(project)), goal)
        }
    }

    @Test fun originalCarryUsesDecrementOnlyWhenBothControlsAreHigh() {
        val countdown = project()
        var state = firstEdge(countdown)
        assertEquals("0", carry(countdown, state))
        repeat(15) { state = nextEdge(countdown, state) }
        assertEquals(0uL, output(countdown, state))
        assertEquals("1", carry(countdown, state))
        val loadZero = project(load = 1, count = 0).let { project ->
            val document = project.mainCircuit
            project.copy(circuits = listOf(document.copy(components = document.components.map {
                if (it.position == GridPoint(220, 200)) it.copy(attributes = it.attributes + ("value" to "0x0")) else it
            })))
        }
        val loaded = firstEdge(loadZero)
        assertEquals(0uL, output(loadZero, loaded))
        assertEquals("0", carry(loadZero, loaded))
    }

    @Test fun originalLoadMasksAtMaximumAndTreatsUndefinedDataAsZero() {
        val masked = project(load = 1, count = 0, attributes = mapOf("max" to "0x9"))
        val document = masked.mainCircuit
        val withLargeData = masked.copy(circuits = listOf(document.copy(components = document.components.map {
            if (it.position == GridPoint(220, 200)) it.copy(attributes = it.attributes + ("value" to "0xe")) else it
        })))
        assertEquals(8uL, output(withLargeData, firstEdge(withLargeData)))
        val withoutData = masked.copy(circuits = listOf(document.copy(
            components = document.components.filter { it.position != GridPoint(220, 200) })))
        assertEquals(0uL, output(withoutData, firstEdge(withoutData)))
    }

    private fun controls() = listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1)

    private fun project(
        source: String = "2.7.1",
        load: Int = 1,
        count: Int = 1,
        attributes: Map<String, String> = emptyMap(),
        up: Int? = null,
    ): CircuitProject {
        val imported = assertIs<CircProjectImportResult.Success>(
            CircCodec.readProject(fixture.replace("source=\"2.7.1\"", "source=\"$source\""))).project
        val document = imported.mainCircuit
        val components = document.components.map { component ->
            when {
                component.kind == ComponentKind.COUNTER -> component.copy(attributes = component.attributes + attributes)
                component.position == GridPoint(220, 190) -> component.copy(attributes = component.attributes + ("value" to "$load"))
                component.position == GridPoint(220, 210) -> component.copy(attributes = component.attributes + ("value" to "$count"))
                else -> component
            }
        }.toMutableList()
        if (up != null) {
            val counter = components.single { it.kind == ComponentKind.COUNTER }
            val port = registry.layout(counter).ports.single { it.name == "up" }
            components += registry.create(ComponentKind.CONSTANT, "direction", port.position, mapOf("value" to "$up"))
        }
        return imported.copy(circuits = listOf(document.copy(components = components)))
    }

    private fun firstEdge(project: CircuitProject) =
        simulator.settle(project, simulator.settle(project), tick = true)

    private fun nextEdge(project: CircuitProject, previous: SimulationState) =
        simulator.settle(project, simulator.settle(project, previous, tick = true), tick = true)

    private fun output(project: CircuitProject, state: SimulationState): ULong? =
        state.vectorValues.getValue(project.mainCircuit.components.single { it.label == "Q" }.id).toULongOrNull()

    private fun carry(project: CircuitProject, state: SimulationState): String =
        state.componentOutputs.getValue(project.mainCircuit.components.single { it.kind == ComponentKind.COUNTER }.id)
            .getValue("carry").display
}
