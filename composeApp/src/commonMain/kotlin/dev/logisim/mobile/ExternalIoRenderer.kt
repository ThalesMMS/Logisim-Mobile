package dev.logisim.mobile

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import dev.logisim.mobile.core.AttributeValues
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentState
import dev.logisim.mobile.core.ExternalIoComponents
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.PortIoState
import dev.logisim.mobile.core.RealTimeClockState
import dev.logisim.mobile.core.SystemEpochClock
import dev.logisim.mobile.core.TelnetLinkState
import dev.logisim.mobile.core.TelnetState
import dev.logisim.mobile.core.VideoState
import dev.logisim.mobile.core.VideoColorFormat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val externalDefinitions = ExternalIoComponents.definitions().associateBy { it.kind }
private val externalFault = Color(0xffd62d24)

internal data class ExternalVideoGeometry(val bounds: Rect, val width: Int, val height: Int, val scale: Int) {
    val imageOrigin: Offset get() = bounds.topLeft + Offset(7f, 7f)
    val imageSize: Size get() = Size((width * scale).toFloat(), (height * scale).toFloat())

    fun pixelBounds(x: Int, y: Int, length: Int = 1): Rect = Rect(
        imageOrigin + Offset((x * scale).toFloat(), (y * scale).toFloat()),
        Size((length * scale).toFloat(), scale.toFloat()),
    )
}

internal fun externalVideoGeometry(component: CircuitComponent): ExternalVideoGeometry {
    val definition = externalDefinitions.getValue(ExternalIoComponents.RgbVideo)
    val attributes = definition.schema.parse(component.attributes)
    return externalVideoGeometry(component, attributes)
}

private fun externalVideoGeometry(component: CircuitComponent, attributes: AttributeValues): ExternalVideoGeometry =
    ExternalVideoGeometry(externalBounds(component, attributes), attributes.string("width")!!.toInt(),
        attributes.string("height")!!.toInt(), attributes.string("scale")!!.toInt())

private fun externalBounds(component: CircuitComponent, attributes: AttributeValues): Rect =
    externalDefinitions.getValue(component.kind).layout(component, attributes).bounds.let {
        Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
    }

/** Draws only the body; the canvas owns selection, labels and simulation-colored terminals. */
fun DrawScope.drawExternalIoComponent(component: CircuitComponent, state: ComponentState?, measurer: TextMeasurer, ink: Color = DefaultComponentInk): Boolean {
    val definition = externalDefinitions[component.kind] ?: return false
    val attributes = definition.schema.parse(component.attributes)
    when (component.kind) {
        ExternalIoComponents.PortIo -> drawExternalPortIo(component, state as? PortIoState, attributes, ink)
        ExternalIoComponents.RgbVideo -> drawExternalVideo(component, state as? VideoState, attributes, ink)
        ExternalIoComponents.RealTimeClock -> drawExternalClock(component, state as? RealTimeClockState, attributes, measurer, ink)
        ExternalIoComponents.Telnet -> drawExternalTelnet(component, state as? TelnetState, attributes, measurer, ink)
        ExternalIoComponents.ReptarLocalBus -> {
            val bounds = externalBounds(component, attributes)
            externalBox(bounds, ink)
            val center = bounds.topLeft + Offset(11f, 11f)
            drawCircle(Color(0xffa65d00), 5f, center, style = Stroke(1.2f))
            drawLine(Color(0xffa65d00), center + Offset(-3.5f, 3.5f), center + Offset(3.5f, -3.5f), 1.2f)
        }
    }
    return true
}

