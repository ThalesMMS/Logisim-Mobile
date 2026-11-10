package dev.logisim.mobile

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathIterator
import androidx.compose.ui.graphics.PathSegment
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import dev.logisim.mobile.core.VisualBounds
import dev.logisim.mobile.core.VisualExport
import dev.logisim.mobile.core.VisualExportOptions
import dev.logisim.mobile.core.VisualItem
import dev.logisim.mobile.core.VisualScene
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import dev.logisim.mobile.core.VisualPng
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Use the same drawing as the canvas, including the default ANSI symbols. Like Evolution's TikZWriter,
 * the canvas records each drawing call in output pixels and writes it as TikZ ([x=1pt,y=-1pt]).
 */
internal fun renderDrawingTikz(
    bounds: VisualBounds,
    options: VisualExportOptions,
    density: Density,
    draw: DrawScope.() -> Unit,
): ByteArray {
    val recording = recordDrawing(bounds, options, density, draw)
    // TeX dimensions have a limit of approximately 16384pt, including intermediate transforms.
    requireLocalized(recording.width <= 16000 && recording.height <= 16000) { message("visual_export_dimensions_large") }
    return recording.tikz().encodeToByteArray()
}

/** SVG of any drawing, written from the same recording as TikZ, as Evolution's ExportImage does. */
internal fun renderDrawingSvg(
    bounds: VisualBounds,
    options: VisualExportOptions,
    density: Density,
    draw: DrawScope.() -> Unit,
): ByteArray = recordDrawing(bounds, options, density, draw).svg().encodeToByteArray()

internal fun recordDrawing(bounds: VisualBounds, options: VisualExportOptions, density: Density, draw: DrawScope.() -> Unit): DrawingRecording {
    val frame = VisualExport.frame(VisualScene(listOf(VisualItem("drawing", bounds, emptyList()))), options)
    val canvas = TikzCanvas(frame.scale)
    val width = frame.pixelWidth.toFloat()
    val height = frame.pixelHeight.toFloat()
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, Size(width, height)) {
        canvas.translate(-frame.bounds.left.toFloat(), -frame.bounds.top.toFloat())
        frame.clip?.let { canvas.clipRect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
        draw()
    }
    canvas.finish()
    return DrawingRecording(frame.pixelWidth, frame.pixelHeight, frame.background, canvas.operations)
}

/** Compose text paints into the platform canvas directly. Record its measured lines before that step. */
internal fun DrawScope.drawCircuitText(layout: TextLayoutResult, topLeft: Offset) {
    val tikz = drawContext.canvas as? TikzCanvas
    if (tikz == null) drawText(layout, topLeft = topLeft) else tikz.text(layout, topLeft)
}

internal inline fun DrawScope.withTikzDash(pattern: List<Double>, draw: () -> Unit) {
    val canvas = drawContext.canvas as? TikzCanvas
    if (canvas == null || pattern.isEmpty()) {
        draw()
    } else {
        val previous = canvas.dashPattern
        canvas.dashPattern = pattern
        try { draw() } finally { canvas.dashPattern = previous }
    }
}

/** Geometry in output pixels (y down). */
internal sealed interface RecordedShape {
    data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) : RecordedShape
    data class Ellipse(val cx: Double, val cy: Double, val rx: Double, val ry: Double) : RecordedShape
    /** Commands: 'M'/'L' (x, y), 'Q' (cx, cy, x, y), 'C' (c1x, c1y, c2x, c2y, x, y), 'Z'. */
    data class Outline(val commands: List<Pair<Char, DoubleArray>>) : RecordedShape
}

internal data class RecordedStroke(val width: Double, val cap: StrokeCap, val join: StrokeJoin, val dash: List<Double>)

