package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JarEditorGeometryTest {
    private val library = "com.cburch.gray.Components"
    private val source = """
        <project source="5.1.0" version="1.0">
          <lib name="0" desc="#Wiring"/>
          <lib name="1" desc="jar#external/gray.jar#$library"/>
          <main name="main"/>
          <circuit name="main">
            <comp lib="0" loc="(30,100)" name="Pin" logisim-mobile-id="clk"><a name="label" val="CLK"/></comp>
            <comp loc="(200,100)" name="child" logisim-mobile-id="left"/>
            <comp loc="(200,180)" name="child" logisim-mobile-id="right"/>
          </circuit>
          <circuit name="child">
            <comp lib="1" loc="(100,100)" name="Gray Counter" logisim-mobile-id="counter"/>
          </circuit>
        </project>
    """.trimIndent()

    private val trace = JarInstanceTrace(listOf(100, 180).map { parentY ->
        JarObservedInstance(
            id = "/200,$parentY/100,100",
            path = listOf(GridPoint(200, parentY)),
            circuit = "child",
            factory = "Gray Counter",
            location = GridPoint(100, 100),
            layout = ComponentLayout(
                listOf(ComponentTerminal("p0", GridPoint(70, 100), PortDirection.INPUT, 1),
                    ComponentTerminal("p1", GridPoint(100, 100), PortDirection.OUTPUT, 4)),
                ComponentBounds(70, 85, 100, 115),
            ),
            exclusiveTerminals = listOf(false, true),
        )
    }, emptyList())

    @Test
    fun verifiedGeometryPreservesFactoryPortsAndNativeHierarchyAddresses() {
        val owner = Any()
        val result = resolveJarEditorGeometry(source, library, trace, ComponentCatalog.registry, owner) { true }
        val component = result.project.circuit("child")!!.components.single()
        assertEquals(trace.instances.first().layout, result.registry.layout(component))
        assertEquals(GridPoint(-30, 0), component.explicitPorts.first().position)
        assertEquals(mapOf("/200,100/100,100" to "left/counter", "/200,180/100,100" to "right/counter"),
            result.instances.mapValues { it.value.eventAddress })
        assertTrue(result.isOwnedBy(owner))
        val serialized = CircCodec.writeProject(result.project, result.registry)
        assertTrue(serialized.contains("jar#external/gray.jar#$library"))
        assertTrue(serialized.contains("name=\"Gray Counter\""))
        assertIs<CircProjectImportResult.Failure>(CircCodec.readProject(serialized, result.registry))
        assertFailsWith<IllegalStateException> { CircuitSimulator(registry = result.registry).settle(result.project) }
    }

    @Test
    fun rejectsMissingMismatchedOrInconsistentInstanceGeometry() {
        val left = trace.instances.first()
        val right = trace.instances.last()
        val changedBounds = right.layout.copy(bounds = right.layout.bounds.copy(left = 69))
        for (instances in listOf(listOf(left), listOf(left, right.copy(factory = "Not Gray Counter")),
                listOf(left, right.copy(circuit = "main")), listOf(left, right.copy(layout = changedBounds)))) {
            assertFailsWith<IllegalArgumentException> {
                resolveJarEditorGeometry(source, library, trace.copy(instances = instances), ComponentCatalog.registry, Any()) { true }
            }
        }
    }

    @Test
    fun capturesOnlyTheOwnedNativeGraphAndRequiresTheRealPinVector() {
        var active = true
        val result = resolveJarEditorGeometry(source, library, trace, ComponentCatalog.registry, Any()) { active }
        val root = SimulationState(circuitName = "main", inputVectors = mapOf("clk" to LogicVector.high(1)))
        assertEquals(JarEditorInputFrame(17, mapOf("CLK" to LogicVector.high(1))),
            result.captureInputFrame(result.project, root, revision = 17))
        assertFailsWith<IllegalStateException> {
            result.captureInputFrame(result.project.copy(), root, revision = 18)
        }
        assertFailsWith<IllegalArgumentException> {
            result.captureInputFrame(result.project, root.copy(inputVectors = emptyMap()), revision = 18)
        }
        active = false
        assertFailsWith<IllegalStateException> { result.captureInputFrame(result.project, root, revision = 18) }
    }
}
