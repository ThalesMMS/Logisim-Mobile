package dev.logisim.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.logisim.mobile.core.ComponentKind

/**
 * Toolbar glyphs drawn on the 16-unit grid used by Logisim Evolution's own icons, so the
 * poke hand, edit arrow, wiring tool, pins, gates and simulation controls look familiar.
 */
internal enum class EditorIcon {
    POKE, SELECT, MARQUEE, MOVE, WIRE, TEXT,
    PIN_IN, PIN_OUT, NOT, AND, OR, XOR, NAND, NOR, XNOR, BUFFER, CLOCK, CONSTANT, TUNNEL, SPLITTER, PROBE, LED,
    PLAY, PAUSE, STEP, TICK, TICK_FULL, AUTO_TICK, RESET,
    UNDO, REDO, MENU, SIDEBAR, MORE, FIT, ZOOM_IN, ZOOM_OUT, LIBRARY, PROPERTIES, SIGNALS, CHECK, BACK, NEXT, EXPAND, COLLAPSE,
    ROTATE_LEFT, ROTATE_RIGHT, DELETE, DUPLICATE, COPY, ADD_SELECTION, CLOSE,
    WARNING,
}

/** Logisim's signal colors: true, false and unknown values. */
internal object LogisimColors {
    val True = Color(0xFF00D200)
    val False = Color(0xFF006400)
    val Unknown = Color(0xFF2929FF)
    val Skin = Color(0xFFF0B8A0)
    val Play = Color(0xFF00C800)
    val PlayDark = Color(0xFF007A00)
    val Pause = Color(0xFFE53935)
    val Tick = Color(0xFFB000B0)
}

internal fun ComponentKind.editorIcon(): EditorIcon? = when (this) {
    ComponentKind.INPUT -> EditorIcon.PIN_IN
    ComponentKind.OUTPUT -> EditorIcon.PIN_OUT
    ComponentKind.NOT -> EditorIcon.NOT
    ComponentKind.AND -> EditorIcon.AND
    ComponentKind.OR -> EditorIcon.OR
    ComponentKind.XOR -> EditorIcon.XOR
    ComponentKind.NAND -> EditorIcon.NAND
    ComponentKind.NOR -> EditorIcon.NOR
    ComponentKind.XNOR -> EditorIcon.XNOR
    ComponentKind.BUFFER -> EditorIcon.BUFFER
    ComponentKind.CLOCK -> EditorIcon.CLOCK
    ComponentKind.TEXT -> EditorIcon.TEXT
    else -> when (name) {
        "CONSTANT" -> EditorIcon.CONSTANT
        "TUNNEL" -> EditorIcon.TUNNEL
        "SPLITTER" -> EditorIcon.SPLITTER
        "PROBE" -> EditorIcon.PROBE
        "LED" -> EditorIcon.LED
        else -> null
    }
}

@Composable
internal fun EditorIconGlyph(icon: EditorIcon, modifier: Modifier = Modifier, tint: Color = ChromeColors.Ink, enabled: Boolean = true) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val unit = minOf(size.width, size.height) / 16f
        val alpha = if (enabled) 1f else 0.35f
        translate((size.width - unit * 16f) / 2f, (size.height - unit * 16f) / 2f) {
            scale(unit, unit, pivot = Offset.Zero) {
                drawEditorIcon(icon, tint.copy(alpha = tint.alpha * alpha), alpha)
            }
        }
    }
}

private fun path(block: Path.() -> Unit) = Path().apply(block)