private fun DrawScope.drawExternalPortIo(component: CircuitComponent, state: PortIoState?, attributes: AttributeValues, ink: Color) {
    val count = attributes.string("number")!!.toInt()
    val width = 10f + (maxOf(8, count) + 1) / 2 * 10f
    val angle = when (component.facing) { "south" -> 90f; "west" -> 180f; "north" -> 270f; else -> 0f }
    withTransform({
        translate(component.position.x.toFloat(), component.position.y.toFloat())
        rotate(angle, Offset.Zero)
    }) {
        val packagePath = Path().apply {
            moveTo(1f, 20f); lineTo(1f, 42f); lineTo(5f, 46f)
            lineTo(width - 6f, 46f); lineTo(width - 2f, 42f); lineTo(width - 2f, 20f); close()
        }
        drawPath(packagePath, Color.DarkGray)
        drawPath(packagePath, ink, style = Stroke(1f))
        for (index in 0 until count) {
            val value = if (state != null && index < state.pins.width) state.pins.bit(index) else null
            drawRect(externalPinColor(value),
                Offset(7f + index / 2 * 10f, 25f + index % 2 * 10f), Size(6f, 6f))
        }
        val direction = attributes.string("direction")
        val bidirectional = direction == "IOSingleEnable" || direction == "IOMultiEnable"
        var x = if (bidirectional) 0f else 10f
        if (bidirectional) {
            drawLine(ink, Offset(x, 10f), Offset(x + 6f, 10f), 3f)
            x += 10f
        }
        if (direction != "onlyinput") {
            drawLine(ink, Offset(x, 0f), Offset(x, 4f), 3f)
            drawLine(ink, Offset(x, 15f), Offset(x, 20f), 3f)
            drawPath(Path().apply {
                moveTo(x, 15f); lineTo(x - 4f, 5f); lineTo(x + 4f, 5f); close()
            }, ink, style = Stroke(2f))
            x += 10f
        }
        if (direction != "onlyOutput") {
            drawLine(ink, Offset(x, 0f), Offset(x, 5f), 3f)
            drawLine(ink, Offset(x, 16f), Offset(x, 20f), 3f)
            drawPath(Path().apply {
                moveTo(x, 6f); lineTo(x - 4f, 16f); lineTo(x + 4f, 16f); close()
            }, ink, style = Stroke(2f))
        }
    }
}

internal fun externalPinColor(value: LogicValue?): Color = when (value) {
    LogicValue.LOW -> Color(0xff006400)
    LogicValue.HIGH -> Color(0xff00d200)
    LogicValue.ERROR -> externalFault
    else -> Color(0xffc0c0c0)
}

private fun DrawScope.drawExternalVideo(component: CircuitComponent, state: VideoState?, attributes: AttributeValues, ink: Color) {
    val geometry = externalVideoGeometry(component, attributes)
    drawRoundRect(Color.White, geometry.bounds.topLeft, geometry.bounds.size, CornerRadius(3f))
    drawRoundRect(ink, geometry.bounds.topLeft, geometry.bounds.size, CornerRadius(3f), style = Stroke(1f))
    val imageBorder = Rect(geometry.imageOrigin - Offset(1f, 1f), Size(geometry.imageSize.width + 2f, geometry.imageSize.height + 2f))
    drawRect(ink, imageBorder.topLeft, imageBorder.size, style = Stroke(1f))
    drawRect(Color.Black, geometry.imageOrigin, geometry.imageSize)
    state?.frame?.forEachColorRun { run ->
        if (run.y < geometry.height && run.x < geometry.width) {
            val rect = geometry.pixelBounds(run.x, run.y, minOf(run.length, geometry.width - run.x))
            drawRect(Color(run.argb), rect.topLeft, rect.size)
        }
    }
    if (state != null && attributes.string("cursor") == "Blinking Dot" &&
        state.cursorX in 0 until geometry.width && state.cursorY in 0 until geometry.height &&
        ExternalIoComponents.isVideoCursorVisible(SystemEpochClock.epochMillis())) {
        val cursor = geometry.pixelBounds(state.cursorX, state.cursorY)
        val color = VideoColorFormat.fromFileValue(attributes.string("color")!!).argb(state.cursorColor)
        drawRect(Color(color), cursor.topLeft, cursor.size)
    }
}

