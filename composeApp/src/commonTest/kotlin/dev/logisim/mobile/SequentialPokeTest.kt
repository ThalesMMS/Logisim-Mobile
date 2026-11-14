package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ExternalIoComponents
import dev.logisim.mobile.core.FlipFlopPoked
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.PortIoTogglePin
import dev.logisim.mobile.core.layout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SequentialPokeTest {
    @Test
    fun aFingertipReachesTheFlipFlopToggleWhileTheMouseKeepsEvolutionsCircle() {
        val editor = EditorState(project("""
            <circuit name="main">
              <comp lib="1" loc="(160,160)" name="D Flip-Flop"/>
            </circuit>
        """))
        val flop = editor.document.components.single()
        val bounds = flop.layout(editor.componentRegistry).bounds
        val center = Offset((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f)
        val near = center + Offset(12f, 0f)
        assertNull(flop.sequentialPokeTarget(near, editor.componentRegistry))
        assertNull(flop.sequentialPokeTarget(near, editor.componentRegistry, tolerance = 0f))
        val target = assertNotNull(flop.sequentialPokeTarget(near, editor.componentRegistry, tolerance = 16f))
        assertEquals(FlipFlopPoked, target.toggle)
        // The highlighted target and the start/end comparison keep the drawn 16-unit circle.
        assertEquals(flop.sequentialPokeTarget(center, editor.componentRegistry), target)
        assertNull(flop.sequentialPokeTarget(center + Offset(12f, 0f), editor.componentRegistry, tolerance = 10f))
        // The reach stops at half the body, so a tap on the body's edge or beside it selects instead.
        val half = minOf(bounds.right - bounds.left, bounds.bottom - bounds.top) / 2f
        assertNull(flop.sequentialPokeTarget(center + Offset(half + 1f, 0f), editor.componentRegistry, tolerance = 40f))
    }

    @Test
    fun aFingertipReachesTheNearestShiftRegisterStage() {
        val editor = EditorState(project("""
            <circuit name="main">
              <comp lib="1" loc="(160,240)" name="Shift Register">
                <a name="length" val="3"/><a name="width" val="4"/>
              </comp>
            </circuit>
        """))
        val shift = editor.document.components.single()
        val registry = editor.componentRegistry
        val body = shift.layout(registry).bounds
        val stages = shift.shiftPokeTargets(registry)
        val box = stages[1].bounds
        val below = Offset(box.center.x + 3f, box.bottom + 3f)
        assertTrue(below.y < body.bottom)
        assertNull(shift.sequentialPokeTarget(below, registry))
        assertEquals(1, shift.sequentialPokeTarget(below, registry, tolerance = 20f)?.stage)
        assertEquals(stages[1], shift.sequentialPokeTarget(below, registry, tolerance = 20f))
        // Just past a box's edge the next stage is the nearer one; off the body nothing is poked.
        assertEquals(2, shift.sequentialPokeTarget(Offset(box.right + 1f, box.center.y), registry, tolerance = 20f)?.stage)
        assertNull(shift.sequentialPokeTarget(Offset(box.center.x, body.bottom + 4f), registry, tolerance = 20f))
    }

    @Test
    fun aFingertipReachesTheNearestPortIoPinWithinTwoUnits() {
        val port = CircuitComponent("port", ExternalIoComponents.PortIo, GridPoint(100, 200),
            attributes = mapOf("facing" to "east", "number" to "8"))
        // Pin 0 covers (107..112, 225..230), pin 1 the row below from 235, pin 2 the column right from 117.
        assertNull(externalIoEventAt(port, GridPoint(114, 227)))
        assertEquals(PortIoTogglePin(0), externalIoEventAt(port, GridPoint(114, 227), tolerance = 20))
        assertEquals(PortIoTogglePin(2), externalIoEventAt(port, GridPoint(115, 227), tolerance = 20))
        assertNull(externalIoEventAt(port, GridPoint(110, 233)))
        assertEquals(PortIoTogglePin(1), externalIoEventAt(port, GridPoint(110, 233), tolerance = 20))
        assertEquals(PortIoTogglePin(0), externalIoEventAt(port, GridPoint(110, 232), tolerance = 20))
        assertNull(externalIoEventAt(port, GridPoint(110, 222), tolerance = 20))
        assertEquals(PortIoTogglePin(0), externalIoEventAt(port, GridPoint(110, 228), tolerance = 20))
        // A one-pin port has no second row to reach.
        val single = port.copy(attributes = mapOf("facing" to "east", "number" to "1"))
        assertEquals(PortIoTogglePin(0), externalIoEventAt(single, GridPoint(110, 232), tolerance = 20))
        assertNull(externalIoEventAt(single, GridPoint(110, 233), tolerance = 20))
    }

    @Test
    fun originalSymbolsUpdateTheirOutputsWithoutPulsingTheClockOrChangingTheFile() {
        val editor = EditorState(project("""
            <circuit name="main">
              <comp lib="1" loc="(160,160)" name="D Flip-Flop"/>
              <comp lib="1" loc="(240,160)" name="T Flip-Flop"/>
              <comp lib="1" loc="(320,160)" name="J-K Flip-Flop"/>
              <comp lib="1" loc="(400,160)" name="S-R Flip-Flop"/>
              <comp lib="1" loc="(480,160)" name="Register"><a name="width" val="4"/></comp>
              <comp lib="1" loc="(560,160)" name="Counter"><a name="width" val="4"/></comp>
              <comp lib="1" loc="(160,240)" name="Shift Register">
                <a name="length" val="3"/><a name="width" val="4"/>
              </comp>
              <comp lib="1" loc="(320,240)" name="Shift Register">
                <a name="length" val="3"/><a name="width" val="1"/>
              </comp>
            </circuit>
        """))
        val saved = editor.exportXml()
        val ticks = editor.simulation.ticks
        for (flop in editor.document.components.filter { it.kind in flipFlopKinds }) {
            assertEquals("0", editor.output(flop))
            editor.poke(editor.bodyTarget(flop))
            assertEquals("1", editor.output(flop))
            assertEquals("0", editor.output(flop, "nq"))
            editor.poke(editor.bodyTarget(flop))
            assertEquals("0", editor.output(flop))
        }
        for (word in editor.document.components.filter { it.kind in listOf(ComponentKind.REGISTER, ComponentKind.COUNTER) }) {
            editor.poke(editor.bodyTarget(word), 5)
            assertEquals("0101", editor.output(word))
            editor.poke(editor.bodyTarget(word), 10)
            assertEquals("1010", editor.output(word))
        }

        val shift = editor.document.components.single { it.position.x == 160 && it.position.y == 240 }
        editor.poke(editor.stageTarget(shift, 0), 5)
        editor.poke(editor.stageTarget(shift, 2), 10)
        assertEquals("0101", editor.output(shift, "stage0"))
        assertEquals("0000", editor.output(shift, "stage1"))
        assertEquals("1010", editor.output(shift, "stage2"))
        assertEquals("1010", editor.output(shift))

        val oneBitShift = editor.document.components.single { it.position.x == 320 && it.position.y == 240 }
        editor.poke(editor.stageTarget(oneBitShift, 1))
        assertEquals("0", editor.output(oneBitShift, "stage0"))
        assertEquals("1", editor.output(oneBitShift, "stage1"))
        assertEquals("0", editor.output(oneBitShift, "stage2"))
        assertNull(oneBitShift.sequentialPokeTarget(Offset(320f, 240f), editor.componentRegistry))
        assertEquals(ticks, editor.simulation.ticks)
        assertEquals(saved, editor.exportXml())
    }

    @Test
    fun pokeIsScopedToTheSelectedInstanceAndCannotOverrideClearOrPreset() {
        val editor = EditorState(project("""
            <circuit name="main">
              <comp loc="(160,160)" name="child"/>
              <comp loc="(320,160)" name="child"/>
            </circuit>
            <circuit name="child">
              <comp lib="1" loc="(160,160)" name="D Flip-Flop"/>
              <comp lib="1" loc="(320,160)" name="D Flip-Flop"/>
              <comp lib="1" loc="(480,160)" name="D Flip-Flop"/>
              <comp lib="0" loc="(310,210)" name="Constant"/>
              <comp lib="0" loc="(450,210)" name="Constant"/>
              <wire from="(310,190)" to="(310,210)"/>
              <wire from="(450,190)" to="(450,210)"/>
            </circuit>
        """))
        val parent = editor.document
        val child = editor.project.circuit("child")!!
        val instances = parent.components.filter { it.kind == ComponentKind.SUBCIRCUIT }
        editor.navigate(CircuitNavigationTarget(child.id, child.name, listOf(instances[0].id)))
        val flops = child.components.filter { it.kind == ComponentKind.D_FLIP_FLOP }.sortedBy { it.position.x }
        assertEquals(listOf("0", "0", "1"), flops.map { editor.output(it) })
        for (flop in flops) editor.poke(editor.bodyTarget(flop))
        assertEquals(listOf("1", "0", "1"), flops.map { editor.output(it) })

        editor.navigate(CircuitNavigationTarget(child.id, child.name, listOf(instances[1].id)))
        assertEquals(listOf("0", "0", "1"), flops.map { editor.output(it) })
    }

    private fun EditorState.bodyTarget(component: CircuitComponent): SequentialPokeTarget {
        val bounds = component.layout(componentRegistry).bounds
        val point = Offset((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f)
        return assertNotNull(component.sequentialPokeTarget(point, componentRegistry))
    }

    private fun EditorState.stageTarget(component: CircuitComponent, index: Int): SequentialPokeTarget {
        val port = component.layout(componentRegistry).outputs.single { it.name == "stage$index" }
        return assertNotNull(component.sequentialPokeTarget(Offset(port.position.x.toFloat(), component.position.y.toFloat()), componentRegistry))
    }

    private fun EditorState.poke(target: SequentialPokeTarget, digit: Int? = null) {
        val event = if (digit == null) target.toggle else target.digitEvent(listOf(digit))
        assertTrue(sendComponentEvent(target.componentId, assertNotNull(event)))
    }

    private fun EditorState.output(component: CircuitComponent, name: String = "q"): String =
        simulation.componentOutputs.getValue(component.id).getValue(name).display

    private fun project(circuits: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project source="2.7.1" version="1.0">
          <lib desc="#Wiring" name="0"/>
          <lib desc="#Memory" name="1"/>
          <main name="main"/>
          ${circuits.trimIndent()}
        </project>
    """.trimIndent().trim()
}
