package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import kotlin.math.ceil

data class VisualExportOptions(
    val selection: Set<String>? = null,
    val scale: Double = 1.0,
    val margin: Double = 10.0,
    val background: UInt? = 0xffffffffu,
    val clip: VisualBounds? = null,
    val maxPixels: Long = 8_388_608,
) {
    init {
        require(scale.isFinite() && scale > 0.0)
        require(margin.isFinite() && margin >= 0.0)
        require(maxPixels in 1..67_108_864)
    }
}

data class VisualExportFrame(
    val items: List<VisualItem>,
    val circuitBounds: VisualBounds,
    val bounds: VisualBounds,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val scale: Double,
    val background: UInt?,
    val clip: VisualBounds?,
)

/** The output size of an export in pixels, before it is checked against the Int and raster limits. */
data class VisualPixelSize(val width: Double, val height: Double) {
    val pixels: Double get() = width * height
    val fitsInt: Boolean get() = width <= Int.MAX_VALUE && height <= Int.MAX_VALUE
    fun fitsRaster(maxPixels: Long = VisualExportOptions().maxPixels): Boolean = fitsInt && pixels <= maxPixels
}

object VisualExport {
    /** The image size [frame] gives drawing bounds at [scale] with [margin] circuit units around them. */
    fun pixelSize(circuitBounds: VisualBounds, scale: Double, margin: Double): VisualPixelSize {
        val bounds = circuitBounds.expanded(margin)
        return VisualPixelSize(ceil(maxOf(1.0, bounds.width * scale)), ceil(maxOf(1.0, bounds.height * scale)))
    }

    fun frame(scene: VisualScene, options: VisualExportOptions = VisualExportOptions(), raster: Boolean = false): VisualExportFrame {
        val selected = options.selection?.let { ids -> scene.items.filter { it.id in ids } } ?: scene.items
        requireLocalized(selected.isNotEmpty()) { message("visual_export_empty") }
        val circuitBounds = options.clip ?: selected.map { it.bounds }.reduce(VisualBounds::union)
        val bounds = circuitBounds.expanded(options.margin)
        val size = pixelSize(circuitBounds, options.scale, options.margin)
        requireLocalized(size.fitsInt) { message("visual_export_dimensions_large") }
        if (raster) requireLocalized(size.pixels <= options.maxPixels) { message("visual_export_pixel_limit") }
        return VisualExportFrame(selected, circuitBounds, bounds, size.width.toInt(), size.height.toInt(), options.scale, options.background, options.clip)
    }

    fun svg(scene: VisualScene, options: VisualExportOptions = VisualExportOptions()): ByteArray = svgText(frame(scene, options)).encodeToByteArray()

