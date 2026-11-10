package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CircuitProjectTest {
    private val child = CircuitDocument("child", listOf(CircuitComponent("input", ComponentKind.INPUT, GridPoint(0, 0))))
    private val main = CircuitDocument("main", listOf(CircuitComponent("instance", ComponentKind.SUBCIRCUIT, GridPoint(50, 50), circuitName = "child", explicitPorts = listOf(ComponentPort("input", GridPoint(-10, 0), PortDirection.INPUT)))))

    @Test fun renamesReferencesWithoutChangingIdentities() {
        val project = CircuitProject(listOf(main, child), "main").renameCircuit("child", "renamed")
        assertEquals("child", project.circuit("renamed")?.id)
        assertEquals("renamed", project.mainCircuit.components.single().circuitName)
        assertEquals(GridPoint(40, 50) to child.components.single(), project.subcircuitPortBindings(project.mainCircuit.components.single()).single())
    }

    @Test fun validatesProjectAndProtectsUsedDefinitions() {
        assertFailsWith<IllegalArgumentException> { CircuitProject(listOf(main)) }
        assertFailsWith<IllegalArgumentException> { CircuitProject(listOf(child, child)) }
        val project = CircuitProject(listOf(main, child), "main")
        assertFailsWith<IllegalArgumentException> { project.removeCircuit("child") }
        assertFailsWith<IllegalArgumentException> { project.removeCircuit("main") }
        assertFailsWith<IllegalArgumentException> { project.renameCircuit("child", "main") }
        assertFailsWith<IllegalArgumentException> { project.reorderCircuits(listOf("main", "main")) }
    }

    @Test fun reordersAndReplacesCircuits() {
        val project = CircuitProject(listOf(main, child), "main").reorderCircuits(listOf("child", "main"))
        val updated = project.withCircuit(child.copy(components = emptyList()))
        assertEquals("main", updated.mainCircuitName)
        assertEquals(emptyList(), updated.circuit("child")?.components)
        assertEquals(listOf("child", "main"), updated.circuits.map { it.name })
    }
}
