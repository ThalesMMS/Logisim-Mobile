/*
 * Adapted from Logisim-evolution I/O components.
 * Copyright by the Logisim-evolution developers.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentState
import dev.logisim.mobile.core.IoAttributes
import dev.logisim.mobile.core.IoButtonState
import dev.logisim.mobile.core.IoDipState
import dev.logisim.mobile.core.IoJoystickState
import dev.logisim.mobile.core.IoKeyboardState
import dev.logisim.mobile.core.IoKeypadState
import dev.logisim.mobile.core.IoKinds
import dev.logisim.mobile.core.IoLampState
import dev.logisim.mobile.core.IoLedState
import dev.logisim.mobile.core.IoLibrary
import dev.logisim.mobile.core.IoLogic
import dev.logisim.mobile.core.IoMatrixState
import dev.logisim.mobile.core.IoTerminalState
import dev.logisim.mobile.core.LogicValue

/** Returns false for other libraries; all I/O drawing reads an already evaluated state. */
fun DrawScope.drawIoComponent(component: CircuitComponent, state: ComponentState?, ticks: Long, measurer: TextMeasurer, printerView: Boolean = false, ink: Color = DefaultComponentInk): Boolean {
    val definition = IoLibrary.definition(component.kind) ?: return false
    val attributes = definition.schema.parse(component.attributes)
    val layout = definition.layout(component, attributes)
    val box = layout.bounds.let { Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
    fun color(name: String, fallback: UInt) = Color(IoAttributes.parseColor(component.attributes[name] ?: "")?.toInt() ?: fallback.toInt())
    val onColor = color("color", if (component.kind in setOf(IoKinds.DotMatrix, IoKinds.LedBar)) 0xff00ff00u else 0xfff00000u)
    val offColor = color("offcolor", if (component.kind == IoKinds.Led) 0xff404040u else 0xffdcdcdcu)
    fun frame(background: Color = Color.White) {
        drawRect(background, box.topLeft, box.size)
        drawRect(ink, box.topLeft, box.size, style = Stroke(1.3f))
    }
    when (component.kind) {
        IoKinds.Button -> {
            val pressed = (state as? IoButtonState)?.pressed == true
            val inset = if (pressed) 3f else 0f
            frame(Color(0xff777777))
            val top = box.topLeft + Offset(inset, inset)
            val size = Size((box.width - 3).coerceAtLeast(0f), (box.height - 3).coerceAtLeast(0f))
            drawRect(color("color", 0xffffffffu), top, size)
            drawRect(ink, top, size, style = Stroke(1f))
        }
        IoKinds.DipSwitch -> {
            val count = attributes[IoAttributes.Switches]
            val positions = (state as? IoDipState)?.positions ?: 0uL
            val facing = component.attributes["facing"] ?: "north"
            val angle = when (facing) { "east" -> 90f; "west" -> 270f; else -> 0f }
            val origin = if (facing == "south") box.topLeft else Offset(component.position.x.toFloat(), component.position.y.toFloat())
            withTransform({ translate(origin.x, origin.y); rotate(angle, Offset.Zero) }) {
                drawRect(Color.DarkGray, Offset.Zero, Size((count + 1) * 10f, 40f))
                repeat(count) { index ->
                    val on = positions and (1uL shl index) != 0uL
                    val x = 7 + index * 10f
                    drawRect(if (on) Color(0xff00cc00) else Color.White, Offset(x, 16f), Size(6f, 20f))
                    drawRect(if (on) Color.DarkGray else Color.Gray, Offset(x + 1, if (on) 17f else 26f), Size(4f, 9f))
                    ioText((index + 1).toString(), Offset(x + 2f, 6f), measurer, Color.White, if (count > 9) 6f else 8f, true)
                }
            }
        }
        IoKinds.Joystick -> {
            frame(color("bg", 0x00ffffffu))
            val joystick = state as? IoJoystickState
            val center = Offset(component.position.x - 15f, component.position.y + 5f)
            val stick = center + Offset(joystick?.x?.toFloat() ?: 0f, joystick?.y?.toFloat() ?: 0f)
            drawCircle(ink, 4f, center)
            drawLine(ink, center, stick, 3f)
            drawCircle(color("color", 0xffff0000u), 4.5f, stick)
            drawCircle(ink, 4.5f, stick, style = Stroke(1f))
        }
        IoKinds.Keyboard -> {
            frame()
            val keyboard = state as? IoKeyboardState
            val start = ((keyboard?.cursor ?: 0) - 17).coerceAtLeast(0)
            val raw = keyboard?.displayText.orEmpty()
            val visible = raw.drop(start).take(19)
            ioText((if (start > 0) "…" else "") + visible, box.topLeft + Offset(7f, 6f), measurer, ink, 12f)
        }
        IoKinds.Led -> {
            val lit = IoLogic.ledOn((state as? IoLedState)?.value ?: LogicValue.UNKNOWN, attributes[IoAttributes.Active])
            val size = Size(box.width - 2f, box.height - 2f)
            drawOval(if (printerView) Color.White else if (lit) onColor else offColor, box.topLeft + Offset(1f, 1f), size)
            drawOval(ink, box.topLeft + Offset(1f, 1f), size, style = Stroke(1.5f))
        }
        IoKinds.RgbLed -> {
            val rgb = IoLogic.rgbColor((state as? IoLampState)?.bits ?: 0, attributes[IoAttributes.Active])
            val size = Size(box.width - 2f, box.height - 2f)
            drawOval(if (printerView) Color.White else Color(rgb.toInt()), box.topLeft + Offset(1f, 1f), size)
            drawOval(ink, box.topLeft + Offset(1f, 1f), size, style = Stroke(1.5f))
        }
        IoKinds.HexDigit, IoKinds.SevenSegment -> {
            frame(color("bg", 0x00ffffffu))
            val bits = (state as? IoLampState)?.bits ?: 0
            val active = component.kind == IoKinds.HexDigit || attributes[IoAttributes.Active]
            val origin = box.topLeft + Offset(5f, 0f)
            val segments = listOf(Rect(3f, 8f, 22f, 12f), Rect(23f, 10f, 27f, 29f), Rect(23f, 30f, 27f, 49f),
                Rect(3f, 47f, 22f, 51f), Rect(-2f, 30f, 2f, 49f), Rect(-2f, 10f, 2f, 29f), Rect(3f, 27f, 22f, 31f))
            segments.forEachIndexed { index, segment ->
                val lit = ((bits ushr index) and 1 == 1) == active
                drawRect(if (printerView) Color.White else if (lit) onColor else offColor, origin + segment.topLeft, segment.size)
                if (printerView) drawRect(ink, origin + segment.topLeft, segment.size, style = Stroke(0.6f))
            }
            if (attributes[IoAttributes.DecimalPoint]) {
                val lit = ((bits ushr 7) and 1 == 1) == active
                drawCircle(if (printerView) Color.White else if (lit) onColor else offColor, 2.5f, origin + Offset(30.5f, 50.5f))
                if (printerView) drawCircle(ink, 2.5f, origin + Offset(30.5f, 50.5f), style = Stroke(0.6f))
            }
        }
        IoKinds.DotMatrix, IoKinds.LedBar -> {
            val matrix = state as? IoMatrixState
            val bar = component.kind == IoKinds.LedBar
            val rows = if (bar) 1 else attributes[IoAttributes.MatrixRows]
            val columns = if (bar) attributes[IoAttributes.BarColumns] else attributes[IoAttributes.MatrixColumns]
            val scaleY = if (bar) attributes[IoAttributes.BarHeight] else 1
            val mode = if (bar) attributes[IoAttributes.BarInput] else attributes[IoAttributes.MatrixInput]
            val origin = when (mode) {
                "column" -> Offset(-5f, -10f * scaleY * rows)
                "row" -> Offset(0f, -5f * scaleY)
                else -> Offset(0f, if (rows == 1) -5f * scaleY else -5f * scaleY * rows + 5f)
            }
            val angle = if (!bar) 0f else when (component.attributes["facing"]) { "south" -> 90f; "west" -> 180f; "north" -> 270f; else -> 0f }
            val shape = if (bar) attributes[IoAttributes.BarShape] else attributes[IoAttributes.DotShape]
            withTransform({ translate(component.position.x.toFloat(), component.position.y.toFloat()); rotate(angle, Offset.Zero) }) {
                drawRect(Color.DarkGray, origin, Size(columns * 10f, rows * 10f * scaleY))
                for (row in 0 until rows) for (column in 0 until columns) {
                    val value = matrix?.takeIf { it.rows == rows && it.columns == columns }?.visible(row, column, ticks) ?: LogicValue.UNKNOWN
                    val tint = if (printerView) Color.White else when (value) { LogicValue.HIGH -> onColor; LogicValue.LOW -> offColor; else -> Color(0xffcc0000) }
                    val at = origin + Offset(column * 10f, row * 10f * scaleY)
                    when (shape) {
                        "circle" -> drawOval(tint, at + Offset(1f, scaleY.toFloat()), Size(8f, 8f * scaleY))
                        "clusterSegment" -> drawRect(tint, at + Offset(2f, 2f * scaleY), Size(6f, 6f * scaleY))
                        else -> drawRect(tint, at, Size(10f, 10f * scaleY))
                    }
                }
                if (!bar) drawRect(ink, origin, Size(columns * 10f, rows * 10f * scaleY), style = Stroke(1.2f))
            }
        }
        IoKinds.Tty -> {
            frame(color("bg", 0x40000000u))
            // Logisim draws a clock symbol and leaves the other TTY pins unlabeled.
            val clockPort = layout.ports.first { it.name == "clock" }.position
            val clock = Offset(clockPort.x.toFloat(), clockPort.y.toFloat())
            val tip = clock + Offset(4f, 0f)
            drawLine(ink, clock + Offset(1f, -3f), tip, strokeWidth = 2f)
            drawLine(ink, clock + Offset(1f, 3f), tip, strokeWidth = 2f)
            val terminal = state as? IoTerminalState
            val textColor = color("color", 0xff000000u)
            val style = TextStyle(color = textColor, fontSize = worldTextSize(11f), fontFamily = FontFamily.Monospace)
            terminal?.displayRows?.forEachIndexed { row, text ->
                val at = box.topLeft + Offset(6f, 6f + row * 15f)
                val measured = measurer.measure(text, style)
                drawCircuitText(measured, topLeft = at)
                if (!printerView && row == terminal.cursorRow) {
                    val x = measured.getCursorRect(terminal.cursorColumn).left
                    val baseline = measurer.measure(" ", style).getLineBaseline(0)
                    drawLine(
                        textColor,
                        at + Offset(x, 0f),
                        at + Offset(x, baseline),
                        strokeWidth = 1f,
                    )
                }
            }
        }
        IoKinds.MatrixKeypad -> {
            frame(Color(0xff323232))
            val scale = if (attributes[IoAttributes.KeypadSize] == "large") 2f else 1f
            val pressed = (state as? IoKeypadState)?.pressed.orEmpty()
            val labels = keypadLabels(component)
            val pmod = attributes[IoAttributes.KeypadMode] == "pmod_kypd"
            for (row in 0..3) for (column in 0..3) {
                val key = row * 4 + column
                val position = box.topLeft + Offset((3f + column * 14f) * scale, (3f + row * 14f) * scale)
                val tint = when {
                    pmod && key in pressed -> Color(0xff0f2d55)
                    pmod -> Color(0xff1e508c)
                    column == 3 || row == 3 && column != 1 -> if (key in pressed) Color(0xffaa1e1e) else Color(0xffdc2828)
                    else -> if (key in pressed) Color(0xff0078aa) else Color(0xff00aadc)
                }
                drawRect(tint, position, Size(12f * scale, 12f * scale))
                val labelColor = if (!pmod && key !in pressed && tint == Color(0xff00aadc)) Color.Black else Color.White
                ioText(labels[key], position + Offset(6f * scale, 2f * scale), measurer, labelColor, 8f * scale, true)
            }
        }
        else -> return false
    }
    return true
}

private fun DrawScope.ioText(text: String, at: Offset, measurer: TextMeasurer, color: Color, fontSize: Float, centered: Boolean = false) {
    val layout = measurer.measure(text, TextStyle(color = color, fontSize = worldTextSize(fontSize), fontFamily = FontFamily.Monospace))
    drawCircuitText(layout, topLeft = if (centered) at - Offset(layout.size.width / 2f, 0f) else at)
}
