package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextMeasurer
import dev.logisim.mobile.core.*

data class CircuitDrawingStyle(
    val highContrastSignals: Boolean = false,
    val signalRadix: String? = null,
    val gateShape: EditorGateShape = EditorGateShape.ANSI,
    val printerView: Boolean = false,
    val colors: Map<EditorColor, Int> = emptyMap(),
    val floatingPoints: Set<GridPoint> = emptySet(),
    /** AppPreferences.PinAppearance diameter (4, 6, 8 or 10 Evolution pixels). */
    val pinDotDiameter: Int = EditorPinAppearance.DOT_SMALL.diameter,
    /** Value.TRUECHAR ... DONTCARECHAR for values shown in components. */
    val valueCharacters: ValueCharacters = ValueCharacters.Default,
    /** Points of wire bundles with incompatible widths, which CircuitWires paints in WIDTH_ERROR_COLOR. */
    val widthErrorPoints: Set<GridPoint> = emptySet(),
    /** The dark theme is active; StdAttr.getDefaultLabelColor() then gives labels without a colour DARK_DEFAULT_LABEL_COLOR. */
    val darkTheme: Boolean = false,
) {
    /** Colour of component labels that have no labelcolor attribute. */
    internal val defaultLabelColor: Color get() = if (darkText) DarkDefaultLabelColor else DefaultLabelColor

    /** Whether theme-dependent text colors use their dark variants. */
    internal val darkText: Boolean get() = darkTheme && !printerView

    /** Body fill under component outlines: white on the light canvas, the canvas itself in the dark theme. */
    internal val paper: Color get() = if (darkText) colors.color(EditorColor.CANVAS, DarkCanvasPaper) else Color.White
    /** The connection dot radius, scaled from the classic radius that the small dot keeps. */
    internal val terminalRadius: Double get() = classicTerminalRadius(pinDotDiameter)

    internal val palette: CircuitSignalPalette get() = if (highContrastSignals) CircuitSignalPalette.HighContrast else CircuitSignalPalette.Classic
    internal fun color(role: EditorColor, fallback: Color): Color = if (printerView) fallback else colors.color(role, fallback)

    /** Placement previews use COMPONENT_GHOST_COLOR, whose light default is DEFAULT_COMPONENT_GHOST_COLOR. */
    internal fun forPreview(): CircuitDrawingStyle {
        val ghost = colors[EditorColor.COMPONENT_GHOST] ?: (EditorColor.COMPONENT_GHOST.defaultColor(false).toArgb() and 0xFFFFFF)
        return copy(colors = colors + (EditorColor.COMPONENT to ghost) + (EditorColor.COMPONENT_SECONDARY to ghost))
    }
}

/** Optional editor decoration, excluded when the same world drawing is exported. */
data class CircuitDrawingOverlay(
    val selectedComponents: Set<String> = emptySet(),
    val selectedWires: Set<WireSegment> = emptySet(),
    val previewComponents: Set<String> = emptySet(),
    val previewWires: Set<WireSegment> = emptySet(),
    val previewDelta: GridPoint = GridPoint(0, 0),
    val pendingWire: GridPoint? = null,
    val memoryFirstAddresses: Map<String, Int> = emptyMap(),
    /** PokeTool's highlighted wire set, drawn wider with the dashed STROKE_COLOR. */
    val highlightedWires: Set<WireSegment> = emptySet(),
    /** CanvasPainter.drawWidthIncompatibilityData: circles and width captions at each conflicting point. */
    val widthIncompatibilities: List<WidthIncompatibility> = emptyList(),
)

