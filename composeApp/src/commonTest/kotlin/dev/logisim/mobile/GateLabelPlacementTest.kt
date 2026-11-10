package dev.logisim.mobile

import androidx.compose.ui.geometry.Rect
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GatesLibrary
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.VisualFont
import dev.logisim.mobile.core.VisualPoint
import dev.logisim.mobile.core.VisualTextAlign
import dev.logisim.mobile.core.VisualTextVerticalAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** #356: gates, buffers and tunnels place their labels like Evolution d58a61e0, not through computeLabelTextField. */
class GateLabelPlacementTest {
    private fun component(kind: ComponentKind, vararg attributes: Pair<String, String>) =
        CircuitComponent("c", kind, GridPoint(200, 150), label = "L", attributes = mapOf(*attributes))

    private fun anchor(component: CircuitComponent, shape: EditorGateShape = EditorGateShape.ANSI): Pair<VisualPoint, VisualTextAlign> {
        val label = assertNotNull(classicComponentLabel(component, classicComponentBounds(component), gateShape = shape))
        assertEquals(VisualTextVerticalAlign.BASELINE, label.verticalAlign)
        return label.position to label.align
    }

    @Test fun abstractGatesCentreTheLabelOnTheBody() {
        // AbstractGate.computeLabel: H_CENTER/V_CENTER at loc - size/2 (+10 negated), baseline ascent/2 lower (16 bold).
        assertEquals(VisualPoint(175.0, 158.0) to VisualTextAlign.CENTER, anchor(component(ComponentKind.AND, "facing" to "east")))
        assertEquals(VisualPoint(165.0, 158.0) to VisualTextAlign.CENTER, anchor(component(ComponentKind.NAND, "facing" to "east")))
        assertEquals(VisualPoint(235.0, 158.0) to VisualTextAlign.CENTER, anchor(component(ComponentKind.OR, "facing" to "west", "size" to "70")))
        assertEquals(VisualPoint(200.0, 183.0) to VisualTextAlign.CENTER, anchor(component(ComponentKind.XOR, "facing" to "north")))
        assertEquals(VisualPoint(200.0, 133.0) to VisualTextAlign.CENTER, anchor(component(ComponentKind.XNOR, "facing" to "south", "size" to "30")))
        assertEquals(VisualPoint(175.0, 158.0) to VisualTextAlign.CENTER, anchor(component(GatesLibrary.OddParity)))
        // Rectangular gates move 6 units across the axis; a gate ignores labelloc.
        assertEquals(VisualPoint(175.0, 164.0), anchor(component(ComponentKind.AND, "labelloc" to "south"), EditorGateShape.IEC).first)
        assertEquals(VisualPoint(206.0, 183.0), anchor(component(ComponentKind.AND, "facing" to "north"), EditorGateShape.IEC).first)
        assertEquals(VisualPoint(194.0, 133.0), anchor(component(ComponentKind.AND, "facing" to "south"), EditorGateShape.IEC).first)
        assertEquals(VisualPoint(225.0, 152.0), anchor(component(ComponentKind.AND, "facing" to "west"), EditorGateShape.IEC).first)
        // DIN 40700 gates are shaped like ANSI ones.
        assertEquals(VisualPoint(175.0, 158.0), anchor(component(ComponentKind.AND), EditorGateShape.DIN40700).first)
    }

