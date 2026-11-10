package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.inputPorts
import dev.logisim.mobile.core.outputPorts
import kotlin.math.max
import kotlin.math.min

internal fun DrawScope.drawPlexer(
    component: CircuitComponent,
    bounds: Rect,
    measurer: TextMeasurer,
    ink: Color = DefaultComponentInk,
    secondary: Int? = null,
) {
    val kind = component.kind.name
    val facing = component.facing
    val horizontal = facing == "east" || facing == "west"
    val n = 1 shl component.attributeInt("select", if (kind == "PRIORITY_ENCODER") 3 else 1)
    val narrow = component.attributes["size"] == "20"
    var body = bounds
    val fanOut = kind == "DEMULTIPLEXER" || kind == "DECODER"
    if (fanOut && n == 2) {
        body = if (horizontal) Rect(bounds.left, bounds.top + 5, bounds.right, bounds.bottom - 5)
        else Rect(bounds.left + 5, bounds.top, bounds.right - 5, bounds.bottom)
    }
    if (kind == "PRIORITY_ENCODER") {
        drawRect(Color.White, body.topLeft, body.size)
        drawRect(ink, body.topLeft, body.size, style = Stroke(1.6f))
    } else {
        val lean = when {
            kind == "BIT_SELECTOR" -> 9f
            fanOut -> if (n == 2) 10f else 20f
            n == 2 -> if (narrow) 7f else 10f
            else -> if (narrow) 10f else 20f
        }
        val taper = if (fanOut) when (facing) {
            "east" -> "west"; "west" -> "east"; "north" -> "south"; else -> "north"
        } else facing
        val points = mutableListOf(body.topLeft, body.topRight, body.bottomRight, body.bottomLeft)
        when (taper) {
            "west" -> { points[0] += Offset(0f, lean); points[3] -= Offset(0f, lean) }
            "north" -> { points[0] += Offset(lean, 0f); points[1] -= Offset(lean, 0f) }
            "south" -> { points[2] -= Offset(lean, 0f); points[3] += Offset(lean, 0f) }
            else -> { points[1] += Offset(0f, lean); points[2] -= Offset(0f, lean) }
        }
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(path, Color.White)
        drawPath(path, ink, style = Stroke(1.6f))
    }
    val label = when (kind) {
        "MULTIPLEXER" -> if (narrow) "" else "MUX"
        "DEMULTIPLEXER" -> "DMX"
        "DECODER" -> "Decd"
        "PRIORITY_ENCODER" -> "Pri"
        else -> "Sel"
    }
    if (label.isNotEmpty()) {
        val measured = measurer.measure(label, TextStyle(fontSize = 10.sp, color = ink))
        drawCircuitText(measured, topLeft = body.center - Offset(measured.size.width / 2f, measured.size.height / 2f))
    }
    val first = if (fanOut) component.outputPorts().firstOrNull() else component.inputPorts().firstOrNull()
    if (first != null && kind != "BIT_SELECTOR") {
        val zero = measurer.measure("0", TextStyle(fontSize = 7.sp, color = secondary.editorColorOr(Color.Gray)))
        val x = (first.x.toFloat() + if (facing == "west") -7f else 3f).coerceIn(body.left + 2, body.right - 5)
        val y = (first.y.toFloat() - 5).coerceIn(body.top + 2, body.bottom - 8)
        drawCircuitText(zero, topLeft = Offset(x, y))
    }
    if (kind != "PRIORITY_ENCODER" && min(bounds.width, bounds.height) > 20) {
        val inputs = component.inputPorts()
        val select = when (kind) {
            "MULTIPLEXER" -> inputs.getOrNull(n)
            "BIT_SELECTOR" -> inputs.lastOrNull()
            else -> inputs.firstOrNull()
        }
        if (select != null) {
            val inset = if (max(bounds.width, bounds.height) <= 50) 8f else 6f
            val center = if (bounds.height >= bounds.width) Offset(select.x.toFloat(),
                select.y + if (select.y < bounds.center.y) inset else -inset)
            else Offset(select.x + if (select.x < bounds.center.x) inset else -inset, select.y.toFloat())
            drawCircle(secondary.editorColorOr(Color.LightGray), 3f, center)
        }
    }
}