internal sealed interface RecordedOperation {
    /** [stroke] null means a fill. */
    data class Shape(val shape: RecordedShape, val argb: Int, val stroke: RecordedStroke?, val evenOdd: Boolean = false) : RecordedOperation
    data class Text(val x: Double, val y: Double, val content: String, val family: FontFamily?, val size: Double,
        val bold: Boolean, val italic: Boolean, val argb: Int, val rotation: Double) : RecordedOperation
    /** [transform] maps the source pixels (a b c d e f, SVG order) into the output. */
    class Image(val transform: DoubleArray, val width: Int, val height: Int, val png: ByteArray?, val corner: Pair<Double, Double>,
        val opposite: Pair<Double, Double>) : RecordedOperation
    data class ClipBegin(val shape: RecordedShape) : RecordedOperation
    data object ClipEnd : RecordedOperation
}

internal class DrawingRecording(val width: Int, val height: Int, val background: UInt?, val operations: List<RecordedOperation>) {
    private fun hex(argb: Int) = (argb.toUInt() and 0xffffffu).toString(16).uppercase().padStart(6, '0')
    private fun opacity(argb: Int) = (argb ushr 24) / 255.0

    fun tikz(): String {
        val fonts = mutableListOf<String>()
        val colors = linkedSetOf<String>()
        val body = StringBuilder()
        fun color(argb: Int) = hex(argb).also { colors += it }.let { "custcol_$it" }
        fun point(x: Double, y: Double) = "(${exportNumber(x)},${exportNumber(y)})"
        background?.let { body.appendLine("\\fill [line width=1pt, ${color(it.toInt())}${fillOpacity(opacity(it.toInt()))}] (0,0) rectangle ($width,$height);") }
            ?: body.appendLine("\\useasboundingbox (0,0) rectangle ($width,$height);")
        fun geometry(shape: RecordedShape): String = when (shape) {
            is RecordedShape.Box -> "${point(shape.left, shape.top)} rectangle ${point(shape.right, shape.bottom)}"
            is RecordedShape.Ellipse -> if (shape.rx == shape.ry) "${point(shape.cx, shape.cy)} circle (${exportNumber(shape.rx)})"
                else "${point(shape.cx, shape.cy)} ellipse (${exportNumber(shape.rx)} and ${exportNumber(shape.ry)})"
            is RecordedShape.Outline -> buildString {
                var lastX = 0.0
                var lastY = 0.0
                var startX = 0.0
                var startY = 0.0
                for ((command, p) in shape.commands) {
                    when (command) {
                        'M' -> { if (isNotEmpty()) append(' '); append(point(p[0], p[1])); startX = p[0]; startY = p[1]; lastX = p[0]; lastY = p[1] }
                        'L' -> { append(" -- ").append(point(p[0], p[1])); lastX = p[0]; lastY = p[1] }
                        'Q' -> {
                            // Degree elevation, as Evolution writes quadratic segments.
                            val first = point(lastX / 3 + p[0] * 2 / 3, lastY / 3 + p[1] * 2 / 3)
                            val second = point(p[0] * 2 / 3 + p[2] / 3, p[1] * 2 / 3 + p[3] / 3)
                            append(" .. controls $first and $second .. ").append(point(p[2], p[3])); lastX = p[2]; lastY = p[3]
                        }
                        'C' -> { append(" .. controls ${point(p[0], p[1])} and ${point(p[2], p[3])} .. ").append(point(p[4], p[5])); lastX = p[4]; lastY = p[5] }
                        'Z' -> { append(" -- cycle"); lastX = startX; lastY = startY }
                    }
                }
            }
        }
        for (operation in operations) when (operation) {
            is RecordedOperation.Shape -> {
                val stroke = operation.stroke
                val options = StringBuilder()
                if (stroke == null) {
                    options.append("line width=1pt, ${color(operation.argb)}${fillOpacity(opacity(operation.argb))}")
                } else {
                    options.append("line width=${exportNumber(stroke.width)}pt, ${color(operation.argb)}")
                    if (opacity(operation.argb) != 1.0) options.append(", draw opacity=${exportNumber(opacity(operation.argb))}")
                    when (stroke.cap) { StrokeCap.Round -> options.append(", line cap=round"); StrokeCap.Butt -> options.append(", line cap=butt") }
                    when (stroke.join) { StrokeJoin.Round -> options.append(", line join=round"); StrokeJoin.Bevel -> options.append(", line join=bevel") }
                    if (stroke.dash.isNotEmpty()) options.append(stroke.dash.mapIndexed { index, interval ->
                        "${if (index % 2 == 0) "on" else "off"} ${exportNumber(interval)}pt"
                    }.joinToString(" ", prefix = ", dash pattern="))
                }
                if (operation.evenOdd) options.append(", even odd rule")
                body.appendLine("${if (stroke == null) "\\fill" else "\\draw"} [$options] ${geometry(operation.shape)};")
            }
            is RecordedOperation.Text -> {
                val family = when (operation.family) {
                    FontFamily.Monospace -> "\\texttt{#1}"
                    FontFamily.Serif -> "\\textrm{#1}"
                    else -> "\\textsf{#1}"
                }
                if (family !in fonts) fonts += family
                val size = exportNumber(operation.size)
                body.append("\\logisimfont${fontName(fonts.indexOf(family))}{\\fontsize{${size}pt}{${size}pt}")
                if (operation.bold) body.append("\\fontseries{bx}")
                if (operation.italic) body.append("\\fontshape{it}")
                body.append("\\selectfont\\node[inner sep=0, outer sep=0, ${color(operation.argb)}, anchor=base west")
                if (opacity(operation.argb) != 1.0) body.append(", text opacity=${exportNumber(opacity(operation.argb))}")
                if (operation.rotation != 0.0) body.append(", rotate=${exportNumber(-operation.rotation)}")
                body.appendLine("] at ${point(operation.x, operation.y)} {${escapeTex(operation.content)}};}")
            }
            is RecordedOperation.Image -> {
                // Evolution also writes a labeled placeholder for raster images in its TikZ export.
                body.appendLine("% Raster image omitted: TikZ image export is not supported.")
                val (x, y) = operation.corner
                val (x2, y2) = operation.opposite
                body.appendLine("\\draw [line width=1pt, ${color(0xff000000.toInt())}] ${point(x, y)} rectangle ${point(x2, y2)} ;" +
                    "\\node[anchor=north west, font=\\small] at ${point(x, y)} {[Image: ${operation.width}x${operation.height}]};")
            }
            is RecordedOperation.ClipBegin -> body.appendLine("\\begin{scope}\\clip ${geometry(operation.shape)};")
            RecordedOperation.ClipEnd -> body.appendLine("\\end{scope}")
        }
        return buildString {
            appendLine("% Important: If latex complains about unicode characters,")
            appendLine("% please use \"\\usepackage[utf8x]{inputenc}\" in your preamble")
            appendLine("% You can change the size of the picture by putting it into the construct:")
            appendLine("% 1) \\resizebox{10cm}{!}{\"below picture\"} to scale horizontally to 10 cm")
            appendLine("% 2) \\resizebox{!}{15cm}{\"below picture\"} to scale vertically to 15 cm")
            appendLine("% 3) \\resizebox{10cm}{15cm}{\"below picture\"} a combination of above two")
            appendLine("% It is not recomended to use the scale option of the tikzpicture environment.")
            appendLine("\\begin{tikzpicture}[x=1pt,y=-1pt,line cap=rect]")
            fonts.forEachIndexed { index, command -> appendLine("\\def\\logisimfont${fontName(index)}#1{$command}") }
            for (rgb in colors) appendLine("\\definecolor{custcol_$rgb}{HTML}{$rgb}")
            append(body)
            appendLine("\\end{tikzpicture}")
        }
    }

