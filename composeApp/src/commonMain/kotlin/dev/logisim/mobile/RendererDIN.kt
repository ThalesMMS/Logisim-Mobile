package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.VisualBounds
import dev.logisim.mobile.core.VisualItem
import dev.logisim.mobile.core.VisualPaint
import dev.logisim.mobile.core.VisualPathCommand
import dev.logisim.mobile.core.VisualPoint
import dev.logisim.mobile.core.VisualPrimitive
import dev.logisim.mobile.core.VisualTransform
import dev.logisim.mobile.core.bounds
import dev.logisim.mobile.core.layout
import kotlin.math.sqrt

internal fun gateVisualItem(
    component: CircuitComponent,
    shape: EditorGateShape,
    registry: ComponentRegistry = ComponentCatalog.registry,
    ink: UInt = 0xff000000u,
    paper: UInt = 0xffffffffu,
): VisualItem? = when (shape) {
    // OddParityGate and EvenParityGate.paintShape draw the rectangle with "2k+1" or "2k" in every gate shape.
    EditorGateShape.ANSI -> if (component.kind.name == "ODD_PARITY" || component.kind.name == "EVEN_PARITY")
        iecGateVisualItem(component, registry, ink, paper) else null
    EditorGateShape.IEC -> iecGateVisualItem(component, registry, ink, paper)
    EditorGateShape.DIN40700 -> dinGateVisualItem(component, registry, ink, paper)
}

/** DIN 40700 geometry follows the original Logisim 2.7.1 PainterDin. */
internal fun dinGateVisualItem(
    component: CircuitComponent,
    registry: ComponentRegistry = ComponentCatalog.registry,
    ink: UInt = 0xff000000u,
    paper: UInt = 0xffffffffu,
): VisualItem? {
    val kind = component.kind.name
    if (kind == "ODD_PARITY" || kind == "EVEN_PARITY") return iecGateVisualItem(component, registry, ink, paper)
    if (kind !in setOf("AND", "NAND", "OR", "NOR", "XOR", "XNOR", "NOT")) return null
    val single = kind == "NOT"
    val inverted = kind in setOf("NAND", "NOR", "NOT")
    val disjunction = kind == "OR" || kind == "NOR"
    val canonical = component.copy(position = GridPoint(0, 0), attributes = component.attributes + ("facing" to "east"))
    val layout = canonical.layout(registry)
    val actualLayout = component.layout(registry)
    val inputs = actualLayout.inputs.map { component.dinLocalPoint(it.position) }
    val left = inputs.mapIndexed { index, point ->
        point.x + if (!single && component.attributes["negate$index"] == "true") 10.0 else 0.0
    }.minOrNull() ?: return null
    val height = if (single) 18.0 else (layout.bounds.bottom - layout.bounds.top).toDouble()
    val width = -left - if (inverted) 8.0 else 0.0
    val radius = minOf(height / 2.0, width)
    val top = -height / 2.0
    val bottom = height / 2.0
    val flat = height / 2.0 - radius
    val right = left + radius
    val line = VisualPaint(null, ink, 2.0)
    val quarterCircle = radius * 0.5522847498307936
    val primitives = buildList {
        add(VisualPrimitive.Path(listOf(
            VisualPathCommand.MoveTo(VisualPoint(left, top)),
            VisualPathCommand.CubicTo(VisualPoint(left + quarterCircle, top), VisualPoint(right, -flat - quarterCircle), VisualPoint(right, -flat)),
            VisualPathCommand.LineTo(VisualPoint(right, flat)),
            VisualPathCommand.CubicTo(VisualPoint(right, flat + quarterCircle), VisualPoint(left + quarterCircle, bottom), VisualPoint(left, bottom)),
            VisualPathCommand.Close,
        ), VisualPaint(paper, ink, 2.0)))
        for ((index, start) in inputs.withIndex()) {
            val curveY = (kotlin.math.abs(start.y) - flat).coerceAtLeast(0.0)
            val endX = if (disjunction) left + sqrt((radius * radius - curveY * curveY).coerceAtLeast(0.0)) else left
            add(VisualPrimitive.Path(listOf(VisualPathCommand.MoveTo(start), VisualPathCommand.LineTo(VisualPoint(endX, start.y))), line))
            if (!single && component.attributes["negate$index"] == "true") {
                add(VisualPrimitive.Ellipse(VisualBounds(start.x, start.y - 5.0, start.x + 10.0, start.y + 5.0),
                    VisualPaint(paper, ink, 2.0)))
            }
        }
        if (kind == "XOR" || kind == "XNOR") {
            val length = minOf(radius - 10.0, 20.0)
            val startX = left + (radius - length) / 2.0
            for (y in listOf(-5.0, 0.0, 5.0)) {
                add(VisualPrimitive.Path(listOf(VisualPathCommand.MoveTo(VisualPoint(startX, y)),
                    VisualPathCommand.LineTo(VisualPoint(startX + length, y))), line))
            }
            if (kind == "XOR") {
                val center = startX + length / 2.0
                add(VisualPrimitive.Path(listOf(VisualPathCommand.MoveTo(VisualPoint(center, -8.0)),
                    VisualPathCommand.LineTo(VisualPoint(center, 8.0))), line))
            }
        }
        for (terminal in actualLayout.outputs) {
            add(VisualPrimitive.Path(listOf(VisualPathCommand.MoveTo(VisualPoint(right, 0.0)),
                VisualPathCommand.LineTo(component.dinLocalPoint(terminal.position))), line))
        }
        if (inverted) add(VisualPrimitive.Ellipse(VisualBounds(right - 4.0, -4.0, right + 4.0, 4.0), VisualPaint(ink, stroke = null)))
    }
    val angle = when (component.facing) { "south" -> 90.0; "west" -> 180.0; "north" -> 270.0; else -> 0.0 }
    val bounds = component.bounds(registry)
    return VisualItem(component.id, VisualBounds(bounds.left.toDouble(), bounds.top.toDouble(), bounds.right.toDouble(), bounds.bottom.toDouble()).expanded(2.0),
        listOf(VisualPrimitive.Group(VisualTransform(component.position.x.toDouble(), component.position.y.toDouble(), angle), primitives)))
}

private fun CircuitComponent.dinLocalPoint(point: GridPoint): VisualPoint {
    val x = (point.x - position.x).toDouble()
    val y = (point.y - position.y).toDouble()
    return when (facing) {
        "south" -> VisualPoint(y, -x)
        "west" -> VisualPoint(-x, -y)
        "north" -> VisualPoint(-y, x)
        else -> VisualPoint(x, y)
    }
}
