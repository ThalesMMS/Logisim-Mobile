package dev.logisim.mobile

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ExtraIoAttributes
import dev.logisim.mobile.core.ExtraIoComponents
import dev.logisim.mobile.core.ExtraIoKinds
import dev.logisim.mobile.core.ExtraOscilloscopeState
import dev.logisim.mobile.core.LogicValue
import kotlin.math.roundToInt

private val oscilloscopeDefinition by lazy {
    ExtraIoComponents.definitions().first { it.kind == ExtraIoKinds.Oscilloscope }
}

internal data class ExtraScopeLine(val from: Offset, val to: Offset)
internal data class ExtraScopeClockLabel(val number: Int, val position: Offset)
internal data class ExtraScopeRow(val isClock: Boolean, val baseline: Float, val lines: List<ExtraScopeLine>, val hasEnding: Boolean)
internal data class ExtraScopePlot(
    val bounds: Rect,
    val color: Color,
    val traceLeft: Float,
    val traceRight: Float,
    val edges: List<Float>,
    val clockLabels: List<ExtraScopeClockLabel>,
    val rows: List<ExtraScopeRow>,
)

internal fun extraOscilloscopePlot(component: CircuitComponent, state: ExtraOscilloscopeState?): ExtraScopePlot {
    val attributes = oscilloscopeDefinition.schema.parse(component.attributes)
    val inputs = attributes[ExtraIoAttributes.ScopeInputs]
    val states = attributes[ExtraIoAttributes.ScopeStates]
    val showClock = attributes[ExtraIoAttributes.ScopeClock]
    val frontLines = attributes[ExtraIoAttributes.ScopeEdges]
    val capacity = states * 2
    val bounds = oscilloscopeDefinition.layout(component, attributes).bounds.let {
        Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
    }
    val trace = state?.resize(inputs, states) ?: ExtraOscilloscopeState(inputs, states)
    val samples = trace.samples
    fun value(column: Int, row: Int): LogicValue = samples.getOrNull(column)?.getOrNull(row) ?: LogicValue.UNKNOWN
    fun defined(value: LogicValue) = value == LogicValue.LOW || value == LogicValue.HIGH
    val traceLeft = bounds.left + 10
    val traceRight = traceLeft + capacity * 15
    val edgeColumns = if (frontLines == "no") emptyList() else (1 until samples.size).filter { column ->
        val previous = value(column - 1, 0)
        val current = value(column, 0)
        (frontLines in setOf("rising", "both") && previous == LogicValue.LOW && current == LogicValue.HIGH) ||
            (frontLines in setOf("falling", "both") && previous == LogicValue.HIGH && current == LogicValue.LOW)
    }
    val rowCount = inputs + if (showClock) 1 else 0
    val rows = List(rowCount) { row ->
        val traceRow = row + if (showClock) 0 else 1
        val high = bounds.top + 20 + row * 30 + if (showClock) 2 else 0
        val low = bounds.top + 10 + (row + 1) * 30 + if (showClock) 2 else 0
        val lines = buildList {
            for (column in samples.indices) {
                val current = value(column, traceRow)
                if (!defined(current)) continue
                val x = traceLeft + column * 15
                val level = if (current == LogicValue.HIGH) high else low
                add(ExtraScopeLine(Offset(x, level), Offset(x + 15, level)))
                val previous = value(column - 1, traceRow)
                if (column > 0 && defined(previous) && previous != current) {
                    add(ExtraScopeLine(Offset(x, high), Offset(x, low)))
                }
                if (column == capacity - 1 && current == LogicValue.HIGH) {
                    add(ExtraScopeLine(Offset(x + 15, high), Offset(x + 15, low)))
                }
            }
        }
        ExtraScopeRow(showClock && row == 0, low, lines,
            samples.size == capacity && defined(value(capacity - 1, traceRow)))
    }
    var remainingClocks = states
    val clockLabels = if (!showClock || frontLines == "no") emptyList() else buildList {
        samples.indices.forEach { column ->
            if (value(column, 0) == LogicValue.HIGH) {
                remainingClocks--
                val number = trace.clockNumber - remainingClocks
                add(ExtraScopeClockLabel(if (number > 0) number else 100 + number - 1,
                    Offset(traceLeft + column * 15 + 7, bounds.top + 15)))
            }
        }
    }
    return ExtraScopePlot(bounds, extraScopeColor(attributes[ExtraIoAttributes.ScopeColor]), traceLeft, traceRight,
        edgeColumns.map { traceLeft + it * 15 }, clockLabels, rows)
}

/** Draws the same half-cycle columns, edge guides, and clock numbers as the desktop instrument. */
fun DrawScope.drawExtraOscilloscope(component: CircuitComponent, state: ExtraOscilloscopeState?, measurer: TextMeasurer, ink: Color = DefaultComponentInk) {
    val plot = extraOscilloscopePlot(component, state)
    val bounds = plot.bounds
    drawRoundRect(plot.color, bounds.topLeft, bounds.size, CornerRadius(5f, 5f))
    drawRoundRect(ink, bounds.topLeft, bounds.size, CornerRadius(5f, 5f), style = Stroke(2f))
    val inside = Rect(bounds.left + 10, bounds.top + 10, bounds.right - 10, bounds.bottom - 10)
    drawRoundRect(Color.White, inside.topLeft, inside.size, CornerRadius(2.5f, 2.5f))
    val edgeColor = plot.color.darker()
    val clockColor = edgeColor.darker()
    val axisColor = clockColor.darker()
    plot.edges.forEach { x ->
        drawLine(edgeColor, Offset(x, inside.top), Offset(x, inside.bottom), 0.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 8f))
    }
    plot.rows.forEach { row ->
        drawLine(axisColor, Offset(plot.traceLeft, row.baseline), Offset(plot.traceRight + 4, row.baseline), 1f)
        val arrowColor = if (row.hasEnding) ink else axisColor
        if (row.hasEnding) {
            drawLine(ink, Offset(plot.traceRight, row.baseline), Offset(plot.traceRight + 4, row.baseline), 2f)
        }
        drawPath(Path().apply {
            moveTo(plot.traceRight + 4, row.baseline - 3)
            lineTo(plot.traceRight + 13, row.baseline)
            lineTo(plot.traceRight + 4, row.baseline + 3)
            close()
        }, arrowColor)
        row.lines.forEach { line -> drawLine(if (row.isClock) clockColor else Color.Black, line.from, line.to, 2f) }
    }
    plot.clockLabels.forEach { label ->
        val text = measurer.measure(label.number.toString(), TextStyle(color = edgeColor, fontSize = worldTextSize(8f)))
        drawCircuitText(text, topLeft = label.position - Offset(text.size.width / 2f, text.size.height / 2f))
    }
    drawRoundRect(ink, inside.topLeft, inside.size, CornerRadius(2.5f, 2.5f), style = Stroke(1f))
}

internal fun extraScopeColor(raw: String): Color {
    val rgb = raw.substring(1, 7).toLong(16)
    val alpha = if (raw.length == 9) raw.substring(7, 9).toLong(16) else 255L
    return Color((alpha shl 24) or rgb)
}

private fun Color.darker(): Color = Color(
    red = ((red * 255).roundToInt() * 0.7).toInt() / 255f,
    green = ((green * 255).roundToInt() * 0.7).toInt() / 255f,
    blue = ((blue * 255).roundToInt() * 0.7).toInt() / 255f,
    alpha = alpha,
)