    private fun fillOpacity(value: Double) = if (value != 1.0) ", fill opacity=${exportNumber(value)}" else ""

    @OptIn(ExperimentalEncodingApi::class)
    fun svg(): String {
        val body = StringBuilder()
        val indent = "   "
        var clips = 0
        var depth = 1
        fun pad() = indent.repeat(depth)
        fun paint(argb: Int, stroke: RecordedStroke?, evenOdd: Boolean = false): String = buildString {
            val ink = "#${hex(argb)}"
            val alpha = opacity(argb)
            if (stroke == null) {
                append(" fill=\"$ink\"")
                if (alpha != 1.0) append(" fill-opacity=\"${exportNumber(alpha)}\"")
                if (evenOdd) append(" fill-rule=\"evenodd\"")
                append(" stroke=\"none\"")
            } else {
                append(" fill=\"none\" stroke=\"$ink\"")
                if (alpha != 1.0) append(" stroke-opacity=\"${exportNumber(alpha)}\"")
                append(" stroke-linecap=\"${when (stroke.cap) { StrokeCap.Round -> "round"; StrokeCap.Butt -> "butt"; else -> "square" }}\"")
                when (stroke.join) { StrokeJoin.Round -> append(" stroke-linejoin=\"round\""); StrokeJoin.Bevel -> append(" stroke-linejoin=\"bevel\"") }
                if (stroke.dash.isNotEmpty()) append(" stroke-dasharray=\"${stroke.dash.joinToString(" ") { exportNumber(it) }}\"")
                append(" stroke-width=\"${exportNumber(stroke.width)}\"")
            }
        }
        fun element(shape: RecordedShape, attributes: String): String = when (shape) {
            is RecordedShape.Box -> "<rect x=\"${exportNumber(minOf(shape.left, shape.right))}\" y=\"${exportNumber(minOf(shape.top, shape.bottom))}\" " +
                "width=\"${exportNumber(abs(shape.right - shape.left))}\" height=\"${exportNumber(abs(shape.bottom - shape.top))}\"$attributes/>"
            is RecordedShape.Ellipse -> if (shape.rx == shape.ry)
                "<circle cx=\"${exportNumber(shape.cx)}\" cy=\"${exportNumber(shape.cy)}\" r=\"${exportNumber(shape.rx)}\"$attributes/>"
                else "<ellipse cx=\"${exportNumber(shape.cx)}\" cy=\"${exportNumber(shape.cy)}\" rx=\"${exportNumber(shape.rx)}\" ry=\"${exportNumber(shape.ry)}\"$attributes/>"
            is RecordedShape.Outline -> "<path d=\"${pathData(shape)}\"$attributes/>"
        }
        background?.let { body.appendLine("$indent<rect x=\"0\" y=\"0\" width=\"$width\" height=\"$height\"${paint(it.toInt(), null)}/>") }
        for (operation in operations) when (operation) {
            is RecordedOperation.Shape -> body.appendLine(pad() + element(operation.shape, paint(operation.argb, operation.stroke, operation.evenOdd)))
            is RecordedOperation.Text -> {
                val family = when (operation.family) { FontFamily.Monospace -> "monospace"; FontFamily.Serif -> "serif"; else -> "sans-serif" }
                val attributes = StringBuilder(" x=\"${exportNumber(operation.x)}\" y=\"${exportNumber(operation.y)}\" font-family=\"$family\" font-size=\"${exportNumber(operation.size)}\"")
                if (operation.bold) attributes.append(" font-weight=\"bold\"")
                if (operation.italic) attributes.append(" font-style=\"italic\"")
                if (operation.rotation != 0.0) attributes.append(" transform=\"rotate(${exportNumber(operation.rotation)},${exportNumber(operation.x)},${exportNumber(operation.y)})\"")
                attributes.append(" fill=\"#${hex(operation.argb)}\"")
                if (opacity(operation.argb) != 1.0) attributes.append(" fill-opacity=\"${exportNumber(opacity(operation.argb))}\"")
                body.appendLine("${pad()}<text$attributes xml:space=\"preserve\">${escapeXml(operation.content)}</text>")
            }
            is RecordedOperation.Image -> {
                val t = operation.transform.joinToString(" ") { exportNumber(it) }
                val href = operation.png?.let { "data:image/png;base64,${Base64.encode(it)}" }
                if (href != null) body.appendLine("${pad()}<image x=\"0\" y=\"0\" width=\"${operation.width}\" height=\"${operation.height}\" " +
                    "preserveAspectRatio=\"none\" transform=\"matrix($t)\" xlink:href=\"$href\"/>")
            }
            is RecordedOperation.ClipBegin -> {
                val id = "clip${clips++}"
                body.appendLine("${pad()}<clipPath id=\"$id\">${element(operation.shape, "")}</clipPath>")
                body.appendLine("${pad()}<g clip-path=\"url(#$id)\">")
                depth++
            }
            RecordedOperation.ClipEnd -> { depth--; body.appendLine("${pad()}</g>") }
        }
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>")
            appendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" version=\"1.1\" " +
                "viewBox=\"0 0 $width $height\" width=\"$width\" height=\"$height\">")
            append(body)
            appendLine("</svg>")
        }
    }

    /** Axis-aligned line runs use H and V, as Evolution's SVG export does. */
    private fun pathData(shape: RecordedShape.Outline): String = buildString {
        var x = 0.0
        var y = 0.0
        var startX = 0.0
        var startY = 0.0
        for ((command, p) in shape.commands) {
            if (isNotEmpty()) append(' ')
            when (command) {
                'M' -> { append("M${exportNumber(p[0])},${exportNumber(p[1])}"); x = p[0]; y = p[1]; startX = x; startY = y }
                'L' -> {
                    when {
                        p[1] == y && p[0] != x -> append("H${exportNumber(p[0])}")
                        p[0] == x && p[1] != y -> append("V${exportNumber(p[1])}")
                        else -> append("L${exportNumber(p[0])},${exportNumber(p[1])}")
                    }
                    x = p[0]; y = p[1]
                }
                'Q' -> { append("Q${exportNumber(p[0])},${exportNumber(p[1])} ${exportNumber(p[2])},${exportNumber(p[3])}"); x = p[2]; y = p[3] }
                'C' -> { append("C${exportNumber(p[0])},${exportNumber(p[1])} ${exportNumber(p[2])},${exportNumber(p[3])} ${exportNumber(p[4])},${exportNumber(p[5])}"); x = p[4]; y = p[5] }
                'Z' -> { append("Z"); x = startX; y = startY }
            }
        }
    }

    private fun fontName(index: Int): String {
        var number = index + 1
        val name = StringBuilder()
        while (number > 0) {
            number--
            name.append('A' + number % 26)
            number /= 26
        }
        return name.reverse().toString()
    }
}