internal data class ExternalClockHands(val hour: Offset, val minute: Offset)

/** Offsets from the clock center, based solely on the captured simulation sample (UTC). */
internal fun externalClockHands(state: RealTimeClockState?, diameter: Float): ExternalClockHands {
    val millis = state?.epochMillis ?: 36_600_000L
    val seconds = millis / 1000 - if (millis < 0 && millis % 1000 != 0L) 1 else 0
    fun end(period: Long, fraction: Float): Offset {
        val position = ((seconds % period + period) % period).toDouble() / period
        val angle = position * 2 * PI
        val length = diameter * fraction
        return Offset((sin(angle) * length).toFloat(), (-cos(angle) * length).toFloat())
    }
    return ExternalClockHands(end(43_200, .24f), end(3_600, .32f))
}

private fun DrawScope.drawExternalClock(component: CircuitComponent, state: RealTimeClockState?,
                                        attributes: AttributeValues, measurer: TextMeasurer, ink: Color) {
    val bounds = externalBounds(component, attributes)
    externalBox(bounds, ink)
    val narrow = attributes.string("porsize") == "2"
    val header = if (narrow) 7f else 15f
    externalText("RTC", Offset(bounds.center.x, bounds.top + header / 2f), measurer,
        if (narrow) 6f else 12f, ink, bold = true)
    val diameter = bounds.height - 4f - header
    val center = Offset(bounds.center.x, bounds.top + header + 1f + diameter / 2f)
    drawCircle(Color.DarkGray, diameter / 2f, center, style = Stroke(1.2f))
    val hands = externalClockHands(state, diameter)
    drawLine(ink, center, center + hands.hour, 1f)
    drawLine(ink, center, center + hands.minute, 1f)
}

internal fun externalTelnetIndicator(link: TelnetLinkState?): Color = when (link) {
    TelnetLinkState.Connected -> Color(0xff00b000)
    TelnetLinkState.Listening -> Color(0xffd59100)
    is TelnetLinkState.Failed -> externalFault
    else -> Color(0xff7a7a7a)
}

private fun DrawScope.drawExternalTelnet(component: CircuitComponent, state: TelnetState?,
                                         attributes: AttributeValues, measurer: TextMeasurer, ink: Color) {
    val bounds = externalBounds(component, attributes)
    externalBox(bounds, ink)
    externalText("Telnet", Offset(bounds.center.x, bounds.top + 9f), measurer, 11f, ink)
    externalText(attributes.string("port")!!, Offset(bounds.center.x, bounds.top + 22f), measurer, 9f, ink)
    val indicator = Offset(bounds.center.x, bounds.top + 32f)
    drawCircle(externalTelnetIndicator(state?.link), 2.5f, indicator)
    when (state?.link) {
        TelnetLinkState.Connected -> drawLine(ink, indicator + Offset(-5f, 0f), indicator + Offset(5f, 0f), 1f)
        TelnetLinkState.Listening -> drawCircle(ink, 4.5f, indicator, style = Stroke(.6f))
        is TelnetLinkState.Failed -> {
            drawLine(ink, indicator + Offset(-3f, -3f), indicator + Offset(3f, 3f), 1f)
            drawLine(ink, indicator + Offset(-3f, 3f), indicator + Offset(3f, -3f), 1f)
        }
        else -> Unit
    }
}

private fun DrawScope.externalBox(bounds: Rect, ink: Color) {
    drawRect(Color.White, bounds.topLeft, bounds.size)
    drawRect(ink, bounds.topLeft, bounds.size, style = Stroke(1.5f))
}

private fun DrawScope.externalText(text: String, center: Offset, measurer: TextMeasurer, size: Float, ink: Color, bold: Boolean = false) {
    val measured = measurer.measure(text, TextStyle(color = ink, fontSize = worldTextSize(size),
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
    drawCircuitText(measured, topLeft = center - Offset(measured.size.width / 2f, measured.size.height / 2f))
}
