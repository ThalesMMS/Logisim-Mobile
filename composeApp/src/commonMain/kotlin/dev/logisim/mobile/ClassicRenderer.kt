package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.AnnotationFont
import dev.logisim.mobile.core.AnnotationLibrary
import dev.logisim.mobile.core.VisualFont
import dev.logisim.mobile.core.VisualPoint
import dev.logisim.mobile.core.VisualPrimitive
import dev.logisim.mobile.core.VisualScene
import dev.logisim.mobile.core.VisualTextAlign
import dev.logisim.mobile.core.VisualTextVerticalAlign
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.GrayCodeExtension
import dev.logisim.mobile.core.GatesLibrary
import dev.logisim.mobile.core.FloatingPointNumbers
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.ValueCharacters
import dev.logisim.mobile.core.display
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.IoKinds
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.MemoryComponentState
import dev.logisim.mobile.core.MemoryComponents
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.bounds
import dev.logisim.mobile.core.layout
import dev.logisim.mobile.core.inputPorts
import dev.logisim.mobile.core.outputPorts
import dev.logisim.mobile.core.relativePoint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private val Ink = DefaultComponentInk
private val Selection = Color(0xFF1479D0)

internal fun classicComponentBounds(component: CircuitComponent, registry: ComponentRegistry = ComponentCatalog.registry): Rect = component.bounds(registry).let {
    Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
}