internal fun exportNumber(value: Double): String {
    val rounded = round(value * 10000).toLong()
    val fraction = (abs(rounded) % 10000).toString().padStart(4, '0').trimEnd('0')
    val whole = (abs(rounded) / 10000).toString()
    return (if (rounded < 0) "-" else "") + whole + if (fraction.isEmpty()) "" else ".$fraction"
}

private fun escapeTex(text: String): String = buildString {
    for (char in text) append(when (char) {
        '\\' -> "\\textbackslash{}"
        '{', '}', '#', '$', '%', '&', '_' -> "\\$char"
        '^' -> "\\textasciicircum{}"
        '~' -> "\\textasciitilde{}"
        ' ' -> "\\ "
        '\t' -> "\\quad{}"
        else -> char.toString()
    })
}

private fun escapeXml(text: String): String = buildString {
    for (char in text) append(when (char) {
        '&' -> "&amp;"
        '<' -> "&lt;"
        '>' -> "&gt;"
        '"' -> "&quot;"
        else -> if (char.code < 0x20 && char != '\t') "" else char.toString()
    })
}

/** Records Compose canvas calls in output pixels, tracking the transform as Evolution's TikZInfo does. */
internal class TikzCanvas(scale: Double) : Canvas {
    val operations = mutableListOf<RecordedOperation>()
    var dashPattern: List<Double> = emptyList()

