package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.VisualPrimitive
import dev.logisim.mobile.core.WireSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Evolution d58a61e0 strokes from svg-labels.circ: ControlledBuffer.paintInstance and PainterShaped.paintInputLines (#358). */
class GateTerminalLinesTest {
    private fun component(kind: ComponentKind, x: Int, y: Int, vararg attributes: Pair<String, String>) =
        CircuitComponent("c", kind, GridPoint(x, y), attributes = mapOf(*attributes))

    @Test fun controlLinesSnapToTheHalfGridLikeEvolution() {
        // Right-handed east buffer at (960,120): M845,50 V40 in Evolution's frame at (105,80).
        assertEquals(GateTerminalLine(GridPoint(950, 130), GridPoint(950, 130), GridPoint(950, 120)),
            controlledBufferControlLine(component(ComponentKind.CONTROLLED_BUFFER, 960, 120, "facing" to "east")))
        // Left-handed east inverter at (1060,120): M935,30 V35.
        assertEquals(GateTerminalLine(GridPoint(1040, 110), GridPoint(1040, 110), GridPoint(1040, 115)),
            controlledBufferControlLine(component(ComponentKind.CONTROLLED_INVERTER, 1060, 120, "facing" to "east", "control" to "left")))
        // Edges the comparison reports for the other facings.
        assertEquals(GridPoint(970, 450) to GridPoint(960, 450), controlledBufferControlLine(
            component(ComponentKind.CONTROLLED_BUFFER, 960, 440, "facing" to "north"))?.let { it.start to it.end })
        assertEquals(GridPoint(1080, 290) to GridPoint(1080, 280), controlledBufferControlLine(
            component(ComponentKind.CONTROLLED_INVERTER, 1060, 280, "facing" to "west", "control" to "left"))?.let { it.start to it.end })
        assertNull(controlledBufferControlLine(component(ComponentKind.BUFFER, 0, 0)))
    }

    @Test fun shieldInputLinesFollowGetInputLineLengths() {
        assertEquals(listOf(6, 6), shapedInputLineLengths(2, 70).toList())
        assertEquals(listOf(2, 2), shapedInputLineLengths(2, 30).toList())
        // West OR of size 70 at (640,280): M605,180 H595 and M605,220 H595 in Evolution's frame.
        val west = shapedGateInputLines(component(ComponentKind.OR, 640, 280, "facing" to "west", "size" to "70"))
        assertEquals(listOf(GridPoint(710, 260) to GridPoint(700, 260), GridPoint(710, 300) to GridPoint(700, 300)), west.map { it.start to it.end })
        // East: M465,20 H470 (6 units snapped to 5).
        val east = shapedGateInputLines(component(ComponentKind.OR, 640, 120, "facing" to "east", "size" to "70"))
        assertEquals(listOf(GridPoint(570, 100) to GridPoint(575, 100), GridPoint(570, 140) to GridPoint(575, 140)), east.map { it.start to it.end })
        // East XOR of size 30: M335,30 H335, a zero-length stroke Evolution still draws.
        val xor = shapedGateInputLines(component(ComponentKind.XOR, 480, 120, "facing" to "east", "size" to "30"))
        assertTrue(xor.isNotEmpty() && xor.all { it.start == it.end })
        assertEquals(emptyList(), shapedGateInputLines(component(ComponentKind.AND, 0, 0)))
    }

    @Test fun printerViewKeepsOnlyTheLinesOfConnectedInputs() {
        val or = component(ComponentKind.OR, 200, 100, "facing" to "east", "size" to "70").copy(id = "or")
        val xor = component(ComponentKind.XOR, 400, 100, "facing" to "east", "size" to "70").copy(id = "xor")
        val pin = CircuitComponent("pin", ComponentKind.INPUT, GridPoint(320, 120), attributes = mapOf("facing" to "east"))
        val ends = CircuitEnds(listOf(or, xor, pin), listOf(WireSegment(GridPoint(130, 40), GridPoint(130, 80))))
        assertEquals(2, shapedGateInputLines(or).size)
        // Circuit.isConnected: a wire end or another component's end; the gate's own end does not count.
        assertEquals(listOf(GridPoint(130, 80)), shapedGateInputLines(or, ends = ends).map { it.port })
        assertEquals(listOf(GridPoint(320, 120)), shapedGateInputLines(xor, ends = ends).map { it.port })
        assertEquals(emptyList(), shapedGateInputLines(or, ends = CircuitEnds(listOf(or), emptyList())))
    }

    @Test fun controlTerminalHasNoMarkerAndTheShapeStaysATriangleInIec() {
        val buffer = component(ComponentKind.CONTROLLED_BUFFER, 100, 100)
        val markers = circuitDrawingTerminalMarkers(buffer, SimulationState(), ComponentCatalog.registry, false)
        assertEquals(setOf(100.0 to 100.0, 80.0 to 100.0), markers.map { (it.bounds.left + it.bounds.right) / 2 to (it.bounds.top + it.bounds.bottom) / 2 }.toSet())
        assertNull(gateVisualItem(buffer, EditorGateShape.IEC))
    }

    @Test fun parityGatesDrawTheirRectangleInEveryShape() {
        for ((kind, symbol) in listOf(ComponentKind("ODD_PARITY") to "2k+1", ComponentKind("EVEN_PARITY") to "2k")) {
            for (shape in EditorGateShape.entries) {
                val group = assertNotNull(gateVisualItem(component(kind, 100, 100), shape)).primitives.single() as VisualPrimitive.Group
                assertEquals(listOf(symbol), group.children.filterIsInstance<VisualPrimitive.Text>().map { it.text }, "$kind $shape")
                assertEquals(0, group.children.filterIsInstance<VisualPrimitive.Ellipse>().size)
            }
        }
    }
}
