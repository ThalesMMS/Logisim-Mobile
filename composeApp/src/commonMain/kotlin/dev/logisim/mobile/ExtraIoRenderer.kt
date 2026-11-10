package dev.logisim.mobile

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.BuzzerTone
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentState
import dev.logisim.mobile.core.ExtraIoComponents
import dev.logisim.mobile.core.ExtraIoKinds
import dev.logisim.mobile.core.ExtraOscilloscopeState
import dev.logisim.mobile.core.ExtraSliderState
import dev.logisim.mobile.core.ExtraSwitchState
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.Ne555State
import dev.logisim.mobile.core.TwoPinLedState

private val extraDefinitions = ExtraIoComponents.definitions().associateBy { it.kind }

/** Returns false when the component belongs to another library. */
fun DrawScope.drawExtraIoComponent(component: CircuitComponent, state: ComponentState?, measurer: TextMeasurer, printerView: Boolean = false, ink: Color = DefaultComponentInk): Boolean {
    val definition = extraDefinitions[component.kind] ?: return false
    val layout = definition.layout(component, definition.schema.parse(component.attributes))
    val b = layout.bounds.let { Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
    fun text(value: String, point: Offset, size: Float = 10f, color: Color = ink) {
        val measured = measurer.measure(value, TextStyle(color = color, fontSize = worldTextSize(size)))
        drawCircuitText(measured, topLeft = point - Offset(measured.size.width / 2f, measured.size.height / 2f))
    }
    fun box() {
        drawRoundRect(Color.White, b.topLeft, b.size, CornerRadius(3f, 3f))
        drawRoundRect(ink, b.topLeft, b.size, CornerRadius(3f, 3f), style = Stroke(1f))
    }
    when (component.kind) {
        ExtraIoKinds.BinaryToBcd -> {
            box()
            text("Bin → BCD", b.center)
            layout.ports.forEach { port ->
                text(if (port.name == "binary") "Bin" else "10^${port.name.removePrefix("digit")}",
                    Offset(port.position.x.toFloat() + if (port.name == "binary") 13 else 0,
                        port.position.y.toFloat() + if (port.name == "binary") 0 else 10), 8f)
            }
        }
        ExtraIoKinds.BcdToSevenSegment -> {
            box(); text("BCD", Offset(b.center.x, b.bottom - 13), 9f)
            val center = b.center - Offset(0f, 10f)
            val segments = listOf(-8f to -15f, 8f to -7f, 8f to 7f, -8f to 15f, -9f to 7f, -9f to -7f, -8f to 0f)
            segments.forEachIndexed { index, (x, y) ->
                val horizontal = index in setOf(0, 3, 6)
                drawRect(Color.Gray, center + Offset(x, y), if (horizontal) Size(16f, 2f) else Size(2f, 12f))
            }
        }
        ExtraIoKinds.Switch, ExtraIoKinds.TwoWaySwitch -> {
            box()
            val outputs = layout.ports.filter { it.direction.name == "OUTPUT" }
            val inputs = layout.ports.filter { it.direction.name == "INPUT" }
            val second = (state as? ExtraSwitchState)?.closed == true
            val common = if (component.kind == ExtraIoKinds.Switch) inputs.first() else layout.ports.first()
            val branch = if (component.kind == ExtraIoKinds.Switch) outputs.first() else layout.ports[if (second) 2 else 1]
            val from = common.position.offset()
            var to = branch.position.offset()
            if (component.kind == ExtraIoKinds.Switch && !second) to -= Offset(0f, 10f)
            drawLine(ink, from, to, 2f)
            layout.ports.forEach { drawCircle(ink, 2f, it.position.offset()) }
        }
        ExtraIoKinds.Slider -> {
            box()
            drawLine(ink, Offset(b.left + 10, b.bottom - 10), Offset(b.right - 10, b.bottom - 10), 2f)
            val slider = state as? ExtraSliderState ?: ExtraSliderState().direction(component.attributes["Direction"] == "right_to_left")
            val x = b.left + 5 + slider.position
            drawRoundRect(Color.DarkGray, Offset(x, b.bottom - 16), Size(10f, 12f), CornerRadius(2f, 2f))
            if (!printerView) text(slider.value(component.attributeInt("width", 8)).display, Offset(b.center.x, b.top + 7), 9f)
        }
        ExtraIoKinds.TwoPinLed -> {
            val color = if (printerView) Color.White else when (state as? TwoPinLedState) {
                TwoPinLedState.FORWARD -> extraColor(component.attributes["color"], Color.Red)
                TwoPinLedState.REVERSE -> extraColor(component.attributes["reversecolor"], Color.Green)
                else -> extraColor(component.attributes["offcolor"], Color.DarkGray)
            }
            drawCircle(color, 9f, b.center)
            drawCircle(ink, 9f, b.center, style = Stroke(1f))
            val anode = layout.ports.single { it.name == "anode" }.position.offset()
            val cathode = layout.ports.single { it.name == "cathode" }.position.offset()
            val direction = (cathode - anode) / 20f
            val perpendicular = Offset(-direction.y, direction.x)
            val base = b.center - direction * 4f
            val tip = b.center + direction * 4f
            val diode = Path().apply {
                moveTo((base + perpendicular * 5f).x, (base + perpendicular * 5f).y)
                lineTo(tip.x, tip.y)
                lineTo((base - perpendicular * 5f).x, (base - perpendicular * 5f).y)
                close()
            }
            drawPath(diode, ink, style = Stroke(1f))
            drawLine(ink, b.center + direction * 5f + perpendicular * 5f,
                b.center + direction * 5f - perpendicular * 5f, 1f)
        }
        ExtraIoKinds.PlaRom -> {
            box(); text(component.label.ifBlank { "PLA ROM" }, Offset(b.center.x, b.top + 20), 10f)
            text("${component.attributeInt("inputs", 4)}×${component.attributeInt("and", 4)}×${component.attributeInt("outputs", 4)}", Offset(b.center.x, b.top + 40), 9f)
        }
        ExtraIoKinds.Oscilloscope -> drawExtraOscilloscope(component, state as? ExtraOscilloscopeState, measurer, ink)
        ExtraIoKinds.Ne555 -> {
            box()
            val chip = Rect(b.center - Offset(12f, 26f), b.center + Offset(32f, 25f))
            drawRect(Color(0xffeeeeee), chip.topLeft, chip.size)
            drawRect(ink, chip.topLeft, chip.size, style = Stroke(1f))
            text("NE555", chip.center, 10f)
            text("R=${component.attributeInt("r1", 71660)}Ω", Offset(b.center.x, b.top + 12), 8f)
            text("C=${component.attributeInt("c1", 10)}µF", Offset(b.center.x, b.bottom - 12), 8f)
            val timer = state as? Ne555State
            drawCircle(if (printerView) Color.White else if (timer?.outputHigh == true) Color.Green else Color.DarkGray, 3f, Offset(b.right - 10, b.center.y))
            drawLine(ink, Offset(b.left + 20, b.top + 27), Offset(b.left + 20, b.bottom - 28), 1f)
            drawLine(ink, Offset(b.left + 10, b.center.y - 3), Offset(b.left + 30, b.center.y - 3), 2f)
            drawLine(ink, Offset(b.left + 10, b.center.y + 3), Offset(b.left + 30, b.center.y + 3), 2f)
        }
        ExtraIoKinds.Buzzer -> {
            drawOval(Color.DarkGray, b.topLeft, b.size)
            drawOval(ink, b.topLeft, b.size, style = Stroke(1f))
            repeat(3) { drawCircle(Color.Gray, 8f + it * 4, b.center, style = Stroke(1f)) }
            val tone = state as? BuzzerTone
            if (tone?.audible == true) text("♪", b.center, 14f, Color.White)
        }
    }
    return true
}

private fun GridPoint.offset() = Offset(x.toFloat(), y.toFloat())
private fun extraColor(raw: String?, fallback: Color): Color =
    if (raw != null && raw.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) extraScopeColor(raw) else fallback