    // x' = a x + c y + e, y' = b x + d y + f
    private var m = doubleArrayOf(scale, 0.0, 0.0, scale, 0.0, 0.0)
    private val saved = ArrayDeque<Pair<DoubleArray, Int>>()
    private var openClips = 0

    private fun map(x: Double, y: Double) = Pair(m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5])
    private val linearScale get() = sqrt(abs(m[0] * m[3] - m[1] * m[2]))
    private val axisAligned get() = (m[1] == 0.0 && m[2] == 0.0) || (m[0] == 0.0 && m[3] == 0.0)

    /** Closes the clips opened outside any save, such as the frame clip. */
    fun finish() {
        while (saved.isNotEmpty()) restore()
        repeat(openClips) { operations += RecordedOperation.ClipEnd }
        openClips = 0
    }

    override fun save() { saved.addLast(m.copyOf() to openClips); openClips = 0 }
    override fun restore() {
        repeat(openClips) { operations += RecordedOperation.ClipEnd }
        openClips = 0
        val (matrix, clips) = saved.removeLastOrNull() ?: return
        m = matrix
        openClips = clips
    }
    private fun concat(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double) {
        m = doubleArrayOf(
            m[0] * a + m[2] * b, m[1] * a + m[3] * b,
            m[0] * c + m[2] * d, m[1] * c + m[3] * d,
            m[0] * e + m[2] * f + m[4], m[1] * e + m[3] * f + m[5],
        )
    }
    override fun translate(dx: Float, dy: Float) = concat(1.0, 0.0, 0.0, 1.0, dx.toDouble(), dy.toDouble())
    override fun scale(sx: Float, sy: Float) = concat(sx.toDouble(), 0.0, 0.0, sy.toDouble(), 0.0, 0.0)
    override fun rotate(degrees: Float) {
        val radians = degrees * PI / 180
        // Exact quarter turns keep rectangles and circles as such.
        val c = round(cos(radians) * 1e12) / 1e12
        val s = round(sin(radians) * 1e12) / 1e12
        concat(c, s, -s, c, 0.0, 0.0)
    }
    override fun skew(sx: Float, sy: Float) = concat(1.0, sy.toDouble(), sx.toDouble(), 1.0, 0.0, 0.0)
    override fun concat(matrix: Matrix) = concat(matrix[0, 0].toDouble(), matrix[0, 1].toDouble(), matrix[1, 0].toDouble(),
        matrix[1, 1].toDouble(), matrix[3, 0].toDouble(), matrix[3, 1].toDouble())

    private fun box(left: Float, top: Float, right: Float, bottom: Float): RecordedShape {
        if (axisAligned) {
            val (x1, y1) = map(left.toDouble(), top.toDouble())
            val (x2, y2) = map(right.toDouble(), bottom.toDouble())
            return RecordedShape.Box(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
        }
        return outline(listOf('M' to floatArrayOf(left, top), 'L' to floatArrayOf(right, top), 'L' to floatArrayOf(right, bottom),
            'L' to floatArrayOf(left, bottom), 'Z' to floatArrayOf()))
    }

    private fun outline(commands: List<Pair<Char, FloatArray>>) = RecordedShape.Outline(commands.map { (command, p) ->
        command to DoubleArray(p.size).also { out ->
            for (i in p.indices step 2) {
                val (x, y) = map(p[i].toDouble(), p[i + 1].toDouble())
                out[i] = x
                out[i + 1] = y
            }
        }
    })

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): RecordedShape {
        val (x, y) = map(cx.toDouble(), cy.toDouble())
        val sx = hypot(m[0], m[1])
        val sy = hypot(m[2], m[3])
        val orthogonal = abs(m[0] * m[2] + m[1] * m[3]) < 1e-9
        return when {
            rx == ry && orthogonal && abs(sx - sy) < 1e-9 -> RecordedShape.Ellipse(x, y, rx * sx, rx * sx)
            m[1] == 0.0 && m[2] == 0.0 -> RecordedShape.Ellipse(x, y, rx * abs(m[0]), ry * abs(m[3]))
            m[0] == 0.0 && m[3] == 0.0 -> RecordedShape.Ellipse(x, y, ry * abs(m[2]), rx * abs(m[1]))
            else -> {
                val k = 0.5522847498f
                outline(listOf(
                    'M' to floatArrayOf(cx + rx, cy),
                    'C' to floatArrayOf(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry),
                    'C' to floatArrayOf(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy),
                    'C' to floatArrayOf(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry),
                    'C' to floatArrayOf(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy),
                    'Z' to floatArrayOf(),
                ))
            }
        }
    }

    private fun shape(paint: Paint, shape: RecordedShape, stroke: Boolean = paint.style == PaintingStyle.Stroke, evenOdd: Boolean = false) {
        val argb = paint.color.toArgb()
        val recordedStroke = if (stroke) RecordedStroke(paint.strokeWidth * linearScale, paint.strokeCap, paint.strokeJoin,
            dashPattern.map { it * linearScale }) else null
        operations += RecordedOperation.Shape(shape, argb, recordedStroke, evenOdd)
    }

    override fun clipRect(left: Float, top: Float, right: Float, bottom: Float, clipOp: ClipOp) {
        check(clipOp == ClipOp.Intersect)
        operations += RecordedOperation.ClipBegin(box(left, top, right, bottom))
        openClips++
    }
    override fun clipPath(path: Path, clipOp: ClipOp) {
        check(clipOp == ClipOp.Intersect)
        operations += RecordedOperation.ClipBegin(outline(segments(path)))
        openClips++
    }
    override fun drawLine(p1: Offset, p2: Offset, paint: Paint) =
        shape(paint, outline(listOf('M' to floatArrayOf(p1.x, p1.y), 'L' to floatArrayOf(p2.x, p2.y))), stroke = true)
    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) = shape(paint, box(left, top, right, bottom))
    override fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, radiusX: Float, radiusY: Float, paint: Paint) {
        if (radiusX == 0f || radiusY == 0f) drawRect(left, top, right, bottom, paint)
        else drawPath(Path().apply { addRoundRect(RoundRect(Rect(left, top, right, bottom), CornerRadius(radiusX, radiusY))) }, paint)
    }
    override fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) =
        shape(paint, ellipse((left + right) / 2, (top + bottom) / 2, (right - left) / 2, (bottom - top) / 2))
    override fun drawCircle(center: Offset, radius: Float, paint: Paint) = shape(paint, ellipse(center.x, center.y, radius, radius))
    override fun drawArc(left: Float, top: Float, right: Float, bottom: Float, startAngle: Float, sweepAngle: Float, useCenter: Boolean, paint: Paint) =
        drawPath(Path().apply {
            if (useCenter) moveTo((left + right) / 2, (top + bottom) / 2)
            arcTo(Rect(left, top, right, bottom), startAngle, sweepAngle, forceMoveTo = !useCenter)
            if (useCenter) close()
        }, paint)
    override fun drawPath(path: Path, paint: Paint) {
        if (!path.isEmpty) shape(paint, outline(segments(path)), evenOdd = path.fillType == PathFillType.EvenOdd)
    }

    private fun segments(path: Path): List<Pair<Char, FloatArray>> {
        val result = mutableListOf<Pair<Char, FloatArray>>()
        val iterator = path.iterator(PathIterator.ConicEvaluation.AsQuadratics)
        val p = FloatArray(8)
        while (iterator.hasNext()) {
            when (iterator.next(p)) {
                PathSegment.Type.Move -> result += 'M' to floatArrayOf(p[0], p[1])
                PathSegment.Type.Line -> result += 'L' to floatArrayOf(p[2], p[3])
                PathSegment.Type.Quadratic -> result += 'Q' to floatArrayOf(p[2], p[3], p[4], p[5])
                PathSegment.Type.Cubic -> result += 'C' to floatArrayOf(p[2], p[3], p[4], p[5], p[6], p[7])
                PathSegment.Type.Close -> result += 'Z' to floatArrayOf()
                PathSegment.Type.Done -> break
                PathSegment.Type.Conic -> error("Conics must be converted to quadratic curves")
            }
        }
        return result
    }

    fun text(layout: TextLayoutResult, topLeft: Offset) {
        val style = layout.layoutInput.style
        val ink = if (style.color == Color.Unspecified) Color.Black else style.color
        val size = with(layout.layoutInput.density) { if (style.fontSize == TextUnit.Unspecified) 14f else style.fontSize.toPx() } * linearScale
        val bold = (style.fontWeight ?: FontWeight.Normal) >= FontWeight.Bold
        val rotation = atan2(m[1], m[0]) * 180 / PI
        val text = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount) {
            val content = text.substring(layout.getLineStart(line), layout.getLineEnd(line, visibleEnd = true))
            if (content.isEmpty()) continue
            val (x, y) = map((topLeft.x + layout.getLineLeft(line)).toDouble(), (topLeft.y + layout.getLineBaseline(line)).toDouble())
            operations += RecordedOperation.Text(x, y, content, style.fontFamily, size, bold, style.fontStyle == FontStyle.Italic,
                ink.toArgb(), round(rotation * 10000) / 10000)
        }
    }

    private fun image(image: ImageBitmap, srcOffset: IntOffset, srcSize: IntSize, dstOffset: IntOffset, dstSize: IntSize) {
        val saved = m
        concat(1.0, 0.0, 0.0, 1.0, dstOffset.x.toDouble(), dstOffset.y.toDouble())
        concat(dstSize.width.toDouble() / srcSize.width, 0.0, 0.0, dstSize.height.toDouble() / srcSize.height, 0.0, 0.0)
        val transform = m.copyOf()
        val corner = map(0.0, 0.0)
        val opposite = map(srcSize.width.toDouble(), srcSize.height.toDouble())
        m = saved
        val png = runCatching {
            val pixels = IntArray(srcSize.width * srcSize.height)
            image.readPixels(pixels, srcOffset.x, srcOffset.y, srcSize.width, srcSize.height)
            VisualPng.encode(srcSize.width, srcSize.height, pixels)
        }.getOrNull()
        operations += RecordedOperation.Image(transform, srcSize.width, srcSize.height, png, corner, opposite)
    }
    override fun drawImage(image: ImageBitmap, topLeft: Offset, paint: Paint) =
        image(image, IntOffset.Zero, IntSize(image.width, image.height), IntOffset(topLeft.x.toInt(), topLeft.y.toInt()), IntSize(image.width, image.height))
    override fun drawImageRect(image: ImageBitmap, srcOffset: IntOffset, srcSize: IntSize, dstOffset: IntOffset, dstSize: IntSize, paint: Paint) =
        image(image, srcOffset, srcSize, dstOffset, dstSize)

    override fun saveLayer(bounds: Rect, paint: Paint): Unit = error("Compositing layers are unavailable in TikZ")
    override fun drawPoints(pointMode: PointMode, points: List<Offset>, paint: Paint): Unit = error("Point meshes are unavailable in TikZ")
    override fun drawRawPoints(pointMode: PointMode, points: FloatArray, paint: Paint): Unit = error("Point meshes are unavailable in TikZ")
    override fun drawVertices(vertices: Vertices, blendMode: BlendMode, paint: Paint): Unit = error("Vertex meshes are unavailable in TikZ")
    override fun enableZ() = Unit
    override fun disableZ() = Unit
}
