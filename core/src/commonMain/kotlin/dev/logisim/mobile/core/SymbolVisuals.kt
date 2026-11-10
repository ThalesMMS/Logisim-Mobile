package dev.logisim.mobile.core

import dev.logisim.mobile.core.AnnotationVisuals.parseColor
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import kotlin.math.roundToInt

object SymbolVisuals {
    fun defaultFill(tag: String): String = if (tag in setOf("line", "polyline", "path")) "none" else "#000000"
    fun defaultStroke(tag: String): String = if (tag in setOf("line", "polyline", "path")) "#000000" else "none"

    internal fun paintColor(color: String, opacity: String?): UInt? {
        val value = parseColor(color) ?: return null
        val factor = if (opacity == null) 1.0 else opacity.replace(',', '.').toDoubleOrNull()
            ?: throw LocalizedArgumentException(message("appearance_invalid_opacity"))
        requireLocalized(factor.isFinite() && factor in 0.0..1.0) { message("appearance_invalid_opacity") }
        val alpha = ((value shr 24).toInt() * factor).roundToInt().coerceIn(0, 255)
        return (alpha.toUInt() shl 24) or (value and 0x00ffffffu)
    }

    fun symbol(
        appearance: SymbolAppearance,
        handles: Boolean = false,
        circuit: CircuitDocument? = null,
        simulation: SimulationState? = null,
    ): VisualScene {
        val ledSources = if (appearance.objects.any { it is SymbolObject.Led }) circuit?.components?.associateBy { it.id } else null
        val items = appearance.objects.mapNotNull { obj ->
            when (obj) {
                is SymbolObject.Led -> return@mapNotNull obj.visualItem(ledSources?.get(obj.sourceId), simulation)
                is SymbolObject.Opaque -> return@mapNotNull null
                is SymbolObject.Shape -> Unit
            }
            val shape = obj
            if (shape.tag == "image") return@mapNotNull shape.imageItem
            val box = SymbolAppearance.shapeBounds(shape)
            val strokeWidth = shape.attributes["stroke-width"]?.toDoubleOrNull() ?: 1.0
            val paint = VisualPaint(paintColor(shape.attributes["fill"] ?: defaultFill(shape.tag), shape.attributes["fill-opacity"]),
                paintColor(shape.attributes["stroke"] ?: defaultStroke(shape.tag), shape.attributes["stroke-opacity"]), strokeWidth)
            fun number(key: String, default: Double? = null) = SymbolAppearance.number(shape, key, default)
            val primitive = when (shape.tag) {
                "rect" -> VisualPrimitive.Rectangle(VisualBounds(number("x"), number("y"), number("x") + number("width"), number("y") + number("height")), paint,
                    number("rx", 0.0))
                "ellipse" -> VisualPrimitive.Ellipse(VisualBounds(number("cx") - number("rx"), number("cy") - number("ry"), number("cx") + number("rx"), number("cy") + number("ry")), paint)
                "line" -> VisualPrimitive.Path(listOf(VisualPathCommand.MoveTo(VisualPoint(number("x1"), number("y1"))), VisualPathCommand.LineTo(VisualPoint(number("x2"), number("y2")))), paint)
                "text" -> VisualPrimitive.Text(shape.text, VisualPoint(number("x"), number("y")),
                    VisualFont(shape.attributes["font-family"] ?: "SansSerif", number("font-size", 12.0), shape.attributes["font-weight"] == "bold", shape.attributes["font-style"] == "italic"),
                    paint.fill ?: 0xff000000u, when (shape.attributes["text-anchor"]) { "start" -> VisualTextAlign.START; "end" -> VisualTextAlign.END; else -> VisualTextAlign.CENTER },
                    verticalAlign = when (shape.attributes["dominant-baseline"]) { "top" -> VisualTextVerticalAlign.TOP; "bottom" -> VisualTextVerticalAlign.BOTTOM; "alphabetic" -> VisualTextVerticalAlign.BASELINE; else -> VisualTextVerticalAlign.CENTER })
                "polyline", "polygon" -> {
                    val points = polygonPoints(shape.attributes["points"].orEmpty())
                    VisualPrimitive.Path(listOf(VisualPathCommand.MoveTo(points.first())) + points.drop(1).map { VisualPathCommand.LineTo(it) } +
                        if (shape.tag == "polygon") listOf(VisualPathCommand.Close) else emptyList(), paint)
                }
                else -> VisualPrimitive.Path(parsePath(shape.attributes["d"].orEmpty()), paint)
            }
            VisualItem(shape.id, VisualBounds(box.left.toDouble(), box.top.toDouble(), box.right.toDouble(), box.bottom.toDouble()).expanded(strokeWidth / 2), listOf(primitive))
        }.toMutableList()
        if (handles) {
            for (port in appearance.ports) {
                val point = port.position
                val bounds = VisualBounds(point.x - 4.0, point.y - 4.0, point.x + 4.0, point.y + 4.0)
                items += VisualItem("port-${port.pinId}", bounds, listOf(VisualPrimitive.Rectangle(bounds, VisualPaint(0xffffffffu, 0xff1479d0u, 1.0))))
            }
            val point = appearance.anchor.position
            val bounds = VisualBounds(point.x - 5.0, point.y - 5.0, point.x + 5.0, point.y + 5.0)
            items += VisualItem("anchor", bounds, listOf(VisualPrimitive.Ellipse(bounds, VisualPaint(null, 0xff00a000u, 1.0))))
        }
        return VisualScene(items)
    }