/** Canvas and raster/print export call this identical renderer in circuit coordinates. */
fun DrawScope.drawCircuitDrawing(
    document: CircuitDocument,
    simulation: SimulationState,
    measurer: TextMeasurer,
    imageDecoder: VisualImageDecoder,
    registry: ComponentRegistry = ComponentCatalog.registry,
    childCircuit: (String) -> CircuitDocument? = { null },
    style: CircuitDrawingStyle = CircuitDrawingStyle(),
    overlay: CircuitDrawingOverlay = CircuitDrawingOverlay(),
    junctions: List<GridPoint> = circuitDrawingJunctions(document.wires),
    wires: List<WireSegment> = document.wires,
    projectStates: Map<String, SimulationState> = simulation.circuitStates,
) {
    for (wire in wires) {
        val vector = simulation.portVectors[wire.from] ?: simulation.portVectors[wire.to]
        val scalar = simulation.portValues[wire.from] ?: simulation.portValues[wire.to]
        val delta = if (wire in overlay.previewWires) overlay.previewDelta else GridPoint(0, 0)
        val from = wire.from.drawingOffset() + delta.drawingOffset()
        val to = wire.to.drawingOffset() + delta.drawingOffset()
        if (wire in overlay.selectedWires) drawLine(DrawingAccent.copy(alpha = 0.35f), from, to, 2 * SelectedWireRadius, cap = StrokeCap.Round)
        val bus = (vector?.width ?: 1) > 1
        if (wire in overlay.highlightedWires) {
            // CircuitWires.draw: the highlighted wire is wider, then dashed with Value.strokeColor.
            drawLine(style.wireColor(vector, scalar, wire.from), from, to, if (bus) HighlightedBusWidth else HighlightedWireWidth,
                cap = StrokeCap.Round)
            drawLine(style.color(EditorColor.STROKE, EditorColor.STROKE.defaultColor(false)), from, to, HighlightedStrokeWidth,
                cap = StrokeCap.Butt, pathEffect = HighlightedStroke)
            continue
        }
        val pattern = style.wirePattern(vector, scalar).takeUnless { wire.from in style.widthErrorPoints }
        withTikzDash(pattern?.intervals.orEmpty()) {
            drawLine(style.wireColor(vector, scalar, wire.from), from, to,
                if (bus) 4f else 2f, cap = StrokeCap.Round, pathEffect = pattern?.effect)
        }
    }
    for (point in junctions) {
        val vector = simulation.portVectors[point]
        val scalar = simulation.portValues[point]
        drawCircle(style.wireColor(vector, scalar, point), if ((vector?.width ?: 1) > 1) BusJunctionRadius else 3.3f, point.drawingOffset())
    }
    val ends = if (style.printerView) CircuitEnds(document.components, wires, registry) else null
    for (component in document.components) {
        if (component.id in overlay.previewComponents) {
            val dx = ((component.position.x.toLong() + overlay.previewDelta.x).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()) - component.position.x).toFloat()
            val dy = ((component.position.y.toLong() + overlay.previewDelta.y).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()) - component.position.y).toFloat()
            withTransform({ translate(dx, dy) }) {
                drawClassicComponent(component, simulation, component.id in overlay.selectedComponents, measurer, registry,
                    style.forPreview(), component.circuitName?.let(childCircuit), imageDecoder, overlay.memoryFirstAddresses[component.id], projectStates, ends)
            }
        } else drawClassicComponent(component, simulation, component.id in overlay.selectedComponents, measurer, registry,
            style, component.circuitName?.let(childCircuit), imageDecoder, overlay.memoryFirstAddresses[component.id], projectStates, ends)
    }
    // CanvasPainter.paintContents draws the width data after the circuit, so captions stay above pins.
    if (overlay.widthIncompatibilities.isNotEmpty()) drawWidthIncompatibilities(overlay.widthIncompatibilities, style, measurer)
    overlay.pendingWire?.let {
        drawCircle(DrawingAccent.copy(alpha = 0.16f), 13f, it.drawingOffset())
        drawCircle(DrawingAccent, 9f, it.drawingOffset(), style = Stroke(2f))
    }
}

/** Include measured labels, custom symbol text, image frames, terminal dots, and wire strokes. */
fun circuitDrawingBounds(
    document: CircuitDocument,
    measurer: TextMeasurer,
    imageDecoder: VisualImageDecoder,
    registry: ComponentRegistry = ComponentCatalog.registry,
    childCircuit: (String) -> CircuitDocument? = { null },
    gateShape: EditorGateShape = EditorGateShape.ANSI,
): VisualBounds? {
    var result: VisualBounds? = null
    fun include(bounds: VisualBounds) { result = result?.union(bounds) ?: bounds }
    for (component in document.components) {
        val annotation = AnnotationLibrary.isAnnotation(component)
        val appearance = component.circuitName?.let(childCircuit)?.let(SymbolAppearance::rendered)
        val bounds = when {
            component.kind == AnnotationLibrary.Image -> AnnotationLibrary.bounds(component).let { box ->
                val frame = VisualBounds(box.left.toDouble(), box.top.toDouble(), box.right.toDouble(), box.bottom.toDouble())
                if (component.attributes["image"].isNullOrEmpty()) frame.expanded(0.5) else frame
            }
            annotation -> measurer.annotationDrawingItem(component, imageDecoder, decodeImages = false).bounds
            appearance != null -> measurer.symbolVisualItem(component, appearance).bounds
            else -> measurer.classicComponentVisualBounds(component, registry, gateShape)
        }
        include(bounds)
        if (!annotation) {
            classicComponentLabel(component, classicComponentBounds(component, registry), gateShape = gateShape)?.let { include(measurer.visualTextBounds(it)) }
            for (port in component.layout(registry).ports) include(VisualBounds(port.position.x - 5.0, port.position.y - 5.0, port.position.x + 5.0, port.position.y + 5.0))
        }
    }
    for (wire in document.wires) include(VisualBounds(minOf(wire.from.x, wire.to.x).toDouble(), minOf(wire.from.y, wire.to.y).toDouble(), maxOf(wire.from.x, wire.to.x).toDouble(), maxOf(wire.from.y, wire.to.y).toDouble()).expanded(5.0))
    return result
}

