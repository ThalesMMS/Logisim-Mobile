package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SymbolLedTest {
    private val source = """
        <project source="5.1.0dev" version="1.0">
          <lib name="0" desc="#Wiring"/><lib name="1" desc="#I/O"/><main name="main"/>
          <circuit name="main">
            <comp lib="0" name="Pin" loc="(20,100)"><a name="label" val="A"/></comp>
            <comp lib="0" name="Pin" loc="(20,200)"><a name="label" val="B"/></comp>
            <comp name="Child" loc="(200,100)"/><comp name="Child" loc="(200,200)"/>
            <wire from="(20,100)" to="(200,100)"/><wire from="(20,200)" to="(200,200)"/>
          </circuit>
          <circuit name="Child">
            <a name="appearance" val="custom"/>
            <appear>
              <rect x="0" y="0" width="40" height="30" fill="none" stroke="#000000"/>
              <circ-anchor x="0" y="15" facing="east"/>
              <circ-port x="0" y="15" pin="50,50" dir="in"/>
            </appear>
            <comp lib="0" name="Pin" loc="(50,50)"/>
            <comp lib="1" name="LED" loc="(100,50)">
              <a name="label" val="Lamp"/><a name="color" val="#00ff00"/><a name="offcolor" val="#232323"/>
            </comp>
            <wire from="(50,50)" to="(100,50)"/>
          </circuit>
        </project>
    """.trimIndent()

    @Test
    fun addedAndEditedLedFollowsEachInstanceAfterReopen() {
        val project = read(source)
        val child = project.circuit("Child")!!
        val lamp = child.components.single { it.kind == IoKinds.Led }
        val original = SymbolAppearance.read(child)
        val draft = original.copy(objects = original.objects + SymbolObject.Led("led-1", lamp.id, GridPoint(11, 7)))
        val indicator = draft.objects.filterIsInstance<SymbolObject.Led>().single()
        val edited = draft.copy(objects = draft.objects - indicator + indicator.copy(position = GridPoint(12, 8), radius = 7))
        val updated = AppearanceEdits.apply(project, child.name, edited)
        val saved = CircCodec.writeProject(updated)
        assertTrue(saved.contains("path=\"/LED(100,50)\""))
        val reopened = read(saved)
        val reopenedChild = reopened.circuit("Child")!!
        val appearance = SymbolAppearance.read(reopenedChild)
        val reopenedIndicator = appearance.objects.filterIsInstance<SymbolObject.Led>().single()
        assertEquals(lamp.id, reopenedIndicator.sourceId)
        assertEquals(GridPoint(12, 8), reopenedIndicator.position)
        assertEquals(7, reopenedIndicator.radius)
        val instances = reopened.mainCircuit.components.filter { it.kind == ComponentKind.SUBCIRCUIT }.sortedBy { it.position.y }
        val inputs = reopened.mainCircuit.components.filter { it.kind == ComponentKind.INPUT }.associateBy { it.label }
        val simulator = CircuitSimulator()
        val first = simulator.settle(reopened, inputs = mapOf(inputs.getValue("A").id to LogicValue.HIGH, inputs.getValue("B").id to LogicValue.LOW))
        val second = simulator.settle(reopened, first, inputs = mapOf(inputs.getValue("A").id to LogicValue.LOW, inputs.getValue("B").id to LogicValue.HIGH))
        fun colors(state: SimulationState) = instances.map { instance ->
            val childState = state.circuitStates.getValue(instance.id)
            val visual = SymbolVisuals.symbol(appearance, circuit = reopenedChild, simulation = childState)
            assertIs<VisualPrimitive.Ellipse>(visual.items.single { it.id == reopenedIndicator.id }.primitives.single()).paint.fill
        }
        assertEquals(listOf(0xff00ff00u, 0xff232323u), colors(first))
        assertEquals(listOf(0xff232323u, 0xff00ff00u), colors(second))
    }

    @Test
    fun desktopIndicatorRebindsWhenLedMovesAndUnknownXmlIsPreserved() {
        val opaque = """<visible-rgbled x="2" y="3" path="/RGBLED(100,70)"/>"""
        val project = read(source.replace("<appear>", """
            <appear>
              <visible-led x="8" y="10" width="10" height="10" path="/LED(100, 50)"/>
              $opaque
        """.trimIndent()))
        val child = project.circuit("Child")!!
        val lamp = child.components.single { it.kind == IoKinds.Led }
        val appearance = SymbolAppearance.read(child)
        assertEquals(lamp.id, appearance.objects.filterIsInstance<SymbolObject.Led>().single().sourceId)
        val moved = child.copy(components = child.components.map { if (it.id == lamp.id) it.copy(position = GridPoint(110, 50)) else it })
        val saved = CircCodec.writeProject(AppearanceEdits.reconcileCircuit(project, child, moved))
        assertTrue(saved.contains(opaque))
        assertTrue(saved.contains("path=\"/LED(110,50)\""))
        val reopened = read(saved).circuit("Child")!!
        assertEquals(reopened.components.single { it.kind == IoKinds.Led }.id,
            SymbolAppearance.read(reopened).objects.filterIsInstance<SymbolObject.Led>().single().sourceId)
    }

    private fun read(xml: String) = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
}