    internal fun imageItem(shape: SymbolObject.Shape): VisualItem {
        val box = SymbolAppearance.shapeBounds(shape)
        val bounds = VisualBounds(box.left.toDouble(), box.top.toDouble(), box.right.toDouble(), box.bottom.toDouble())
        val source = shape.attributes["xlink:href"]?.takeIf { it.isNotBlank() } ?: shape.attributes["href"]
        val bitmap = source?.let { runCatching { EmbeddedImage.fromDataUri(it) }.getOrNull() }
        val scale = when (shape.attributes["scale"]) {
            "stretch" -> "stretch"
            "cover" -> "cover"
            else -> "fit"
        }
        val primitives = if (bitmap != null) listOf(VisualPrimitive.Image(bounds, bitmap.mimeType, bitmap.bytes, scale))
        else listOf(
            VisualPrimitive.Rectangle(bounds, VisualPaint(0xffd3d3d3u, 0xff404040u)),
            VisualPrimitive.Path(listOf(
                VisualPathCommand.MoveTo(VisualPoint(bounds.left, bounds.top)),
                VisualPathCommand.LineTo(VisualPoint(bounds.right, bounds.bottom)),
                VisualPathCommand.MoveTo(VisualPoint(bounds.right, bounds.top)),
                VisualPathCommand.LineTo(VisualPoint(bounds.left, bounds.bottom)),
            ), VisualPaint(null, 0xff404040u)),
        )
        return VisualItem(shape.id, bounds, primitives)
    }

    fun symbolInstance(instance: CircuitComponent, appearance: SymbolAppearance, scene: VisualScene = symbol(appearance)): VisualItem {
        val source = scene.items.flatMap { it.primitives }
        val directions = listOf("east", "south", "west", "north")
        val turns = (directions.indexOf(instance.facing) - directions.indexOf(appearance.anchor.facing) + 4) % 4
        val angle = turns * 90.0
        val painted = scene.items.map { it.bounds }.reduceOrNull(VisualBounds::union)
        val bounds = if (painted == null) {
            val box = appearance.relativeBounds()
            val corners = listOf(GridPoint(box.left, box.top), GridPoint(box.right, box.top), GridPoint(box.right, box.bottom), GridPoint(box.left, box.bottom)).map(instance::relativePoint)
            VisualBounds(corners.minOf { it.x }.toDouble(), corners.minOf { it.y }.toDouble(), corners.maxOf { it.x }.toDouble(), corners.maxOf { it.y }.toDouble())
        } else {
            // A quarter-turn preserves the union of axis-aligned item bounds, including strokes.
            val corners = listOf(VisualPoint(painted.left, painted.top), VisualPoint(painted.right, painted.top),
                VisualPoint(painted.right, painted.bottom), VisualPoint(painted.left, painted.bottom)).map { point ->
                var x = point.x - appearance.anchor.position.x
                var y = point.y - appearance.anchor.position.y
                repeat(turns) { val previousX = x; x = -y; y = previousX }
                VisualPoint(x + instance.position.x, y + instance.position.y)
            }
            VisualBounds(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
        }
        return VisualItem(instance.id, bounds, listOf(VisualPrimitive.Group(VisualTransform(instance.position.x.toDouble(), instance.position.y.toDouble(), angle),
            listOf(VisualPrimitive.Group(VisualTransform(-appearance.anchor.position.x.toDouble(), -appearance.anchor.position.y.toDouble()), source)))))
    }

    internal fun polygonPoints(value: String): List<VisualPoint> {
        val values = Regex("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?")
            .findAll(value).map { it.value.toDouble() }.toList()
        return values.chunked(2).map { VisualPoint(it[0], it[1]) }
    }

    internal fun parsePath(value: String): List<VisualPathCommand> {
        requireLocalized(Regex("[MLQCZ0-9+.,eE\\s-]+").matches(value)) { message("appearance_unsupported_path") }
        val tokens = Regex("[MLQCZ]|[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?").findAll(value).map { it.value }.toList()
        val result = mutableListOf<VisualPathCommand>()
        var index = 0
        var command = ""
        fun point(): VisualPoint {
            requireLocalized(index + 2 <= tokens.size) { message("appearance_incomplete_path") }
            return VisualPoint(tokens[index++].toDouble(), tokens[index++].toDouble())
        }
        while (index < tokens.size) {
            if (tokens[index] in setOf("M", "L", "Q", "C", "Z")) command = tokens[index++]
            result += when (command) {
                "M" -> VisualPathCommand.MoveTo(point()).also { command = "L" }
                "L" -> VisualPathCommand.LineTo(point())
                "Q" -> VisualPathCommand.QuadTo(point(), point())
                "C" -> VisualPathCommand.CubicTo(point(), point(), point())
                "Z" -> VisualPathCommand.Close.also { command = "" }
                else -> throw LocalizedArgumentException(message("appearance_unsupported_path"))
            }
        }
        return result
    }
}