internal fun TextMeasurer.annotationDrawingItem(component: CircuitComponent, imageDecoder: VisualImageDecoder, decodeImages: Boolean = true): VisualItem {
    fun create(): VisualItem = try {
        annotationVisualItem(component).also { item -> if (decodeImages) item.primitives.filterIsInstance<VisualPrimitive.Image>().forEach { imageDecoder.decode(it) } }
    } catch (failure: Exception) {
        if (component.kind != AnnotationLibrary.Image) throw failure
        val box = AnnotationLibrary.bounds(component)
        val bounds = VisualBounds(box.left.toDouble(), box.top.toDouble(), box.right.toDouble(), box.bottom.toDouble())
        VisualItem(component.id, bounds.expanded(1.0), listOf(
            VisualPrimitive.Rectangle(bounds, VisualPaint(0xfff5f5f5u, 0xffc62828u, 1.0)),
            VisualPrimitive.Path(listOf(VisualPathCommand.MoveTo(VisualPoint(bounds.left, bounds.top)), VisualPathCommand.LineTo(VisualPoint(bounds.right, bounds.bottom)),
                VisualPathCommand.MoveTo(VisualPoint(bounds.right, bounds.top)), VisualPathCommand.LineTo(VisualPoint(bounds.left, bounds.bottom))), VisualPaint(null, 0xffc62828u, 1.0)),
        ))
    }
    return if (decodeImages && component.kind == AnnotationLibrary.Image && imageDecoder is AnnotationImageCache)
        imageDecoder.drawingItem(component, ::create) else create()
}

fun circuitDrawingWireId(wire: WireSegment): String = "wire:${wire.from.x},${wire.from.y}:${wire.to.x},${wire.to.y}"

fun circuitDrawingSelection(document: CircuitDocument, ids: Set<String>?): CircuitDocument = if (ids == null) document else document.copy(
    components = document.components.filter { it.id in ids }, wires = document.wires.filter { circuitDrawingWireId(it) in ids },
)

/** Endpoint junctions in O(n log n), including an endpoint touching another segment's interior. */
fun circuitDrawingJunctions(wires: List<WireSegment>): List<GridPoint> {
    data class Intervals(val starts: List<Int>, val ends: List<Int>) {
        fun count(point: Int): Int {
            fun rank(values: List<Int>, inclusive: Boolean): Int {
                var low = 0; var high = values.size
                while (low < high) { val middle = (low + high) ushr 1; if (values[middle] < point || inclusive && values[middle] == point) low = middle + 1 else high = middle }
                return low
            }
            return rank(starts, true) - rank(ends, false)
        }
    }
    fun index(horizontal: Boolean): Map<Int, Intervals> = wires.filter { (it.from.y == it.to.y) == horizontal }.groupBy { if (horizontal) it.from.y else it.from.x }.mapValues { (_, segments) ->
        val pairs = segments.map { if (horizontal) minOf(it.from.x, it.to.x) to maxOf(it.from.x, it.to.x) else minOf(it.from.y, it.to.y) to maxOf(it.from.y, it.to.y) }
        Intervals(pairs.map { it.first }.sorted(), pairs.map { it.second }.sorted())
    }
    val horizontal = index(true); val vertical = index(false)
    val endpoints = linkedMapOf<GridPoint, Int>()
    for (wire in wires) { endpoints[wire.from] = (endpoints[wire.from] ?: 0) + 1; endpoints[wire.to] = (endpoints[wire.to] ?: 0) + 1 }
    return endpoints.filter { (point, endpointCount) -> 2 * ((horizontal[point.y]?.count(point.x) ?: 0) + (vertical[point.x]?.count(point.y) ?: 0)) - endpointCount >= 3 }.keys.toList()
}

