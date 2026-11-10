package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.VisualBounds
import dev.logisim.mobile.core.VisualFont
import dev.logisim.mobile.core.VisualItem
import dev.logisim.mobile.core.VisualPaint
import dev.logisim.mobile.core.VisualPathCommand
import dev.logisim.mobile.core.VisualPoint
import dev.logisim.mobile.core.VisualPrimitive
import dev.logisim.mobile.core.VisualTextAlign
import dev.logisim.mobile.core.VisualTextVerticalAlign
import dev.logisim.mobile.core.VisualTransform
import dev.logisim.mobile.core.bounds
import dev.logisim.mobile.core.layout
import kotlin.math.floor

private const val IecSymbolAscent = 12.0

/** A drawing preference changes primitives, never the component layout or saved attributes. */
internal fun iecGateVisualItem(
    component: CircuitComponent,
    registry: ComponentRegistry = ComponentCatalog.registry,
    ink: UInt = 0xff000000u,
    paper: UInt = 0xffffffffu,
): VisualItem? {
    val symbol = when (component.kind.name) {
        "AND", "NAND" -> "&"
        "OR", "NOR" -> "≥1"
        "ODD_PARITY" -> "2k+1"
        "EVEN_PARITY" -> "2k"
        "XOR", "XNOR" -> if (component.attributes["xor"] == "odd" &&
            (component.attributes["inputs"]?.toIntOrNull() ?: 2) != 2) "2k+1" else "=1"
        // ControlledBuffer.paintShape keeps the triangle (and the inverter's bubble) in every gate shape.
        "NOT", "BUFFER" -> "1"
        else -> return null
    }
    val kind = component.kind.name
    val single = symbol == "1"
    val inverted = kind in setOf("NOT", "NAND", "NOR", "XNOR", "CONTROLLED_INVERTER")
    val canonical = component.copy(position = GridPoint(0, 0), attributes = component.attributes + ("facing" to "east"))
    val layout = canonical.layout(registry)
    val actualLayout = component.layout(registry)
    val inputs = actualLayout.inputs.map { it.copy(position = component.iecLocalPoint(it.position)) }
    val data = if (single) inputs.take(1) else inputs
    val left = data.mapIndexed { index, terminal ->
        terminal.position.x.toDouble() + if (!single && component.attributes["negate$index"] == "true") 10.0 else 0.0
    }.minOrNull() ?: return null
    val radius = if (inverted && single && -left <= 20.0) 3.0 else 5.0
    val right = if (inverted) -2.0 * radius else 0.0
    val top = if (single) -9.0 else layout.bounds.top.toDouble()
    val bottom = if (single) 9.0 else layout.bounds.bottom.toDouble()
    val body = VisualBounds(left, top, right, bottom)
    val outline = VisualPaint(paper, ink, 1.7)
    val line = VisualPaint(null, ink, 1.4)
    val primitives = buildList {
        add(VisualPrimitive.Rectangle(body, outline))
        // The canvas font (Dialog plain 12, ascent 12): GraphicsUtil.drawCenteredText for the one-input gates,
        // ComponentDrawContext.drawRectangle (top edge above 20 units, else centred) for the others.
        val height = bottom - top
        val baseline = when {
            single -> (top + bottom) / 2.0 + IecSymbolAscent / 2
            height > 20.0 -> top + 2.0 + IecSymbolAscent
            else -> top + floor((height + IecSymbolAscent) / 2.0) - 1.0
        }
        add(VisualPrimitive.Text(symbol, VisualPoint((left + right) / 2.0, baseline),
            VisualFont("SansSerif", 12.0), ink, VisualTextAlign.CENTER, VisualTextVerticalAlign.BASELINE))
        for ((index, terminal) in inputs.withIndex()) {
            val start = VisualPoint(terminal.position.x.toDouble(), terminal.position.y.toDouble())
            val control = single && index > 0
            val end = if (control) VisualPoint(start.x.coerceIn(left, right), if (start.y < 0) top else bottom)
                else VisualPoint(left, start.y)
            add(VisualPrimitive.Path(listOf(VisualPathCommand.MoveTo(start), VisualPathCommand.LineTo(end)), line))
            if (!single && component.attributes["negate$index"] == "true") {
                add(VisualPrimitive.Ellipse(VisualBounds(start.x, start.y - 5.0, start.x + 10.0, start.y + 5.0), outline))
            }
        }
        for (terminal in actualLayout.outputs) {
            val local = component.iecLocalPoint(terminal.position)
            val end = VisualPoint(local.x.toDouble(), local.y.toDouble())
            add(VisualPrimitive.Path(listOf(VisualPathCommand.MoveTo(VisualPoint(right, end.y)), VisualPathCommand.LineTo(end)), line))
        }
        if (inverted) add(VisualPrimitive.Ellipse(VisualBounds(-2.0 * radius, -radius, 0.0, radius), outline))
    }
    val angle = when (component.facing) { "south" -> 90.0; "west" -> 180.0; "north" -> 270.0; else -> 0.0 }
    val bounds = component.bounds(registry)
    return VisualItem(component.id, VisualBounds(bounds.left.toDouble(), bounds.top.toDouble(), bounds.right.toDouble(), bounds.bottom.toDouble()).expanded(2.0),
        listOf(VisualPrimitive.Group(VisualTransform(component.position.x.toDouble(), component.position.y.toDouble(), angle), primitives)))
}

private fun CircuitComponent.iecLocalPoint(point: GridPoint): GridPoint {
    val x = point.x - position.x
    val y = point.y - position.y
    return when (facing) {
        "south" -> GridPoint(y, -x)
        "west" -> GridPoint(-x, -y)
        "north" -> GridPoint(-y, x)
        else -> GridPoint(x, y)
    }
}
