package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.VisualBounds
import dev.logisim.mobile.core.VisualPathCommand
import dev.logisim.mobile.core.VisualPoint
import dev.logisim.mobile.core.VisualPrimitive
import dev.logisim.mobile.core.VisualFont
import dev.logisim.mobile.core.VisualTextVerticalAlign
import dev.logisim.mobile.core.inputPorts
import dev.logisim.mobile.core.outputPorts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RendererIECTest {
    @Test
    fun iecSymbolsSitOnEvolutionsBaselineInTheCanvasFont() {
        // AND Gate at (200,150) in scripts/oracle/visual-export/svg-gates.circ: Evolution draws "&" with its
        // baseline at y=139, top + 2 + ascent 12 (ComponentDrawContext.drawRectangle); NOT centres "1" at +6.
        fun symbol(kind: ComponentKind) = (assertNotNull(iecGateVisualItem(CircuitComponent("g", kind, GridPoint(200, 150))))
            .primitives.single() as VisualPrimitive.Group).children.filterIsInstance<VisualPrimitive.Text>().single()
        val and = symbol(ComponentKind.AND)
        assertEquals(-25.0 + 14.0, and.position.y)
        assertEquals(VisualTextVerticalAlign.BASELINE, and.verticalAlign)
        assertEquals(VisualFont("SansSerif", 12.0), and.font)
        val not = symbol(ComponentKind.NOT)
        assertEquals(VisualPoint(-20.0, 6.0), not.position)
    }

    @Test
    fun gateKindsUseTheirIecSymbolsAndOutputInversion() {
        val symbols = mapOf("AND" to "&", "NAND" to "&", "OR" to "≥1", "NOR" to "≥1", "XOR" to "=1", "XNOR" to "=1",
            "BUFFER" to "1", "NOT" to "1", "ODD_PARITY" to "2k+1", "EVEN_PARITY" to "2k")
        val inverted = setOf("NAND", "NOR", "XNOR", "NOT")
        for ((kind, symbol) in symbols) {
            val component = CircuitComponent("gate", ComponentKind(kind), GridPoint(100, 200))
            val group = assertNotNull(iecGateVisualItem(component)).primitives.single() as VisualPrimitive.Group
            assertEquals(symbol, group.children.filterIsInstance<VisualPrimitive.Text>().single().text)
            assertEquals(if (kind in inverted) 1 else 0, group.children.filterIsInstance<VisualPrimitive.Ellipse>().size)
            assertEquals(1, group.children.filterIsInstance<VisualPrimitive.Rectangle>().size)
        }
        assertNull(iecGateVisualItem(CircuitComponent("clock", ComponentKind.CLOCK, GridPoint(0, 0))))
    }

    @Test
    fun facingUsesTheSameWorldRotationForBodyTextAndPins() {
        for ((facing, angle) in mapOf("east" to 0.0, "south" to 90.0, "west" to 180.0, "north" to 270.0)) {
            val component = CircuitComponent("gate", ComponentKind.NAND, GridPoint(100, 200), attributes = mapOf("facing" to facing))
            val group = assertNotNull(iecGateVisualItem(component)).primitives.single() as VisualPrimitive.Group
            assertEquals(100.0, group.transform.translateX)
            assertEquals(200.0, group.transform.translateY)
            assertEquals(angle, group.transform.rotationDegrees)
            assertEquals(VisualBounds(-60.0, -25.0, -10.0, 25.0),
                group.children.filterIsInstance<VisualPrimitive.Rectangle>().single().bounds)
        }
    }

    @Test
    fun xorLabelsDistinguishOddParityFromExactlyOneInput() {
        for (kind in listOf(ComponentKind.XOR, ComponentKind.XNOR)) {
            for ((attributes, expected) in listOf(
                mapOf("inputs" to "3", "xor" to "odd") to "2k+1",
                mapOf("inputs" to "3", "xor" to "1") to "=1",
                mapOf("inputs" to "2", "xor" to "odd") to "=1",
            )) {
                val component = CircuitComponent("gate", kind, GridPoint(0, 0), attributes = attributes)
                val group = assertNotNull(iecGateVisualItem(component)).primitives.single() as VisualPrimitive.Group
                assertEquals(expected, group.children.filterIsInstance<VisualPrimitive.Text>().single().text)
            }
        }
    }

    @Test
    fun negatedInputsEndOnTheRectangleAndControlledBuffersKeepTheTriangle() {
        val negated = CircuitComponent("gate", ComponentKind.AND, GridPoint(0, 0), attributes = mapOf("negate0" to "true"))
        val group = assertNotNull(iecGateVisualItem(negated)).primitives.single() as VisualPrimitive.Group
        assertEquals(VisualBounds(-60.0, -25.0, -50.0, -15.0), group.children.filterIsInstance<VisualPrimitive.Ellipse>().single().bounds)
        // ControlledBuffer.paintShape draws the triangle in every gate shape, so IEC has no rectangle for it.
        for (kind in listOf(ComponentKind.CONTROLLED_BUFFER, ComponentKind.CONTROLLED_INVERTER)) {
            assertNull(iecGateVisualItem(CircuitComponent("controlled", kind, GridPoint(0, 0))))
            assertNull(gateVisualItem(CircuitComponent("controlled", kind, GridPoint(0, 0)), EditorGateShape.IEC))
        }
    }

    @Test
    fun negationBubblesFollowTheAuthoritativeTerminalOrderForEveryFacing() {
        data class Case(val size: Int, val facing: String, val worldOffset: GridPoint, val local: VisualPoint)
        val cases = listOf(
            Case(30, "east", GridPoint(-40, -10), VisualPoint(-40.0, -10.0)),
            Case(30, "south", GridPoint(-10, -40), VisualPoint(-40.0, 10.0)),
            Case(30, "west", GridPoint(40, -10), VisualPoint(-40.0, 10.0)),
            Case(30, "north", GridPoint(-10, 40), VisualPoint(-40.0, -10.0)),
            Case(50, "east", GridPoint(-60, -20), VisualPoint(-60.0, -20.0)),
            Case(50, "south", GridPoint(-20, -60), VisualPoint(-60.0, 20.0)),
            Case(50, "west", GridPoint(60, -20), VisualPoint(-60.0, 20.0)),
            Case(50, "north", GridPoint(-20, 60), VisualPoint(-60.0, -20.0)),
            Case(70, "east", GridPoint(-80, -20), VisualPoint(-80.0, -20.0)),
            Case(70, "south", GridPoint(-20, -80), VisualPoint(-80.0, 20.0)),
            Case(70, "west", GridPoint(80, -20), VisualPoint(-80.0, 20.0)),
            Case(70, "north", GridPoint(-20, 80), VisualPoint(-80.0, -20.0)),
        )
        for (case in cases) {
            val component = CircuitComponent("gate", ComponentKind.AND, GridPoint(100, 200),
                attributes = mapOf("size" to case.size.toString(), "facing" to case.facing, "negate0" to "true"))
            assertEquals(GridPoint(100 + case.worldOffset.x, 200 + case.worldOffset.y), component.inputPorts().first())
            val group = assertNotNull(iecGateVisualItem(component)).primitives.single() as VisualPrimitive.Group
            val bubble = group.children.filterIsInstance<VisualPrimitive.Ellipse>().single().bounds
            assertEquals(case.local, VisualPoint(bubble.left, (bubble.top + bubble.bottom) / 2.0))
            val lead = group.children.filterIsInstance<VisualPrimitive.Path>().first().commands
            assertEquals(VisualPathCommand.MoveTo(case.local), lead[0])
            assertEquals(VisualPathCommand.LineTo(VisualPoint(case.local.x + 10.0, case.local.y)), lead[1])
        }
    }

    @Test
    fun changingDrawingStyleDoesNotChangeSavedComponentsOrTerminals() {
        val component = CircuitComponent("gate", ComponentKind.XNOR, GridPoint(100, 200), attributes = mapOf("inputs" to "4", "facing" to "north"))
        val document = CircuitDocument(components = listOf(component))
        val before = CircCodec.write(document)
        val inputs = component.inputPorts()
        val outputs = component.outputPorts()
        assertEquals(EditorGateShape.ANSI, CircuitDrawingStyle().gateShape)
        assertEquals(EditorGateShape.IEC, CircuitDrawingStyle(gateShape = EditorGateShape.IEC).gateShape)
        assertNotNull(iecGateVisualItem(component))
        assertEquals(inputs, component.inputPorts())
        assertEquals(outputs, component.outputPorts())
        assertEquals(before, CircCodec.write(document))
    }
}