internal fun circuitWireScene(document: CircuitDocument, simulation: SimulationState, style: CircuitDrawingStyle): List<VisualItem> = buildList {
    for (wire in document.wires) {
        val vector = simulation.portVectors[wire.from] ?: simulation.portVectors[wire.to]
        val scalar = simulation.portValues[wire.from] ?: simulation.portValues[wire.to]
        val color = style.wireColor(vector, scalar, wire.from)
        val dash = style.wirePattern(vector, scalar)?.intervals.orEmpty()
        val width = if ((vector?.width ?: 1) > 1) 4.0 else 2.0
        val bounds = VisualBounds(minOf(wire.from.x, wire.to.x).toDouble(), minOf(wire.from.y, wire.to.y).toDouble(), maxOf(wire.from.x, wire.to.x).toDouble(), maxOf(wire.from.y, wire.to.y).toDouble()).expanded(width / 2)
        val path = listOf(
            VisualPathCommand.MoveTo(VisualPoint(wire.from.x.toDouble(), wire.from.y.toDouble())),
            VisualPathCommand.LineTo(VisualPoint(wire.to.x.toDouble(), wire.to.y.toDouble())),
        )
        val paint = VisualPaint(null, color.toArgb().toUInt(), width, dash)
        add(VisualItem(circuitDrawingWireId(wire), bounds, listOf(VisualPrimitive.Path(path, paint))))
    }
    for (point in circuitDrawingJunctions(document.wires)) {
        val vector = simulation.portVectors[point]
        val color = style.wireColor(vector, simulation.portValues[point], point)
        val radius = if ((vector?.width ?: 1) > 1) 5.0 else 3.3
        val bounds = VisualBounds(point.x - radius, point.y - radius, point.x + radius, point.y + radius)
        add(VisualItem("junction:${point.x},${point.y}", bounds, listOf(VisualPrimitive.Ellipse(bounds, VisualPaint(color.toArgb().toUInt(), stroke = null)))))
    }
}

private enum class WirePattern(length: Double) {
    UNKNOWN(2.0), ERROR(8.0);

    val intervals = listOf(length, 7.0)
    val effect by lazy { PathEffect.dashPathEffect(intervals.map(Double::toFloat).toFloatArray()) }
}

private fun CircuitDrawingStyle.wireColor(vector: LogicVector?, scalar: LogicValue?, point: GridPoint): Color = when {
    // CircuitWires checks the bundle width before the state or the printer view.
    point in widthErrorPoints -> color(EditorColor.WIDTH_ERROR, EditorColor.WIDTH_ERROR.defaultColor(false))
    printerView -> DefaultComponentInk
    else -> colors.signalColor(vector, scalar, point in floatingPoints, palette)
}

/** CanvasPainter.drawWidthIncompatibilityData, in circuit coordinates. */
private fun DrawScope.drawWidthIncompatibilities(
    exceptions: List<WidthIncompatibility>,
    style: CircuitDrawingStyle,
    measurer: TextMeasurer,
) = drawVisualScene(VisualScene(widthIncompatibilityScene(exceptions, style)), measurer)

/** The circles and outlined width captions of each conflicting point, as one visual item per point. */
internal fun widthIncompatibilityScene(exceptions: List<WidthIncompatibility>, style: CircuitDrawingStyle): List<VisualItem> = buildList {
    fun argb(role: EditorColor) = style.color(role, role.defaultColor(false)).toArgb().toUInt()
    val error = argb(EditorColor.WIDTH_ERROR)
    val highlight = argb(EditorColor.WIDTH_ERROR_HIGHLIGHT)
    val caption = argb(EditorColor.WIDTH_ERROR_CAPTION)
    val background = argb(EditorColor.WIDTH_ERROR_BACKGROUND)
    for (exception in exceptions) {
        val common = exception.commonWidth
        exception.points.forEachIndexed { index, point ->
            if (!exception.isFirstAt(index)) return@forEachIndexed
            val x = point.position.x.toDouble()
            val y = point.position.y.toDouble()
            val odd = common != null && point.width != common
            val primitives = mutableListOf<VisualPrimitive>()
            if (odd) primitives += VisualPrimitive.Ellipse(VisualBounds(x - 5, y - 5, x + 5, y + 5), VisualPaint(null, highlight, 2.0))
            primitives += VisualPrimitive.Ellipse(VisualBounds(x - 4, y - 4, x + 4, y + 4), VisualPaint(null, error, 2.0))
            val text = exception.caption(index)
            val origin = Offset(x.toFloat() + 4f, y.toFloat() + 1f)
            // GraphicsUtil.outlineText: a 3-pixel outline in the background color under the caption.
            for ((dx, dy) in WidthCaptionOutline) {
                primitives += classicLabel(text, origin + Offset(dx, dy), Color((if (odd) highlight else background).toInt()), fontSize = 12f)
            }
            primitives += classicLabel(text, origin, Color(caption.toInt()), fontSize = 12f)
            add(VisualItem("width-error:${point.position.x},${point.position.y}", VisualBounds(x - 6, y - 6, x + 6 + 8.0 * text.length, y + 18),
                primitives))
        }
    }
}