internal fun DrawScope.drawClassicComponent(
    component: CircuitComponent,
    simulation: SimulationState,
    selected: Boolean,
    measurer: TextMeasurer,
    registry: ComponentRegistry = ComponentCatalog.registry,
    style: CircuitDrawingStyle,
    childCircuit: CircuitDocument? = null,
    imageDecoder: VisualImageDecoder? = null,
    memoryFirstAddress: Int? = null,
    projectStates: Map<String, SimulationState> = simulation.circuitStates,
    /** The circuit's wire and component ends, which the printer view needs to omit unconnected gate input lines. */
    ends: CircuitEnds? = null,
) {
    val ink = style.color(EditorColor.COMPONENT, Ink)
    val secondary = style.colors[EditorColor.COMPONENT_SECONDARY].takeUnless { style.printerView }
    val paper = style.paper
    val darkText = style.darkText
    val printerView = style.printerView
    val layout = component.layout(registry)
    val bounds = layout.bounds.let { Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
    if (selected) {
        val selection = bounds.inflate(7f)
        drawRect(Selection.copy(alpha = 0.09f), selection.topLeft, selection.size)
        drawRect(Selection, selection.topLeft, selection.size, style = Stroke(1.25f))
        for (corner in listOf(selection.topLeft, selection.topRight, selection.bottomLeft, selection.bottomRight)) {
            drawRect(Color.White, corner - Offset(2f, 2f), Size(4f, 4f))
            drawRect(Selection, corner - Offset(2f, 2f), Size(4f, 4f), style = Stroke(1f))
        }
    }
    if (AnnotationLibrary.isAnnotation(component)) {
        val decoder = imageDecoder ?: AnnotationImageCache()
        val item = measurer.annotationDrawingItem(component, decoder)
        drawVisualScene(VisualScene(listOf(if (darkText && component.kind == AnnotationLibrary.Text) item.withThemeTextColors() else item)), measurer, decoder)
        return
    }
    val customSymbolRendered = childCircuit != null && drawSymbolComponent(component, childCircuit, measurer,
        simulation.symbolChildState(component, projectStates).takeUnless { printerView }, imageDecoder,
        outline = style.colors[EditorColor.COMPONENT].takeUnless { printerView })
    val kind = component.kind.name
    val showValue = !printerView || kind == "CONSTANT"
    val componentVector = simulation.vectorValues[component.id]
    val vector = componentVector.takeIf { showValue }
    val signalWidth = if (kind == "PROBE") componentVector?.width ?: simulation.portVectors[component.position]?.width ?: component.width else component.width
    val radix = component.attributes["radix"] ?: style.signalRadix
    val shown = when {
        showValue -> vector?.let { formattedSignal(it, radix, style.valueCharacters) }
            ?: simulation.componentValues[component.id]?.display(style.valueCharacters) ?: style.valueCharacters.unknownChar.toString()
        kind in listOf("INPUT", "OUTPUT", "PROBE") -> "x$signalWidth"
        else -> ""
    }
    val signal = style.classicSignalColor(vector, simulation.componentValues[component.id],
        layout.inputs.firstOrNull()?.position?.let { it in style.floatingPoints } == true)
    val componentState = simulation.componentStates[component.id].takeUnless { printerView }
    if (kind.startsWith("TTL_")) {
        drawTtlPackage(component, measurer, style.color(EditorColor.COMPONENT, Color.Black))
        drawComponentLabel(component, bounds, measurer, style)
        return
    }
    val preferredGate = gateVisualItem(component, style.gateShape, registry, ink.toArgb().toUInt(), paper.toArgb().toUInt())
    if (preferredGate != null) drawVisualScene(VisualScene(listOf(preferredGate)), measurer)
    val shiftPokeTargets = component.shiftPokeTargets(registry)
    if (shiftPokeTargets.isNotEmpty()) {
        outlineBox(bounds, ink, paper)
        if (!printerView) {
            val outputs = simulation.componentOutputs[component.id]
            for (target in shiftPokeTargets) {
                val value = outputs?.get(target.portName)
                val digit = value?.toULongOrNull()?.toString(16) ?: value?.display(style.valueCharacters)
                    ?: style.valueCharacters.unknownChar.toString()
                drawLabel(digit, target.bounds.center, measurer, ink, fontSize = 9f, centered = true, mono = true)
            }
        }
    }
    val rendered = shiftPokeTargets.isNotEmpty() || customSymbolRendered || preferredGate != null || drawIoComponent(component, componentState, if (printerView) 0 else simulation.ticks, measurer, printerView, ink) ||
        drawExtraIoComponent(component, componentState, measurer, printerView, ink) ||
        drawExternalIoComponent(component, componentState, measurer, ink) ||
        drawSocComponent(component, componentState, measurer, printerView, style.color(EditorColor.COMPONENT, Color.Black))
    if (!rendered) when (kind) {
        "MULTIPLEXER", "DEMULTIPLEXER", "DECODER", "PRIORITY_ENCODER", "BIT_SELECTOR" ->
            drawPlexer(component, bounds, measurer, ink, secondary)
        "ROM", "RAM" -> drawMemory(component, bounds, simulation, measurer, registry, printerView, memoryFirstAddress, ink, secondary, paper)
        "INPUT", "OUTPUT", "CONSTANT", "PROBE" -> {
            if (kind == "OUTPUT" && component.width == 1) {
                drawOval(paper, bounds.topLeft, bounds.size)
                drawOval(ink, bounds.topLeft, bounds.size, style = Stroke(1.5f))
            } else outlineBox(bounds, ink, paper)
            val digits = shown
            val rows = if (radix == null || radix == "2") digits.chunked(8) else listOf(digits)
            rows.forEachIndexed { index, text ->
                drawLabel(text, Offset(bounds.center.x, bounds.top + bounds.height * (index + 0.5f) / rows.size), measurer, signal,
                    fontSize = if (component.width == 1) 12f else 10f, centered = true, mono = true)
            }
        }
        "SUBCIRCUIT" -> {
            outlineBox(bounds, ink, paper)
            if (component.appearanceTexts.isNotEmpty()) {
                for (text in component.appearanceTexts) drawLabel(text.text, component.relativePoint(text.position).asOffset(), measurer,
                    ink, fontSize = text.fontSize.toFloat(), centered = true)
            } else if (component.explicitBounds == null) {
                val title = (component.circuitName ?: component.label).replace(Regex("_[0-9a-f]{8}$"), "").replace('_', ' ')
                drawLabel(title, bounds.center, measurer, ink, fontSize = 10f, centered = true)
            }
        }
        "HEX_DISPLAY", "SEVEN_SEGMENT_DISPLAY" -> {
            outlineBox(bounds, ink, Color(0xFFFCFCFC))
            val value = vector?.toULongOrNull()?.toInt()
            drawSevenSegment(bounds, value, kind == "SEVEN_SEGMENT_DISPLAY", secondary)
        }
        "SPLITTER" -> drawSplitter(component, registry, ink)
        "TUNNEL" -> drawTunnel(component, measurer, registry, ink, paper)
        "LED" -> {
            drawCircle(if (printerView) Color.White else if (vector?.toULongOrNull() == 1uL) Color.Red else Color(0xFF454545), min(bounds.width, bounds.height) / 2f, bounds.center)
            drawCircle(ink, min(bounds.width, bounds.height) / 2f, bounds.center, style = Stroke(1.2f))
        }
        else -> {
            val position = component.position.asOffset()
            val facing = if (registry.definition(component.kind)?.supportsRotation == true) component.facing else "east"
            val angle = when (facing) { "south" -> 90f; "west" -> 180f; "north" -> 270f; else -> 0f }
            val canonical = component.copy(position = GridPoint(0, 0), attributes = component.attributes + ("facing" to "east"))
            withTransform({ translate(position.x, position.y); rotate(angle, Offset.Zero) }) {
                drawCanonical(canonical, shown, signal, measurer, registry, ink, secondary, paper)
            }
        }
    }
    // ControlledBuffer.paintInstance and PainterShaped.paintInputLines: 3-wide terminal strokes in the value colour.
    val terminalLines = listOfNotNull(controlledBufferControlLine(component, registry)) +
        if (preferredGate == null && style.gateShape == EditorGateShape.ANSI) shapedGateInputLines(component, registry, ends.takeIf { style.printerView }) else emptyList()
    for (line in terminalLines) {
        drawLine(classicPortColor(simulation, line.port, style), line.start.asOffset(), line.end.asOffset(), 3f, cap = StrokeCap.Square)
    }
    if (kind != "TUNNEL") drawComponentLabel(component, bounds, measurer, style)
    if (selected || (kind !in listOf("TEXT", "SPLITTER") && !component.omitsPrinterViewPorts(style.printerView, style.gateShape))) {
        // Evolution draws no marker on the control terminal of a controlled buffer (only ports 0 and 1).
        val control = component.controlTerminal(registry).takeUnless { selected }
        for (port in (layout.inputs.map { it.position } + layout.outputs.map { it.position }).distinct()) {
            if (port == control) continue
            val radius = style.terminalRadius.toFloat()
            drawCircle(classicPortColor(simulation, port, style), if (selected) maxOf(2.7f, radius + 1.1f) else radius, port.asOffset())
        }
    }
    if (preferredGate == null && component.kind !in ComponentKind.entries && component.kind != ComponentKind.SUBCIRCUIT && component.kind != IoKinds.Tty &&
        kind.substringBefore('@') != GrayCodeExtension.incrementerKind.name) {
        for (port in layout.ports) {
            if (shiftPokeTargets.isNotEmpty() && port.name.startsWith("stage")) continue
            val label = port.label.ifBlank { port.name }
            val point = port.position.asOffset()
            when {
                point.x <= bounds.left + 2f -> drawLabel(label, Offset(bounds.left + 5f, point.y - 4f), measurer, ink, fontSize = 8f)
                point.x >= bounds.right - 2f -> drawLabel(label, Offset(bounds.right - 5f, point.y), measurer, ink, fontSize = 8f, rightAligned = true)
                point.y <= bounds.top + 2f -> drawLabel(label, Offset(point.x, bounds.top + 8f), measurer, ink, fontSize = 8f, centered = true)
                else -> drawLabel(label, Offset(point.x, bounds.bottom - 8f), measurer, ink, fontSize = 8f, centered = true)
            }
        }
    }
}

/**
 * AbstractGate.paintInstance draws its ports in the printer view only for the rectangular shape, and
 * ControlledBuffer.paintInstance skips ports 0 and 1 there (port 2 never gets a marker).
 */
internal fun CircuitComponent.omitsPrinterViewPorts(printerView: Boolean, gateShape: EditorGateShape): Boolean =
    printerView && (isControlledBuffer() || (kind in AbstractGateKinds && gateShape != EditorGateShape.IEC))

/** ComponentDrawContext.drawPinMarker: fillOval(x - offset, y - offset, d, d) with d = 4, 6, 8 or 10 and offset = d / 2. */
internal fun classicTerminalRadius(diameter: Int): Double = diameter / 2.0

internal val ClassicTerminalRadius = classicTerminalRadius(EditorPinAppearance.DOT_SMALL.diameter)

internal fun classicPortColor(simulation: SimulationState, port: GridPoint, highContrastSignals: Boolean, printerView: Boolean = false): Color =
    classicPortColor(simulation, port, CircuitDrawingStyle(highContrastSignals = highContrastSignals, printerView = printerView))

/** ComponentDrawContext.drawPin: the marker takes Value.getColor() of its point, the colours wires use. */
private fun classicPortColor(simulation: SimulationState, port: GridPoint, style: CircuitDrawingStyle): Color =
    if (style.printerView) Ink
    else style.colors.signalColor(simulation.portVectors[port], simulation.portValues[port], port in style.floatingPoints, style.palette)

private fun CircuitDrawingStyle.classicSignalColor(vector: LogicVector?, scalar: LogicValue?, floating: Boolean): Color {
    if (printerView) return Ink
    val defaults = if (highContrastSignals) HighContrastSymbolSignals else ClassicSymbolSignals
    return colors.signalColor(vector, scalar, floating, defaults)
}

// Values shown in components use TRUE/UNKNOWN/ERROR_COLOR; digits of 0 and of buses keep the component ink.
private val ClassicSymbolSignals = CircuitSignalPalette.Classic.copy(low = Ink, bus = Ink)
private val HighContrastSymbolSignals = ClassicSymbolSignals.copy(high = CircuitSignalPalette.HighContrast.high)

private fun DrawScope.drawCanonical(
    component: CircuitComponent, shown: String, signal: Color, measurer: TextMeasurer,
    registry: ComponentRegistry, ink: Color, secondary: Int?, paper: Color,
) {
    val bounds = classicComponentBounds(component, registry)
    val kind = component.kind.name
    when (kind) {
        "INPUT", "OUTPUT", "CONSTANT", "PROBE" -> {
            outlineBox(bounds, ink, paper)
            val digits = if (component.width <= 8) shown else shown.take(16)
            drawLabel(digits, bounds.center, measurer, signal, fontSize = if (component.width == 1) 12f else 10f, centered = true, mono = true)
        }
        "CLOCK" -> {
            outlineBox(bounds, ink, paper)
            val wave = Path().apply {
                moveTo(bounds.left + 3f, bounds.bottom - 5f)
                lineTo(bounds.left + 7f, bounds.bottom - 5f)
                lineTo(bounds.left + 7f, bounds.top + 5f)
                lineTo(bounds.right - 6f, bounds.top + 5f)
                lineTo(bounds.right - 6f, bounds.bottom - 5f)
                lineTo(bounds.right - 2f, bounds.bottom - 5f)
            }
            drawPath(wave, signal, style = Stroke(1.7f))
        }
        "BUTTON" -> {
            outlineBox(bounds, ink, Color(0xFFF0F0F0))
            drawRect(Color.White, bounds.topLeft + Offset(3f, 3f), Size((bounds.width - 6f).coerceAtLeast(0f), (bounds.height - 6f).coerceAtLeast(0f)))
            drawRect(ink, bounds.topLeft + Offset(3f, 3f), Size((bounds.width - 6f).coerceAtLeast(0f), (bounds.height - 6f).coerceAtLeast(0f)), style = Stroke(1f))
        }
        "T_FLIP_FLOP", "JK_FLIP_FLOP", "SR_FLIP_FLOP" -> {
            outlineBox(bounds, ink, paper)
            drawLabel(shown, bounds.center, measurer, signal, fontSize = 9f, centered = true, mono = true)
        }
        "D_FLIP_FLOP", "REGISTER", "COUNTER" -> {
            outlineBox(bounds, ink, paper)
            val isFlipFlop = kind == "D_FLIP_FLOP"
            val valuePosition = if (isFlipFlop) bounds.center else Offset(bounds.center.x, bounds.top + 9f)
            drawLabel(shown, valuePosition, measurer, signal, fontSize = 9f, centered = true, mono = true)
            if (isFlipFlop) {
                drawLabel("D", Offset(bounds.left + 4f, bounds.bottom - 12f), measurer, ink, fontSize = 8f)
                drawLabel("Q", Offset(bounds.right - 10f, bounds.top + 3f), measurer, ink, fontSize = 8f)
            } else {
                drawLabel(if (kind == "COUNTER") "ctr" else "D  Q", Offset(bounds.center.x, bounds.bottom - 10f), measurer, ink, fontSize = 8f, centered = true)
            }
            val clock = component.layout(registry).inputs.firstOrNull { it.name == "clock" }?.position?.asOffset()
            if (clock != null) {
                val direction = if (clock.x <= bounds.left) Offset(5f, 0f) else Offset(0f, -5f)
                val side = if (direction.x == 0f) Offset(3f, 0f) else Offset(0f, 3f)
                val triangle = Path().apply { moveTo((clock - side).x, (clock - side).y); lineTo((clock + direction).x, (clock + direction).y); lineTo((clock + side).x, (clock + side).y) }
                drawPath(triangle, ink, style = Stroke(1f))
            }
        }
        "ADDER", "SUBTRACTOR" -> {
            outlineBox(bounds, ink, paper)
            drawLabel(if (kind == "ADDER") "+" else "−", bounds.center, measurer, secondary.editorColorOr(ink), fontSize = 20f, centered = true)
        }
        "MULTIPLEXER", "DECODER" -> {
            val trapezoid = Path().apply {
                moveTo(bounds.left, bounds.top); lineTo(bounds.right, bounds.top + 10)
                lineTo(bounds.right, bounds.bottom - 10); lineTo(bounds.left, bounds.bottom); close()
            }
            drawPath(trapezoid, paper)
            drawPath(trapezoid, ink, style = Stroke(1.6f))
            drawLabel(if (kind == "MULTIPLEXER") "MUX" else "DEC", bounds.center, measurer, ink, fontSize = 9f, centered = true)
        }
        "NOT", "BUFFER", "CONTROLLED_BUFFER", "CONTROLLED_INVERTER", "AND", "NAND", "OR", "NOR", "XOR", "XNOR" -> drawGate(component, registry, ink, paper)
        else -> {
            outlineBox(bounds, ink, paper)
            val title = if (kind.substringBefore('@') == GrayCodeExtension.incrementerKind.name) "G+1"
                else registry.definition(component.kind)?.fileName ?: kind.replace('_', ' ')
            drawLabel(title, bounds.center, measurer, ink, fontSize = 9f, centered = true)
        }
    }
}

private fun DrawScope.drawGate(component: CircuitComponent, registry: ComponentRegistry, ink: Color, paper: Color) {
    val kind = component.kind.name
    val inverted = kind in listOf("NOT", "NAND", "NOR", "XNOR", "CONTROLLED_INVERTER")
    val single = kind in listOf("NOT", "BUFFER", "CONTROLLED_BUFFER", "CONTROLLED_INVERTER")
    val bounds = classicComponentBounds(component, registry)
    val end = if (inverted) -10f else 0f
    val inputs = component.inputPorts(registry)
    val rear = inputs.firstOrNull()?.x?.toFloat() ?: bounds.left
    val halfHeight = if (single) 10f else max(component.attributes["size"]?.toFloatOrNull()?.div(2f) ?: 25f, (inputs.maxOfOrNull { it.y } ?: 0).toFloat() + 5f)
    val path = Path()
    when {
        single -> { path.moveTo(rear, -halfHeight); path.lineTo(end, 0f); path.lineTo(rear, halfHeight); path.close() }
        kind == "AND" || kind == "NAND" -> {
            path.moveTo(rear, -halfHeight); path.lineTo(end - halfHeight, -halfHeight)
            path.cubicTo(end + halfHeight * 0.3f, -halfHeight, end + halfHeight * 0.3f, halfHeight, end - halfHeight, halfHeight)
            path.lineTo(rear, halfHeight); path.close()
        }
        else -> {
            path.moveTo(rear, -halfHeight)
            path.cubicTo(end - halfHeight, -halfHeight, end - 8f, -halfHeight * 0.75f, end, 0f)
            path.cubicTo(end - 8f, halfHeight * 0.75f, end - halfHeight, halfHeight, rear, halfHeight)
            path.quadraticTo(rear + 14f, 0f, rear, -halfHeight); path.close()
        }
    }
    drawPath(path, paper)
    drawPath(path, ink, style = Stroke(1.7f))
    if (kind == "XOR" || kind == "XNOR") {
        val curve = Path().apply { moveTo(rear - 6f, -halfHeight); quadraticTo(rear + 8f, 0f, rear - 6f, halfHeight) }
        drawPath(curve, ink, style = Stroke(1.4f))
    }
    if (inverted) {
        drawCircle(paper, 5f, Offset(-5f, 0f))
        drawCircle(ink, 5f, Offset(-5f, 0f), style = Stroke(1.5f))
    }
    for (port in inputs) {
        val start = port.asOffset()
        if (single && port != inputs.first()) drawLine(ink, start, Offset(start.x, 0f), 1.4f)
        // OR, NOR, XOR and XNOR take Evolution's input lines (shapedGateInputLines) in the value colour instead.
        else if (kind == "AND" || kind == "NAND") drawLine(ink, start, Offset(rear, start.y), 1.4f)
    }
}

private fun DrawScope.drawSplitter(component: CircuitComponent, registry: ComponentRegistry, ink: Color) {
    val ports = (component.inputPorts(registry) + component.outputPorts(registry)).distinct()
    val trunk = component.position.asOffset()
    for (port in ports) {
        val end = port.asOffset()
        val elbow = if (component.facing in listOf("north", "south")) Offset(trunk.x, end.y) else Offset(end.x, trunk.y)
        drawLine(ink, trunk, elbow, 3f)
        drawLine(ink, elbow, end, 1.8f)
    }
}

private fun DrawScope.drawTunnel(component: CircuitComponent, measurer: TextMeasurer, registry: ComponentRegistry, ink: Color, paper: Color) {
    val bounds = classicComponentBounds(component, registry)
    val p = component.position.asOffset()
    val path = Path()
    when (component.facing) {
        "east" -> { path.moveTo(p.x, p.y); path.lineTo(p.x - 5, bounds.top); path.lineTo(bounds.left, bounds.top); path.lineTo(bounds.left, bounds.bottom); path.lineTo(p.x - 5, bounds.bottom) }
        "north" -> { path.moveTo(p.x, p.y); path.lineTo(bounds.right, p.y + 5); path.lineTo(bounds.right, bounds.bottom); path.lineTo(bounds.left, bounds.bottom); path.lineTo(bounds.left, p.y + 5) }
        "south" -> { path.moveTo(p.x, p.y); path.lineTo(bounds.right, p.y - 5); path.lineTo(bounds.right, bounds.top); path.lineTo(bounds.left, bounds.top); path.lineTo(bounds.left, p.y - 5) }
        else -> { path.moveTo(p.x, p.y); path.lineTo(p.x + 5, bounds.top); path.lineTo(bounds.right, bounds.top); path.lineTo(bounds.right, bounds.bottom); path.lineTo(p.x + 5, bounds.bottom) }
    }
    path.close()
    drawPath(path, paper)
    drawPath(path, ink, style = Stroke(1.2f))
    classicTunnelLabel(component)?.let { drawVisualText(it.copy(color = ink.toArgb().toUInt()), measurer) }
}

private fun DrawScope.drawMemory(
    component: CircuitComponent, bounds: Rect, simulation: SimulationState, measurer: TextMeasurer,
    registry: ComponentRegistry, printerView: Boolean, firstAddress: Int?, ink: Color, secondary: Int?, paper: Color,
) {
    outlineBox(bounds, ink, paper)
    val configuration = MemoryComponents.configuration(component)
    val (addressBits, width) = configuration
    val count = if (addressBits < 16) (1 shl addressBits).toString() else "2^$addressBits"
    drawLabel("${component.kind.name} $count × $width", Offset(bounds.center.x, bounds.top + 9f), measurer, ink, fontSize = 11f, centered = true)
    drawLabel("A", bounds.topLeft + Offset(3f, 16f), measurer, ink, fontSize = 10f)
    drawLabel("D", Offset(bounds.right - 12f, bounds.center.y), measurer, ink, fontSize = 10f)
    if (printerView) return
    val activeAddress = memoryActiveAddress(component, simulation, registry, configuration.originalRom || configuration.originalInterface != null)
    val base = memoryDisplayGrid(bounds, addressBits, width)
    val display = if (firstAddress == null) base.following(activeAddress) else base.at(firstAddress)
    val image = runCatching { memoryEditorImage(component, simulation, registry) }.getOrNull()
    val grid = display.bounds
    // The light grey contents panel would hide the dark theme's white digits; keep the canvas there.
    drawRect(if (paper == Color.White) Color(0xFFE1E1E1) else paper, grid.topLeft, grid.size)
    drawRect(secondary.editorColorOr(Color(0xFF999999)), grid.topLeft, grid.size, style = Stroke(0.6f))
    for (address in display.addresses) {
        val cell = display.cell(address)
        if ((address - display.firstAddress) % display.columns == 0) {
            drawLabel(address.toString(16), Offset(grid.left + 2, cell.top + 3),
                measurer, secondary.editorColorOr(Color(0xFF666666)), fontSize = display.addressFontSize, mono = true)
        }
        if (activeAddress == address) drawRect(Color(0xFF555555), cell.topLeft, cell.size)
        val value = image?.takeIf { address.toLong() < it.capacity }?.get(address.toLong()) ?: 0uL
        drawLabel(value.toString(16).padStart(display.digits, '0'), cell.topLeft + Offset(0f, 3f), measurer,
            if (activeAddress == address) Color.White else ink, fontSize = display.fontSize, mono = true)
    }
}

internal class MemoryDisplayGrid(
    val bounds: Rect,
    val columns: Int,
    val digits: Int,
    private val rows: Int,
    private val addressBits: Int,
    firstAddress: Int = 0,
) {
    private val capacity = 1 shl addressBits
    private val visibleWords = minOf(capacity, columns * rows)
    val firstAddress = firstAddress.coerceIn(0, capacity - visibleWords)
    val addresses: IntRange get() = firstAddress until firstAddress + visibleWords
    private val addressDigits = (addressBits + 3) / 4
    private val gutter = maxOf(bounds.width * 0.8f / (columns + 0.8f), addressDigits * 4f + 2f)
    private val cellWidth = (bounds.width - gutter) / columns
    private val cellHeight = bounds.height / rows
    val fontSize: Float = minOf(9f, (cellWidth - 1f) / (digits * 0.65f))
    val addressFontSize: Float = minOf(9f, (gutter - 2f) / (addressDigits * 0.65f))
    val addressLabelBounds: Rect get() = Rect(bounds.left, bounds.top, bounds.left + gutter, bounds.bottom)

    fun at(address: Int): MemoryDisplayGrid =
        MemoryDisplayGrid(bounds, columns, digits, rows, addressBits, address)

    fun following(address: Int?): MemoryDisplayGrid {
        if (address == null || address !in 0 until capacity || address in addresses) return this
        val rowStart = address / columns * columns
        return at(if (address < firstAddress) rowStart else rowStart - (rows - 1) * columns)
    }

    fun cell(address: Int): Rect {
        val index = address - firstAddress
        val x = bounds.left + gutter + cellWidth * (index % columns)
        val y = bounds.top + cellHeight * (index / columns)
        return Rect(Offset(x, y), Size(cellWidth, cellHeight))
    }

    fun addressAt(point: Offset): Int? = addresses.firstOrNull { cell(it).contains(point) }
}

internal data class MemoryCanvasWindow(val firstAddress: Int = 0, val activeAddress: Int? = null) {
    fun update(grid: MemoryDisplayGrid, currentAddress: Int?): MemoryCanvasWindow {
        val current = grid.at(firstAddress)
        val display = if (currentAddress != activeAddress) current.following(currentAddress) else current
        return MemoryCanvasWindow(display.firstAddress, currentAddress)
    }
}

internal fun memoryActiveAddress(component: CircuitComponent, simulation: SimulationState, registry: ComponentRegistry): Int? {
    val configuration = MemoryComponents.configuration(component)
    return memoryActiveAddress(component, simulation, registry, configuration.originalRom || configuration.originalInterface != null)
}

private fun memoryActiveAddress(component: CircuitComponent, simulation: SimulationState, registry: ComponentRegistry, original: Boolean): Int? {
    val state = simulation.componentStates[component.id] as? MemoryComponentState
    if (original && state != null) return state.runtime.currentAddresses.firstOrNull()?.toInt()?.takeIf { it >= 0 }
    return component.inputPorts(registry).firstOrNull()?.let { simulation.portVectors[it]?.toULongOrNull()?.toInt() }
}

internal fun memoryDisplayGrid(component: CircuitComponent, registry: ComponentRegistry): MemoryDisplayGrid? {
    if (component.kind != ComponentKind.ROM && component.kind != ComponentKind.RAM) return null
    val (addressBits, width) = MemoryComponents.configuration(component)
    return memoryDisplayGrid(classicComponentBounds(component, registry), addressBits, width)
}

private fun memoryDisplayGrid(bounds: Rect, addressBits: Int, width: Int): MemoryDisplayGrid {
    val grid = Rect(bounds.left + 40f, bounds.top + 20f, bounds.right - 40f, min(bounds.bottom - 12f, bounds.top + 67f))
    val digits = (width + 3) / 4
    val preferredColumns = if (width > 8) 4 else 8
    val readableColumns = ((grid.width - ((addressBits + 3) / 4 * 4f + 2f)) / (digits * 6f + 2f)).toInt().coerceAtLeast(1)
    return MemoryDisplayGrid(grid, minOf(preferredColumns, readableColumns), digits, 2, addressBits)
}

private fun DrawScope.drawSevenSegment(bounds: Rect, value: Int?, rawSegments: Boolean, secondary: Int?) {
    val masks = intArrayOf(0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F, 0x77, 0x7C, 0x39, 0x5E, 0x79, 0x71)
    val mask = if (value == null) 0 else if (rawSegments) value else masks[value and 15]
    val left = bounds.left + 7f
    val right = bounds.right - 9f
    val top = bounds.top + 7f
    val bottom = bounds.bottom - 8f
    val middle = (top + bottom) / 2f
    val lines = listOf(
        Offset(left + 2, top) to Offset(right - 2, top),
        Offset(right, top + 2) to Offset(right, middle - 2),
        Offset(right, middle + 2) to Offset(right, bottom - 2),
        Offset(left + 2, bottom) to Offset(right - 2, bottom),
        Offset(left, middle + 2) to Offset(left, bottom - 2),
        Offset(left, top + 2) to Offset(left, middle - 2),
        Offset(left + 2, middle) to Offset(right - 2, middle),
    )
    for ((index, segment) in lines.withIndex()) drawLine(if (mask and (1 shl index) != 0) Color(0xFFF00000) else secondary.editorColorOr(Color(0xFFE6E6E6)), segment.first, segment.second, 3.5f)
}

/**
 * Tunnel.computeBounds draws the label in its `labelfont` (StdAttr.DEFAULT_LABEL_FONT) at the centre of the box that
 * TunnelAttributes.configureLabel places 5 units (ARROW_MARGIN) behind the point, H_CENTER and V_CENTER_OVERALL.
 * The box is max(10, text width) wide; without a measurer the text is aligned on the box's edge at the arrow, which
 * is that centre whenever the text is at least 10 units wide. Heights use Java's metrics as [classicComponentLabel].
 */
internal fun classicTunnelLabel(component: CircuitComponent, @Suppress("UNUSED_PARAMETER") bounds: Rect? = null): VisualPrimitive.Text? {
    val label = component.label.takeIf { it.isNotBlank() } ?: return null
    val font = classicLabelFont(component)
    val ascent = font.size.toInt()
    val height = ascent + ceil(font.size * 0.212).toInt()
    val box = max(10, height)
    val margin = 5
    val x = component.position.x
    val y = component.position.y
    // Baseline of a line whose overall centre (GraphicsUtil V_CENTER_OVERALL) is at `centre`.
    fun baseline(centre: Int) = centre - height / 2 + ascent
    val (anchor, align) = when (component.facing) {
        "north" -> VisualPoint(x.toDouble(), baseline(y + margin + box / 2).toDouble()) to VisualTextAlign.CENTER
        "south" -> VisualPoint(x.toDouble(), baseline(y - margin - box + box / 2).toDouble()) to VisualTextAlign.CENTER
        "east" -> VisualPoint((x - margin).toDouble(), baseline(y).toDouble()) to VisualTextAlign.END
        else -> VisualPoint((x + margin).toDouble(), baseline(y).toDouble()) to VisualTextAlign.START
    }
    return VisualPrimitive.Text(label, anchor, font, Ink.toArgb().toUInt(), align, VisualTextVerticalAlign.BASELINE)
}

private fun classicLabelFont(component: CircuitComponent): VisualFont =
    (component.attributes["labelfont"]?.let(AnnotationFont::parse) ?: EvolutionDefaultLabelFont).let {
        VisualFont(it.family, it.size.toDouble(), it.bold, it.italic)
    }

private val AbstractGateKinds = setOf(ComponentKind.AND, ComponentKind.OR, ComponentKind.NAND, ComponentKind.NOR,
    ComponentKind.XOR, ComponentKind.XNOR, GatesLibrary.OddParity, GatesLibrary.EvenParity)
private val NegatedOutputGateKinds = setOf(ComponentKind.NAND, ComponentKind.NOR, ComponentKind.XNOR)
private val BufferLabelKinds = setOf(ComponentKind.NOT, ComponentKind.BUFFER, ComponentKind.CONTROLLED_BUFFER, ComponentKind.CONTROLLED_INVERTER)

/** A label TextField: its point, horizontal alignment and the baseline offset of its vertical alignment. */
private class LabelField(val x: Int, val y: Int, val align: VisualTextAlign, val baseline: Int)

/**
 * The gates place their own label instead of Instance.computeLabelTextField, and ignore `labelloc`:
 * AbstractGate.computeLabel centres it (H_CENTER, V_CENTER) on the body, size / 2 (+ 10 with a negated output) behind
 * the output, 6 units across the axis with rectangular (IEC) gates; NotGate.configureLabel, also used by Buffer and
 * ControlledBuffer, puts its baseline (V_BASELINE) on the top of the body, 2 units higher for the rectangular NOT and
 * for north and south, centred, or from the control input when that input is on the top edge.
 */
private fun gateLabelField(component: CircuitComponent, gateShape: EditorGateShape, ascent: Int): LabelField? {
    val x = component.position.x
    val y = component.position.y
    val facing = component.facing
    if (component.kind in AbstractGateKinds) {
        val axis = (component.attributes["size"]?.toIntOrNull() ?: 50) / 2 + if (component.kind in NegatedOutputGateKinds) 10 else 0
        val perp = if (gateShape == EditorGateShape.IEC) 6 else 0
        val (cx, cy) = when (facing) {
            "north" -> (x + perp) to (y + axis)
            "south" -> (x - perp) to (y - axis)
            "west" -> (x + axis) to (y - perp)
            else -> (x - axis) to (y + perp)
        }
        return LabelField(cx, cy, VisualTextAlign.CENTER, ascent - ascent / 2)
    }
    if (component.kind !in BufferLabelKinds) return null
    val controlled = component.kind == ComponentKind.CONTROLLED_BUFFER || component.kind == ComponentKind.CONTROLLED_INVERTER
    val sized = component.kind == ComponentKind.NOT || component.kind == ComponentKind.CONTROLLED_INVERTER
    val length = if (sized) component.attributes["size"]?.toIntOrNull()?.takeIf { it == 20 } ?: 30 else 20
    val half = if (controlled) 10 else 9
    // NotGate, Buffer and ControlledBuffer getOffsetBounds: left and top of the body.
    val (left, top, width) = when (facing) {
        "north" -> Triple(x - half, y, 2 * half)
        "south" -> Triple(x - half, y - length, 2 * half)
        "west" -> Triple(x, y - half, length)
        else -> Triple(x - length, y - half, length)
    }
    if (facing == "north" || facing == "south") return LabelField(left + width / 2 + 2, top - 2, VisualTextAlign.START, 0)
    val rectangular = component.kind == ComponentKind.NOT && gateShape == EditorGateShape.IEC
    val labelY = if (rectangular) top - 2 else top
    if (controlled) {
        // ControlledBuffer.configurePorts: the control input, 10 units before the input end, on the left or right.
        val right = if (component.attributes["control"] == "left") 10 else -10
        val (controlX, controlY) = if (facing == "west") (x + length - 10) to (y + right) else (x - length + 10) to (y - right)
        if (controlY == top) return LabelField(controlX + 2, labelY, VisualTextAlign.START, 0)
    }
    return LabelField(left + width / 2, labelY, VisualTextAlign.CENTER, 0)
}

/**
 * The label TextField of Instance.computeLabelTextField (SubcircuitFactory.configureLabel for subcircuits), drawn in
 * the component's `labelfont` or, without one, StdAttr.DEFAULT_LABEL_FONT (SansSerif bold 16). Original 2.7.1 files
 * load the plain 12 default of that edition as an implicit `labelfont`. Vertical alignment follows TextField with
 * Java's font metrics (ascent = size, descent = ceil(0.212 size)), so each label sits on Evolution's baseline.
 */
internal fun classicComponentLabel(
    component: CircuitComponent,
    bounds: Rect,
    defaultColor: Color = DefaultLabelColor,
    gateShape: EditorGateShape = EditorGateShape.ANSI,
): VisualPrimitive.Text? {
    if (component.kind == ComponentKind.TUNNEL) return classicTunnelLabel(component)
    val label = component.label.ifBlank { component.sharedLabel }
    if (label.isBlank() || component.attributes["labelvisible"] == "false") return null
    val location = component.attributes["labelloc"] ?: "north"
    val font = classicLabelFont(component)
    val ascent = font.size.toInt()
    val descent = ceil(font.size * 0.212).toInt()
    val color = themedLabelColor(component.attributes["labelcolor"], defaultColor).toArgb().toUInt()
    gateLabelField(component, gateShape, ascent)?.let { field ->
        return VisualPrimitive.Text(label, VisualPoint(field.x.toDouble(), (field.y + field.baseline).toDouble()), font, color,
            field.align, VisualTextVerticalAlign.BASELINE)
    }
    val isPin = component.kind == ComponentKind.INPUT || component.kind == ComponentKind.OUTPUT
    // Pin passes AVOID_LEFT, which computeLabelTextField rotates onto the facing side, where the wire attaches.
    // Evolution moves the label 2 units off the wire axis; Mobile keeps 4 so that it also clears a 4-unit bus.
    val avoid = if (isPin) component.facing else null
    val shift = 4f
    val subcircuit = component.kind == ComponentKind.SUBCIRCUIT
    var x = floor(bounds.left + bounds.width / 2f)
    var y = floor(bounds.top + bounds.height / 2f)
    var align = VisualTextAlign.CENTER
    // Baseline offset from the field's y: V_CENTER, V_TOP, V_BASELINE and V_BOTTOM of TextField.
    var baseline = ascent / 2
    when (location) {
        "north" -> {
            y = bounds.top - 2f
            baseline = if (subcircuit) 0 else -descent
            if (avoid == "north") { x += shift; align = VisualTextAlign.START }
        }
        "south" -> {
            y = bounds.bottom + 2f
            baseline = ascent
            if (avoid == "south") { x += shift; align = VisualTextAlign.START }
        }
        "east" -> {
            x = bounds.right + 2f
            align = VisualTextAlign.START
            if (avoid == "east") { y -= shift; baseline = -descent }
        }
        "west" -> {
            x = bounds.left - 2f
            align = VisualTextAlign.END
            if (avoid == "west") { y -= shift; baseline = -descent }
        }
    }
    return VisualPrimitive.Text(label, VisualPoint(x.toDouble(), (y + baseline).toDouble()), font, color, align, VisualTextVerticalAlign.BASELINE)
}

/** StdAttr.DEFAULT_LABEL_FONT. */
internal val EvolutionDefaultLabelFont = AnnotationFont("SansSerif", 16, bold = true)

private fun DrawScope.drawComponentLabel(component: CircuitComponent, bounds: Rect, measurer: TextMeasurer, style: CircuitDrawingStyle) {
    classicComponentLabel(component, bounds, style.defaultLabelColor, style.gateShape)?.let { drawVisualText(it, measurer) }
}

internal fun TextMeasurer.classicComponentVisualBounds(component: CircuitComponent, registry: ComponentRegistry,
    gateShape: EditorGateShape = EditorGateShape.ANSI): dev.logisim.mobile.core.VisualBounds {
    val body = classicComponentBounds(component, registry)
    val bounds = dev.logisim.mobile.core.VisualBounds(body.left.toDouble(), body.top.toDouble(), body.right.toDouble(), body.bottom.toDouble()).expanded(2.0)
    val label = classicComponentLabel(component, body, gateShape = gateShape) ?: return bounds
    return bounds.union(visualTextBounds(label))
}

private fun DrawScope.outlineBox(bounds: Rect, ink: Color, fill: Color) {
    drawRect(fill, bounds.topLeft, bounds.size)
    drawRect(ink, bounds.topLeft, bounds.size, style = Stroke(1.5f))
}

private fun DrawScope.drawLabel(
    text: String,
    position: Offset,
    measurer: TextMeasurer,
    color: Color = Ink,
    font: String? = null,
    fontSize: Float = 11f,
    centered: Boolean = false,
    rightAligned: Boolean = false,
    mono: Boolean = false,
) {
    drawVisualText(classicLabel(text, position, color, font, fontSize, centered, rightAligned, mono), measurer)
}

internal fun classicLabel(text: String, position: Offset, color: Color = Ink, font: String? = null,
    fontSize: Float = 11f, centered: Boolean = false, rightAligned: Boolean = false, mono: Boolean = false): VisualPrimitive.Text {
    val parsed = font?.let(AnnotationFont::parse)
    return VisualPrimitive.Text(text, VisualPoint(position.x.toDouble(), position.y.toDouble()),
        VisualFont(if (mono) "Monospaced" else parsed?.family ?: "SansSerif", parsed?.size?.toDouble() ?: fontSize.toDouble(), parsed?.bold ?: false, parsed?.italic ?: false),
        color.toArgb().toUInt(), when { centered -> VisualTextAlign.CENTER; rightAligned -> VisualTextAlign.END; else -> VisualTextAlign.START },
        verticalAlign = if (centered || rightAligned) VisualTextVerticalAlign.CENTER else VisualTextVerticalAlign.TOP)
}

private fun GridPoint.asOffset() = Offset(x.toFloat(), y.toFloat())
internal fun formattedSignal(value: LogicVector, radix: String?, characters: ValueCharacters = ValueCharacters.Default): String {
    val number = value.toULongOrNull() ?: return value.display(characters)
    return when (radix) {
        "16" -> number.toString(16).padStart((value.width + 3) / 4, '0')
        "8" -> number.toString(8)
        "10unsigned" -> number.toString()
        "10signed" -> {
            val shift = 64 - value.width
            ((number shl shift).toLong() shr shift).toString()
        }
        // Evolution's Value.toStringFromFloatValue: FP8, FP16, float and double; other widths are NaN.
        "float" -> when (value.width) {
            8, 16, 32 -> FloatingPointNumbers.decode(value).toFloat().toString()
            64 -> FloatingPointNumbers.decode(value).toString()
            else -> "NaN"
        }
        else -> value.display(characters)
    }
}