/** Draws in a 16 by 16 unit space. */
private fun DrawScope.drawEditorIcon(icon: EditorIcon, ink: Color, alpha: Float) {
    val medium = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val bold = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun c(color: Color) = color.copy(alpha = color.alpha * alpha)
    fun leads(vararg points: Float) {
        for (i in points.indices step 4) drawLine(ink, Offset(points[i], points[i + 1]), Offset(points[i + 2], points[i + 3]), 1.2f, StrokeCap.Round)
    }
    fun bubble(x: Float) = drawCircle(ink, 1.5f, Offset(x, 8f), style = Stroke(1.2f))
    val andShape = path {
        moveTo(3f, 3f); lineTo(8f, 3f)
        arcTo(Rect(3f, 3f, 13f, 13f), -90f, 180f, false)
        lineTo(3f, 13f); close()
    }
    val orShape = path {
        moveTo(2.5f, 3f); quadraticTo(6.5f, 8f, 2.5f, 13f)
        quadraticTo(10f, 13f, 13.5f, 8f); quadraticTo(10f, 3f, 2.5f, 3f); close()
    }
    when (icon) {
        EditorIcon.POKE -> translate(0f, 0.8f) {
            val hand = path {
                moveTo(6f, 15f); quadraticTo(5f, 10f, 1f, 7f); quadraticTo(2.5f, 4f, 4f, 7f)
                quadraticTo(6f, 6f, 6f, 10f); lineTo(6f, 1f); quadraticTo(7f, -1f, 8f, 1f); lineTo(8f, 8f)
                quadraticTo(9f, 2f, 10f, 8f); quadraticTo(11f, 2f, 12f, 8f); lineTo(12f, 9f)
                quadraticTo(13f, 4f, 14f, 9f); quadraticTo(12f, 11f, 13f, 15f)
            }
            drawPath(hand, c(LogisimColors.Skin))
            drawPath(hand, ink, style = Stroke(1f, join = StrokeJoin.Round))
        }
        EditorIcon.SELECT -> drawPath(path {
            moveTo(4f, 0.5f); lineTo(4f, 14.5f); lineTo(7.4f, 10.6f); lineTo(9.8f, 15.4f)
            lineTo(11.4f, 14.6f); lineTo(9.2f, 10.2f); lineTo(13.5f, 10.2f); close()
        }, ink)
        EditorIcon.MARQUEE -> {
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2f, 1.6f))
            drawRect(ink, Offset(1.5f, 2.5f), Size(10f, 8f), style = Stroke(1.1f, pathEffect = dash))
            drawPath(path { moveTo(9f, 7f); lineTo(9f, 15.5f); lineTo(11f, 13.4f); lineTo(12.4f, 16f); lineTo(13.6f, 15.4f)
                lineTo(12.3f, 12.9f); lineTo(15f, 12.9f); close() }, ink)
        }
        EditorIcon.MOVE -> {
            leads(8f, 2f, 8f, 14f, 2f, 8f, 14f, 8f)
            listOf(
                listOf(8f, 0.8f, 5.8f, 3.6f, 10.2f, 3.6f), listOf(8f, 15.2f, 5.8f, 12.4f, 10.2f, 12.4f),
                listOf(0.8f, 8f, 3.6f, 5.8f, 3.6f, 10.2f), listOf(15.2f, 8f, 12.4f, 5.8f, 12.4f, 10.2f),
            ).forEach { p -> drawPath(path { moveTo(p[0], p[1]); lineTo(p[2], p[3]); lineTo(p[4], p[5]); close() }, ink) }
        }
        EditorIcon.WIRE -> {
            drawPath(path { moveTo(3f, 13f); lineTo(8f, 13f); lineTo(8f, 3f); lineTo(13f, 3f) }, ink, style = bold)
            drawCircle(c(LogisimColors.True), 2.5f, Offset(3.5f, 13f))
            drawCircle(c(LogisimColors.Unknown), 2.5f, Offset(12.5f, 3f))
        }
        EditorIcon.TEXT -> {
            drawPath(path { moveTo(3.5f, 14f); lineTo(8f, 2f); lineTo(12.5f, 14f) }, ink, style = medium)
            drawLine(ink, Offset(5.3f, 9.6f), Offset(10.7f, 9.6f), 1.6f, StrokeCap.Round)
        }
        EditorIcon.PIN_IN, EditorIcon.PIN_OUT -> {
            val output = icon == EditorIcon.PIN_OUT
            if (output) drawCircle(ink, 5f, Offset(8f, 8f), style = Stroke(1.2f))
            else drawRect(ink, Offset(3f, 3f), Size(10f, 10f), style = Stroke(1.2f))
            drawOval(c(LogisimColors.True), Offset(5.6f, 4.6f), Size(4.8f, 6.8f))
            drawCircle(c(LogisimColors.True), 1.6f, Offset(if (output) 1.6f else 14.4f, 8f))
        }
        EditorIcon.AND, EditorIcon.NAND -> {
            leads(0.5f, 5.5f, 3f, 5.5f, 0.5f, 10.5f, 3f, 10.5f)
            drawPath(andShape, ink, style = Stroke(1.2f, join = StrokeJoin.Round))
            if (icon == EditorIcon.NAND) { bubble(14.5f) } else leads(13f, 8f, 15.5f, 8f)
        }
        EditorIcon.OR, EditorIcon.NOR, EditorIcon.XOR, EditorIcon.XNOR -> {
            val exclusive = icon == EditorIcon.XOR || icon == EditorIcon.XNOR
            leads(0.5f, 5.5f, 3.8f, 5.5f, 0.5f, 10.5f, 3.8f, 10.5f)
            drawPath(orShape, ink, style = Stroke(1.2f, join = StrokeJoin.Round))
            if (exclusive) drawPath(path { moveTo(0.8f, 3f); quadraticTo(4.8f, 8f, 0.8f, 13f) }, ink, style = Stroke(1.2f))
            if (icon == EditorIcon.NOR || icon == EditorIcon.XNOR) bubble(15f) else leads(13.5f, 8f, 15.8f, 8f)
        }
        EditorIcon.NOT, EditorIcon.BUFFER -> {
            leads(0.5f, 8f, 3f, 8f)
            drawPath(path { moveTo(3f, 3f); lineTo(3f, 13f); lineTo(11.5f, 8f); close() }, ink, style = Stroke(1.2f, join = StrokeJoin.Round))
            if (icon == EditorIcon.NOT) bubble(13f) else leads(11.5f, 8f, 15.5f, 8f)
        }
        EditorIcon.CLOCK -> {
            drawRect(ink, Offset(2f, 3f), Size(12f, 10f), style = Stroke(1.2f))
            drawPath(path { moveTo(4f, 11f); lineTo(6f, 11f); lineTo(6f, 5f); lineTo(10f, 5f); lineTo(10f, 11f); lineTo(12f, 11f) },
                c(LogisimColors.False), style = Stroke(1.2f, join = StrokeJoin.Round))
        }
        EditorIcon.CONSTANT -> {
            drawLine(ink, Offset(7f, 3f), Offset(7f, 13f), 1.6f, StrokeCap.Round)
            drawLine(ink, Offset(5f, 5f), Offset(7f, 3f), 1.6f, StrokeCap.Round)
            leads(9.5f, 8f, 15f, 8f)
        }
        EditorIcon.TUNNEL -> drawPath(path {
            moveTo(1.5f, 8f); lineTo(5f, 4f); lineTo(14.5f, 4f); lineTo(14.5f, 12f); lineTo(5f, 12f); close()
        }, ink, style = Stroke(1.2f, join = StrokeJoin.Round))
        EditorIcon.SPLITTER -> {
            drawLine(ink, Offset(1f, 8f), Offset(6f, 8f), 2f, StrokeCap.Round)
            drawLine(ink, Offset(6f, 2.5f), Offset(6f, 13.5f), 2f, StrokeCap.Round)
            leads(6f, 3f, 14.5f, 3f, 6f, 8f, 14.5f, 8f, 6f, 13f, 14.5f, 13f)
        }
        EditorIcon.PROBE -> {
            drawRoundRect(ink, Offset(2f, 4f), Size(12f, 8f), CornerRadius(4f), style = Stroke(1.2f))
            drawCircle(c(LogisimColors.True), 1.6f, Offset(8f, 8f))
        }
        EditorIcon.LED -> {
            drawCircle(c(LogisimColors.True.copy(alpha = 0.75f)), 5f, Offset(8f, 8f))
            drawCircle(ink, 5f, Offset(8f, 8f), style = Stroke(1.2f))
        }
        EditorIcon.PLAY, EditorIcon.PAUSE, EditorIcon.STEP, EditorIcon.TICK, EditorIcon.TICK_FULL, EditorIcon.AUTO_TICK -> {
            drawCircle(Color.White.copy(alpha = alpha), 7.4f, Offset(8f, 8f))
            drawCircle(ink.copy(alpha = 0.7f * alpha), 7.4f, Offset(8f, 8f), style = Stroke(1f))
            when (icon) {
                EditorIcon.PLAY, EditorIcon.STEP -> {
                    val shift = if (icon == EditorIcon.STEP) -0.8f else 0.6f
                    val tri = path { moveTo(5.6f + shift, 3.8f); lineTo(11f + shift, 8f); lineTo(5.6f + shift, 12.2f); close() }
                    drawPath(tri, c(LogisimColors.Play))
                    drawPath(tri, c(LogisimColors.PlayDark), style = Stroke(0.9f, join = StrokeJoin.Round))
                    if (icon == EditorIcon.STEP) drawRect(c(LogisimColors.PlayDark), Offset(10.6f, 3.8f), Size(1.6f, 8.4f))
                }
                EditorIcon.PAUSE -> {
                    drawRect(c(LogisimColors.Pause), Offset(5f, 4f), Size(2.2f, 8f))
                    drawRect(c(LogisimColors.Pause), Offset(8.8f, 4f), Size(2.2f, 8f))
                }
                // Evolution's TickFullIcon: a whole clock period beside the half-cycle pulse of TICK.
                EditorIcon.TICK_FULL -> {
                    drawPath(path { moveTo(2.8f, 11f); lineTo(4.6f, 11f); lineTo(4.6f, 5f); lineTo(8f, 5f); lineTo(8f, 11f)
                        lineTo(11.4f, 11f); lineTo(11.4f, 5f); lineTo(13.2f, 5f) },
                        c(LogisimColors.Tick), style = Stroke(1.5f, join = StrokeJoin.Round, cap = StrokeCap.Round))
                }
                EditorIcon.TICK -> {
                    drawPath(path { moveTo(3.6f, 11f); lineTo(5.6f, 11f); lineTo(5.6f, 5f); lineTo(10.4f, 5f); lineTo(10.4f, 11f); lineTo(12.4f, 11f) },
                        c(LogisimColors.Tick), style = Stroke(1.5f, join = StrokeJoin.Round, cap = StrokeCap.Round))
                }
                else -> {
                    drawLine(ink, Offset(8f, 8f), Offset(11.2f, 4.8f), 1.6f, StrokeCap.Round)
                    drawLine(c(LogisimColors.Pause), Offset(8f, 8f), Offset(4.8f, 4.8f), 1.6f, StrokeCap.Round)
                    drawCircle(ink, 0.9f, Offset(8f, 8f))
                }
            }
        }
        EditorIcon.RESET -> {
            drawArc(ink, -60f, 290f, false, Offset(2.5f, 2.5f), Size(11f, 11f), style = medium)
            drawPath(path { moveTo(10.6f, 0.9f); lineTo(14.6f, 3.6f); lineTo(10f, 5.6f); close() }, ink)
        }
        EditorIcon.UNDO, EditorIcon.REDO -> {
            val mirror = icon == EditorIcon.REDO
            scale(if (mirror) -1f else 1f, 1f, pivot = Offset(8f, 8f)) {
                drawPath(path { moveTo(4f, 6.5f); lineTo(10f, 6.5f); quadraticTo(14f, 6.5f, 14f, 10f); quadraticTo(14f, 13.5f, 10f, 13.5f); lineTo(7f, 13.5f) },
                    ink, style = medium)
                drawPath(path { moveTo(1.5f, 6.5f); lineTo(5.5f, 3f); lineTo(5.5f, 10f); close() }, ink)
            }
        }
        EditorIcon.MENU -> leadsBold(ink, 2.5f, 4f, 13.5f, 4f, 2.5f, 8f, 13.5f, 8f, 2.5f, 12f, 13.5f, 12f)
        EditorIcon.SIDEBAR -> {
            drawRoundRect(ink, Offset(1.5f, 2.5f), Size(13f, 11f), CornerRadius(1.5f), style = Stroke(1.3f))
            drawRect(ink, Offset(1.5f, 2.5f), Size(4.5f, 11f))
        }
        EditorIcon.MORE -> listOf(3.5f, 8f, 12.5f).forEach { drawCircle(ink, 1.4f, Offset(it, 8f)) }
        EditorIcon.FIT -> {
            val corners = listOf(
                listOf(2f, 6f, 2f, 2f, 6f, 2f), listOf(10f, 2f, 14f, 2f, 14f, 6f),
                listOf(14f, 10f, 14f, 14f, 10f, 14f), listOf(6f, 14f, 2f, 14f, 2f, 10f),
            )
            corners.forEach { p -> drawPath(path { moveTo(p[0], p[1]); lineTo(p[2], p[3]); lineTo(p[4], p[5]) }, ink, style = medium) }
            drawRect(ink, Offset(5.5f, 5.5f), Size(5f, 5f), style = Stroke(1f))
        }
        EditorIcon.ZOOM_IN, EditorIcon.ZOOM_OUT -> {
            drawLine(ink, Offset(3f, 8f), Offset(13f, 8f), 1.6f, StrokeCap.Round)
            if (icon == EditorIcon.ZOOM_IN) drawLine(ink, Offset(8f, 3f), Offset(8f, 13f), 1.6f, StrokeCap.Round)
        }
        EditorIcon.LIBRARY -> {
            drawRect(ink, Offset(4f, 3f), Size(8f, 10f), style = Stroke(1.2f))
            leads(1.5f, 5.5f, 4f, 5.5f, 1.5f, 10.5f, 4f, 10.5f, 12f, 8f, 14.5f, 8f)
            drawCircle(c(LogisimColors.True), 1.3f, Offset(8f, 8f))
        }
        EditorIcon.PROPERTIES -> {
            drawRect(ink, Offset(1.5f, 2.5f), Size(13f, 11f), style = Stroke(1.2f))
            leads(1.5f, 6.2f, 14.5f, 6.2f, 1.5f, 9.8f, 14.5f, 9.8f, 6.5f, 2.5f, 6.5f, 13.5f)
        }
        EditorIcon.SIGNALS -> drawPath(path {
            moveTo(1f, 11.5f); lineTo(3.5f, 11.5f); lineTo(3.5f, 4.5f); lineTo(7f, 4.5f); lineTo(7f, 11.5f)
            lineTo(10f, 11.5f); lineTo(10f, 4.5f); lineTo(12.5f, 4.5f); lineTo(12.5f, 11.5f); lineTo(15f, 11.5f)
        }, c(LogisimColors.False), style = Stroke(1.5f, join = StrokeJoin.Round, cap = StrokeCap.Round))
        EditorIcon.CHECK -> drawPath(path { moveTo(3f, 8.5f); lineTo(6.5f, 12f); lineTo(13f, 4.5f) }, ink, style = bold)
        EditorIcon.BACK, EditorIcon.NEXT -> scale(if (icon == EditorIcon.NEXT) -1f else 1f, 1f, pivot = Offset(8f, 8f)) {
            drawPath(path { moveTo(10f, 3f); lineTo(5f, 8f); lineTo(10f, 13f) }, ink, style = medium)
        }
        EditorIcon.EXPAND, EditorIcon.COLLAPSE -> {
            listOf(3.5f, 8.5f).forEachIndexed { index, y ->
                val tip = if (icon == EditorIcon.EXPAND) y + 4f else y
                val base = if (icon == EditorIcon.EXPAND) y else y + 4f
                drawPath(path { moveTo(3.5f, base); lineTo(8f, tip); lineTo(12.5f, base) },
                    if (index == 0) ink.copy(alpha = ink.alpha * 0.45f) else ink, style = medium)
            }
        }
        // A quarter-turn arc with its arrowhead: counter-clockwise for left, mirrored for right.
        EditorIcon.ROTATE_LEFT, EditorIcon.ROTATE_RIGHT -> scale(if (icon == EditorIcon.ROTATE_RIGHT) -1f else 1f, 1f, pivot = Offset(8f, 8f)) {
            drawArc(ink, -100f, 270f, false, Offset(3f, 3.5f), Size(10f, 10f), style = medium)
            drawPath(path { moveTo(1.2f, 4.2f); lineTo(6.6f, 2.2f); lineTo(5.2f, 7.6f); close() }, ink)
        }
        EditorIcon.DELETE -> {
            drawLine(ink, Offset(2.5f, 4f), Offset(13.5f, 4f), 1.4f, StrokeCap.Round)
            drawPath(path { moveTo(6f, 4f); lineTo(6.5f, 2f); lineTo(9.5f, 2f); lineTo(10f, 4f) }, ink, style = Stroke(1.2f, join = StrokeJoin.Round))
            drawPath(path { moveTo(4f, 4f); lineTo(5f, 14.5f); lineTo(11f, 14.5f); lineTo(12f, 4f) }, ink, style = Stroke(1.3f, join = StrokeJoin.Round))
            leads(6.9f, 6.5f, 7.1f, 12.5f, 9.1f, 6.5f, 8.9f, 12.5f)
        }
        EditorIcon.DUPLICATE, EditorIcon.COPY -> {
            drawRoundRect(ink, Offset(2f, 2f), Size(8.5f, 8.5f), CornerRadius(1.2f), style = Stroke(1.2f))
            drawRoundRect(ink, Offset(5.5f, 5.5f), Size(8.5f, 8.5f), CornerRadius(1.2f), style = Stroke(1.3f))
            if (icon == EditorIcon.DUPLICATE) {
                drawLine(ink, Offset(9.75f, 7.6f), Offset(9.75f, 11.9f), 1.3f, StrokeCap.Round)
                drawLine(ink, Offset(7.6f, 9.75f), Offset(11.9f, 9.75f), 1.3f, StrokeCap.Round)
            }
        }
        EditorIcon.ADD_SELECTION -> {
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2f, 1.6f))
            drawRect(ink, Offset(1.5f, 1.5f), Size(9f, 9f), style = Stroke(1.1f, pathEffect = dash))
            drawLine(ink, Offset(11.5f, 8f), Offset(11.5f, 15f), 1.8f, StrokeCap.Round)
            drawLine(ink, Offset(8f, 11.5f), Offset(15f, 11.5f), 1.8f, StrokeCap.Round)
        }
        EditorIcon.CLOSE -> leadsBold(ink, 4f, 4f, 12f, 12f, 12f, 4f, 4f, 12f)
        EditorIcon.WARNING -> {
            drawPath(path { moveTo(8f, 1.8f); lineTo(14.8f, 14f); lineTo(1.2f, 14f); close() }, ink,
                style = Stroke(1.4f, join = StrokeJoin.Round))
            drawLine(ink, Offset(8f, 6f), Offset(8f, 9.6f), 1.6f, StrokeCap.Round)
            drawCircle(ink, 0.95f, Offset(8f, 11.9f))
        }
    }
}

private fun DrawScope.leadsBold(ink: Color, vararg points: Float) {
    for (i in points.indices step 4) drawLine(ink, Offset(points[i], points[i + 1]), Offset(points[i + 2], points[i + 3]), 1.6f, StrokeCap.Round)
}
