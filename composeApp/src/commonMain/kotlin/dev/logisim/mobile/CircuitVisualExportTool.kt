package dev.logisim.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.logisim.mobile.core.*
import dev.logisim.mobile.core.localization.message

/** Native delivery can be absent in a host, while the drawing tool remains discoverable. */
fun circuitVisualExportEditorTool(
    registry: ComponentRegistry = ComponentCatalog.registry,
    assets: DocumentAssetActions?,
    drawingStyle: CircuitDrawingStyle = CircuitDrawingStyle(),
): EditorTool {
    val delivery = assets?.visualExportDelivery() ?: object : VisualExportDelivery {
        override fun supports(action: VisualExportAction): Boolean = false
        override fun deliver(asset: DocumentAsset, action: VisualExportAction, onResult: (AssetActionResult) -> Unit) {
            onResult(AssetActionResult.Failure(message("visual_export_action_unavailable")))
        }
    }
    return EditorTool("visual-export", "Export drawing", titleText = message("visual_export_title"), requiresProjectGraph = true) { context ->
        val source = rememberCircuitExportSource(registry, drawingStyle.copy(printerView = false))
        val printerSource = rememberCircuitExportSource(registry, drawingStyle.copy(printerView = true))
        VisualExportPanel(context, source, delivery, printerSource = printerSource, registry = registry)
    }
}

@Composable
fun rememberCircuitExportSource(registry: ComponentRegistry = ComponentCatalog.registry, style: CircuitDrawingStyle = CircuitDrawingStyle()): CircuitExportSource {
    val resolver = LocalFontFamilyResolver.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    return remember(registry, style, resolver, density, direction) { RenderedCircuitExportSource(registry, resolver, density, direction, style) }
}

/** A request creates its own text/bitmap caches; no canvas cache is accessed from a worker. */
class RenderedCircuitExportSource(
    private val registry: ComponentRegistry,
    private val fontResolver: FontFamily.Resolver,
    private val density: Density,
    private val direction: LayoutDirection,
    private val style: CircuitDrawingStyle = CircuitDrawingStyle(),
) : CircuitExportSource {
    private fun measurer() = TextMeasurer(fontResolver, density, direction, cacheSize = 128)

    /** Exports draw the canvas colours, including NIL_COLOR for wires of the whole circuit that no terminal gives a width. */
    private fun style(context: EditorToolContext): CircuitDrawingStyle =
        if (style.printerView) style else style.copy(floatingPoints = CircuitNetworks(context.document, registry).widthlessPoints())

    override fun selectionIds(context: EditorToolContext): Set<String> =
        context.selectedComponents.map { it.id }.toSet() + context.selectedWires.map(::circuitDrawingWireId)

    override fun bounds(context: EditorToolContext, selectionIds: Set<String>?): VisualBounds? =
        circuitDrawingBounds(circuitDrawingSelection(context.document, selectionIds), measurer(), AnnotationImageCache(), registry, context.project::circuit, style.gateShape)

    override fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, context: EditorToolContext, selectionIds: Set<String>?) {
        with(scope) {
            drawCircuitDrawing(circuitDrawingSelection(context.document, selectionIds), context.simulation, measurer(),
                AnnotationImageCache(), registry, context.project::circuit, style(context), projectStates = context.projectSimulation.circuitStates)
        }
    }

    override fun scene(context: EditorToolContext, selectionIds: Set<String>?): VisualScene? {
        val document = circuitDrawingSelection(context.document, selectionIds)
        val appearances = mutableMapOf<String, SymbolAppearance>()
        for (component in document.components) {
            if (AnnotationLibrary.isAnnotation(component) || gateVisualItem(component, style.gateShape, registry) != null) continue
            val child = component.circuitName?.let(context.project::circuit) ?: return null
            val appearance = SymbolAppearance.rendered(child) ?: return null
            if (appearance.objects.any { it is SymbolObject.Opaque }) return null
            appearances[component.id] = appearance
        }
        val measurer = measurer()
        val images = AnnotationImageCache()
        val items = circuitWireScene(document, context.simulation, style(context)).toMutableList()
        for (component in document.components) {
            var item = if (AnnotationLibrary.isAnnotation(component)) measurer.annotationDrawingItem(component, images)
                else gateVisualItem(component, style.gateShape, registry)
                    ?: measurer.symbolVisualItem(component, appearances.getValue(component.id),
                        component.circuitName?.let(context.project::circuit),
                        context.simulation.symbolChildState(component, context.projectSimulation.circuitStates).takeUnless { style.printerView })
            if (!AnnotationLibrary.isAnnotation(component)) classicComponentLabel(component, classicComponentBounds(component, registry), gateShape = style.gateShape)?.let { label ->
                item = item.copy(bounds = item.bounds.union(measurer.visualTextBounds(label)), primitives = item.primitives + label)
            }
            if (!AnnotationLibrary.isAnnotation(component)) {
                val markers = circuitDrawingTerminalMarkers(component, context.simulation, registry, style.highContrastSignals, style.printerView, style.terminalRadius, style.gateShape)
                item = item.copy(bounds = markers.fold(item.bounds) { bounds, marker -> bounds.union(marker.bounds) }, primitives = item.primitives + markers)
            }
            items += item
        }
        return VisualScene(items)
    }
}

internal fun circuitDrawingTerminalMarkers(component: CircuitComponent, simulation: SimulationState, registry: ComponentRegistry, highContrast: Boolean, printerView: Boolean = false,
    radius: Double = ClassicTerminalRadius, gateShape: EditorGateShape = EditorGateShape.ANSI): List<VisualPrimitive.Ellipse> =
    if (component.omitsPrinterViewPorts(printerView, gateShape)) emptyList() else (component.inputPorts(registry) + component.outputPorts(registry)).distinct().minus(listOfNotNull(component.controlTerminal(registry))).map { point ->
        VisualPrimitive.Ellipse(VisualBounds(point.x - radius, point.y - radius, point.x + radius, point.y + radius),
            VisualPaint(classicPortColor(simulation, point, highContrast, printerView).toArgb().toUInt(), stroke = null))
    }