    @Test fun notGatesAndBuffersPutTheBaselineOnTopOfTheBody() {
        // NotGate.configureLabel: V_BASELINE on the top of the body, centred; 2 units higher for the IEC NOT.
        assertEquals(VisualPoint(185.0, 141.0) to VisualTextAlign.CENTER, anchor(component(ComponentKind.NOT)))
        assertEquals(VisualPoint(185.0, 139.0) to VisualTextAlign.CENTER, anchor(component(ComponentKind.NOT), EditorGateShape.IEC))
        assertEquals(VisualPoint(190.0, 141.0) to VisualTextAlign.CENTER, anchor(component(ComponentKind.NOT, "size" to "20")))
        assertEquals(VisualPoint(190.0, 141.0) to VisualTextAlign.CENTER, anchor(component(ComponentKind.BUFFER), EditorGateShape.IEC))
        assertEquals(VisualPoint(210.0, 141.0) to VisualTextAlign.CENTER, anchor(component(ComponentKind.BUFFER, "facing" to "west")))
        // North and south: left-aligned 2 units right of the centre, 2 units above the body.
        assertEquals(VisualPoint(202.0, 148.0) to VisualTextAlign.START, anchor(component(ComponentKind.NOT, "facing" to "north")))
        assertEquals(VisualPoint(202.0, 128.0) to VisualTextAlign.START, anchor(component(ComponentKind.BUFFER, "facing" to "south")))
        assertEquals(VisualPoint(202.0, 118.0) to VisualTextAlign.START,
            anchor(component(ComponentKind.CONTROLLED_INVERTER, "facing" to "south")))
    }

    @Test fun controlledBuffersStartFromAControlInputOnTheTopEdge() {
        // Right-handed east: the control is below, so the label is centred on the top edge.
        assertEquals(VisualPoint(190.0, 140.0) to VisualTextAlign.CENTER, anchor(component(ComponentKind.CONTROLLED_BUFFER)))
        // Left-handed east and right-handed west put the control on the top edge: H_LEFT, 2 units after it.
        assertEquals(VisualPoint(192.0, 140.0) to VisualTextAlign.START, anchor(component(ComponentKind.CONTROLLED_BUFFER, "control" to "left")))
        assertEquals(VisualPoint(212.0, 140.0) to VisualTextAlign.START, anchor(component(ComponentKind.CONTROLLED_BUFFER, "facing" to "west")))
        assertEquals(VisualPoint(222.0, 140.0) to VisualTextAlign.START, anchor(component(ComponentKind.CONTROLLED_INVERTER, "facing" to "west")))
        assertEquals(VisualPoint(210.0, 140.0) to VisualTextAlign.CENTER,
            anchor(component(ComponentKind.CONTROLLED_BUFFER, "facing" to "west", "control" to "left")))
    }

    @Test fun tunnelLabelsUseTheLabelFontInsideTheBody() {
        fun tunnel(facing: String, font: String? = null) = CircuitComponent("t", ComponentKind.TUNNEL, GridPoint(200, 150), label = "clk",
            attributes = mapOf("facing" to facing) + listOfNotNull(font?.let { "labelfont" to it }))
        val east = assertNotNull(classicTunnelLabel(tunnel("east")))
        assertEquals(VisualFont("SansSerif", 16.0, bold = true), east.font)
        // 5 units behind the point (ARROW_MARGIN), centred on the line (V_CENTER_OVERALL: ascent 16, descent 4).
        assertEquals(VisualPoint(195.0, 156.0) to VisualTextAlign.END, east.position to east.align)
        assertEquals(VisualPoint(205.0, 156.0) to VisualTextAlign.START, assertNotNull(classicTunnelLabel(tunnel("west"))).let { it.position to it.align })
        assertEquals(VisualPoint(200.0, 171.0) to VisualTextAlign.CENTER, assertNotNull(classicTunnelLabel(tunnel("north"))).let { it.position to it.align })
        assertEquals(VisualPoint(200.0, 141.0) to VisualTextAlign.CENTER, assertNotNull(classicTunnelLabel(tunnel("south"))).let { it.position to it.align })
        val small = assertNotNull(classicTunnelLabel(tunnel("north", "SansSerif plain 12")))
        assertEquals(VisualFont("SansSerif", 12.0), small.font)
        assertEquals(VisualPoint(200.0, 167.0), small.position)
        // Every label consumer (bounds, export, text tool) reads the tunnel through classicComponentLabel too.
        assertEquals(east, classicComponentLabel(tunnel("east"), Rect.Zero))
    }
}