    fun svgText(frame: VisualExportFrame): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(frame.pixelWidth)
        append("\" height=\"").append(frame.pixelHeight).append("\" viewBox=\"")
        append(number(frame.bounds.left)).append(' ').append(number(frame.bounds.top)).append(' ')
        append(number(maxOf(1.0 / frame.scale, frame.bounds.width))).append(' ')
        append(number(maxOf(1.0 / frame.scale, frame.bounds.height))).append("\">\n")
        frame.background?.let { color ->
            append("<rect x=\"").append(number(frame.bounds.left)).append("\" y=\"").append(number(frame.bounds.top))
            append("\" width=\"").append(number(maxOf(1.0 / frame.scale, frame.bounds.width))).append("\" height=\"")
            append(number(maxOf(1.0 / frame.scale, frame.bounds.height))).append('"')
            appendColor("fill", color); append("/>\n")
        }
        frame.clip?.let {
            append("<defs><clipPath id=\"circuit-clip\"><rect x=\"").append(number(it.left)).append("\" y=\"").append(number(it.top))
            append("\" width=\"").append(number(it.width)).append("\" height=\"").append(number(it.height)).append("\"/></clipPath></defs>\n")
            append("<g clip-path=\"url(#circuit-clip)\">\n")
        }
        frame.items.forEach { item ->
            append("<g data-object-id=\"").append(escape(item.id)).append("\">\n")
            item.primitives.forEach { appendPrimitive(it) }
            append("</g>\n")
        }
        if (frame.clip != null) append("</g>\n")
        append("</svg>\n")
    }

    private fun StringBuilder.appendPrimitive(value: VisualPrimitive) {
        when (value) {
            is VisualPrimitive.Path -> {
                append("<path d=\"")
                value.commands.forEach { command ->
                    when (command) {
                        is VisualPathCommand.MoveTo -> { append('M'); appendPoint(command.point) }
                        is VisualPathCommand.LineTo -> { append('L'); appendPoint(command.point) }
                        is VisualPathCommand.QuadTo -> { append('Q'); appendPoint(command.control); append(' '); appendPoint(command.end) }
                        is VisualPathCommand.CubicTo -> { append('C'); appendPoint(command.firstControl); append(' '); appendPoint(command.secondControl); append(' '); appendPoint(command.end) }
                        VisualPathCommand.Close -> append('Z')
                    }
                    append(' ')
                }
                append('"'); appendPaint(value.paint); append("/>\n")
            }
            is VisualPrimitive.Rectangle -> {
                append("<rect x=\"").append(number(value.bounds.left)).append("\" y=\"").append(number(value.bounds.top))
                append("\" width=\"").append(number(value.bounds.width)).append("\" height=\"").append(number(value.bounds.height)).append('"')
                if (value.radius > 0.0) append(" rx=\"").append(number(value.radius)).append('"')
                appendPaint(value.paint); append("/>\n")
            }
            is VisualPrimitive.Ellipse -> {
                append("<ellipse cx=\"").append(number((value.bounds.left + value.bounds.right) / 2))
                append("\" cy=\"").append(number((value.bounds.top + value.bounds.bottom) / 2))
                append("\" rx=\"").append(number(value.bounds.width / 2)).append("\" ry=\"").append(number(value.bounds.height / 2)).append('"')
                appendPaint(value.paint); append("/>\n")
            }
            is VisualPrimitive.Text -> {
                append("<text x=\"").append(number(value.position.x)).append("\" y=\"").append(number(value.position.y))
                append("\" font-family=\"").append(escape(value.font.resolvedFamily)).append("\" font-size=\"").append(number(value.font.size))
                append("\" font-weight=\"").append(if (value.font.bold) "bold" else "normal")
                append("\" font-style=\"").append(if (value.font.italic) "italic" else "normal")
                append("\" text-anchor=\"").append(when (value.align) { VisualTextAlign.START -> "start"; VisualTextAlign.CENTER -> "middle"; VisualTextAlign.END -> "end" }).append('"')
                append(" dominant-baseline=\"").append(when (value.verticalAlign) {
                    VisualTextVerticalAlign.BASELINE -> "alphabetic"
                    VisualTextVerticalAlign.TOP -> "text-before-edge"
                    VisualTextVerticalAlign.CENTER -> "central"
                    VisualTextVerticalAlign.BOTTOM -> "text-after-edge"
                }).append('"')
                appendColor("fill", value.color); append(" xml:space=\"preserve\">")
                value.displayText.split('\n').forEachIndexed { index, line ->
                    if (index == 0) append(escape(line)) else {
                        append("<tspan x=\"").append(number(value.position.x)).append("\" dy=\"")
                        append(number(value.font.size * value.font.lineHeight)).append("\">").append(escape(line)).append("</tspan>")
                    }
                }
                append("</text>\n")
            }
            is VisualPrimitive.Image -> {
                append("<image x=\"").append(number(value.bounds.left)).append("\" y=\"").append(number(value.bounds.top))
                append("\" width=\"").append(number(value.bounds.width)).append("\" height=\"").append(number(value.bounds.height))
                append("\" preserveAspectRatio=\"").append(when (value.scaleMode) { "fit" -> "xMidYMid meet"; "cover" -> "xMidYMid slice"; else -> "none" }).append("\" href=\"data:").append(value.mimeType).append(";base64,")
                append(base64(value.encodedBytes)).append("\"/>\n")
            }
            is VisualPrimitive.Group -> {
                val transform = value.transform
                append("<g transform=\"translate(").append(number(transform.translateX)).append(' ').append(number(transform.translateY))
                append(") rotate(").append(number(transform.rotationDegrees)).append(") scale(")
                append(number(transform.scaleX)).append(' ').append(number(transform.scaleY)).append(")\">\n")
                value.children.forEach { appendPrimitive(it) }
                append("</g>\n")
            }
        }
    }

    private fun StringBuilder.appendPoint(point: VisualPoint) { append(number(point.x)).append(' ').append(number(point.y)) }
    private fun StringBuilder.appendPaint(paint: VisualPaint) {
        if (paint.fill == null) append(" fill=\"none\"") else appendColor("fill", paint.fill)
        if (paint.stroke == null) append(" stroke=\"none\"") else appendColor("stroke", paint.stroke)
        append(" stroke-width=\"").append(number(paint.strokeWidth)).append("\" stroke-linecap=\"round\" stroke-linejoin=\"round\"")
        if (paint.dash.isNotEmpty()) append(" stroke-dasharray=\"").append(paint.dash.joinToString(" ") { number(it) }).append('"')
    }
    private fun StringBuilder.appendColor(attribute: String, color: UInt) {
        append(' ').append(attribute).append("=\"#").append((color and 0xffffffu).toString(16).padStart(6, '0')).append('"')
        val alpha = (color shr 24).toInt()
        if (alpha != 255) append(' ').append(attribute).append("-opacity=\"").append(number(alpha / 255.0)).append('"')
    }
    private fun number(value: Double): String = if (value == 0.0) "0" else if (value >= Long.MIN_VALUE.toDouble() && value < Long.MAX_VALUE.toDouble() && value.toLong().toDouble() == value) value.toLong().toString() else value.toString()
    private fun escape(value: String): String = buildString {
        value.forEach { append(when (it) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&apos;"; else -> it.toString() }) }
    }
    private fun base64(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        return buildString(((bytes.size + 2) / 3) * 4) {
            var offset = 0
            while (offset < bytes.size) {
                val a = bytes[offset++].toInt() and 255
                val b = if (offset < bytes.size) bytes[offset++].toInt() and 255 else -1
                val c = if (offset < bytes.size) bytes[offset++].toInt() and 255 else -1
                append(alphabet[a ushr 2]); append(alphabet[((a and 3) shl 4) or (if (b < 0) 0 else b ushr 4)])
                append(if (b < 0) '=' else alphabet[((b and 15) shl 2) or (if (c < 0) 0 else c ushr 6)])
                append(if (c < 0) '=' else alphabet[c and 63])
            }
        }
    }
}