private val WidthCaptionOutline = listOf(-1.5f to 0f, 1.5f to 0f, 0f to -1.5f, 0f to 1.5f,
    -1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)
private const val HighlightedWireWidth = 4f
private const val HighlightedBusWidth = 5f
private const val HighlightedStrokeWidth = 3f
// Lazy: on iOS a PathEffect needs the current graphics registry, which file initialization outside a composition lacks.
private val HighlightedStroke by lazy { PathEffect.dashPathEffect(floatArrayOf(7f, 7f)) }

private fun CircuitDrawingStyle.wirePattern(vector: LogicVector?, scalar: LogicValue?): WirePattern? {
    if (printerView || !highContrastSignals) return null
    if (vector != null) return when {
        vector.errorMask != 0uL -> WirePattern.ERROR
        vector.unknownMask != 0uL -> WirePattern.UNKNOWN
        else -> null
    }
    return when (scalar) {
        LogicValue.ERROR -> WirePattern.ERROR
        LogicValue.UNKNOWN, null -> WirePattern.UNKNOWN
        else -> null
    }
}

private val DrawingAccent = Color(0xff1673c8)
internal const val SelectedWireRadius = 4f
internal const val BusJunctionRadius = 5f
private fun GridPoint.drawingOffset() = Offset(x.toFloat(), y.toFloat())

/** Evolution's StdAttr.DEFAULT_LABEL_COLOR (Color.BLUE) and DARK_DEFAULT_LABEL_COLOR (0x6CB6FF). */
internal val DefaultLabelColor = Color(0xFF0000FF)
internal val DarkDefaultLabelColor = Color(0xFF6CB6FF)
private val DarkCanvasPaper = Color(0xFF2B2B2B)

/**
 * Color of a label whose `labelcolor` attribute is [labelColor]. As in Evolution's InstanceTextField,
 * a component without the attribute takes [defaultColor]. On the dark canvas the attribute's light
 * defaults (#000000 here, Color.BLUE in Evolution) take it too, so that I/O labels stay readable;
 * any other explicit color is kept.
 */
internal fun themedLabelColor(labelColor: String?, defaultColor: Color = DefaultLabelColor): Color {
    val explicit = labelColor?.let(::parseLabelColor) ?: return defaultColor
    val lightDefault = explicit.toArgb() and 0xFFFFFF in LightLabelDefaults
    return if (defaultColor == DarkDefaultLabelColor && lightDefault) defaultColor else explicit
}

private val LightLabelDefaults = setOf(0x000000, 0x0000FF)

private fun parseLabelColor(value: String): Color? {
    val hex = value.removePrefix("#")
    if (hex.length < 6) return null
    val parsed = hex.take(6).toLongOrNull(16) ?: return null
    return Color((0xFF000000L or parsed).toInt())
}

/** ColorUtil.getLuminanceInvertedColor: inverts Rec. 709 luma, keeping chroma and alpha up to RGB clipping. */
internal fun luminanceInvertedColor(argb: UInt): UInt {
    val red = (argb shr 16 and 0xFFu).toInt()
    val green = (argb shr 8 and 0xFFu).toInt()
    val blue = (argb and 0xFFu).toInt()
    val luma = 0.2126 * red + 0.7152 * green + 0.0722 * blue
    val adjustment = 255.0 - 2.0 * luma
    fun channel(value: Int): UInt = kotlin.math.round(value + adjustment).toInt().coerceIn(0, 255).toUInt()
    return (argb and 0xFF000000u) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
}

/** Text.paintInstance: in the dark theme a Text annotation shows the luma-inverted stored color. */
internal fun VisualItem.withThemeTextColors(): VisualItem = copy(primitives = primitives.map { primitive ->
    if (primitive is VisualPrimitive.Text) primitive.copy(color = luminanceInvertedColor(primitive.color)) else primitive
})
