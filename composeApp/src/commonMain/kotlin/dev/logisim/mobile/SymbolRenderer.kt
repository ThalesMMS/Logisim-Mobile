package dev.logisim.mobile

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.SymbolAppearance
import dev.logisim.mobile.core.SymbolObject
import dev.logisim.mobile.core.SymbolVisuals
import dev.logisim.mobile.core.VisualBounds
import dev.logisim.mobile.core.VisualItem
import dev.logisim.mobile.core.VisualPoint
import dev.logisim.mobile.core.VisualPrimitive
import dev.logisim.mobile.core.VisualScene

fun TextMeasurer.symbolVisualItem(
    instance: CircuitComponent,
    appearance: SymbolAppearance,
    childCircuit: CircuitDocument? = null,
    simulation: SimulationState? = null,
): VisualItem {
    val scene = SymbolVisuals.symbol(appearance, circuit = childCircuit, simulation = simulation)
    val item = SymbolVisuals.symbolInstance(instance, appearance, scene)
    val directions = listOf("east", "south", "west", "north")
    val turns = (directions.indexOf(instance.facing) - directions.indexOf(appearance.anchor.facing) + 4) % 4
    fun world(point: VisualPoint): VisualPoint {
        var x = point.x - appearance.anchor.position.x
        var y = point.y - appearance.anchor.position.y
        repeat(turns) { val previousX = x; x = -y; y = previousX }
        return VisualPoint(x + instance.position.x, y + instance.position.y)
    }
    val textBounds = scene.items.flatMap { it.primitives }.filterIsInstance<VisualPrimitive.Text>().map { text ->
        val bounds = visualTextBounds(text)
        val points = listOf(VisualPoint(bounds.left, bounds.top), VisualPoint(bounds.right, bounds.top), VisualPoint(bounds.right, bounds.bottom), VisualPoint(bounds.left, bounds.bottom)).map(::world)
        VisualBounds(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }
    return item.copy(bounds = textBounds.fold(item.bounds) { bounds, text -> bounds.union(text) })
}

fun DrawScope.drawSymbolComponent(
    instance: CircuitComponent,
    childCircuit: CircuitDocument,
    textMeasurer: TextMeasurer,
    simulation: SimulationState? = null,
    imageDecoder: VisualImageDecoder? = null,
    outline: Int? = null,
): Boolean {
    if (instance.kind != ComponentKind.SUBCIRCUIT) return false
    val decoder = imageDecoder ?: AnnotationImageCache()
    val appearance = if (decoder is AnnotationImageCache) decoder.symbolAppearance(childCircuit)
        else SymbolAppearance.rendered(childCircuit)
    if (appearance == null) return false
    val displayed = if (outline != null && !SymbolAppearance.isCustom(childCircuit)) appearance.inComponentColor(outline) else appearance
    drawVisualScene(VisualScene(listOf(textMeasurer.symbolVisualItem(instance, displayed, childCircuit, simulation))), textMeasurer, decoder)
    return true
}

internal fun SimulationState.symbolChildState(
    instance: CircuitComponent,
    projectStates: Map<String, SimulationState>,
): SimulationState? {
    val path = if (instancePath.isEmpty()) instance.id else "$instancePath/${instance.id}"
    return circuitStates[path] ?: circuitStates[instance.id] ?: projectStates[path]
}

/**
 * A default appearance drawn in COMPONENT_COLOR [component], as Evolution generates it: outlines, pins and the
 * title band take the color and the title text its complement (DefaultEvolutionAppearance, 0xFFFFFF ^ color).
 * Custom appearances keep the colors stored in their shapes, as Evolution's draw shapes do.
 */
internal fun SymbolAppearance.inComponentColor(component: Int): SymbolAppearance {
    val color = editorColorHex(component and 0xFFFFFF)
    val complement = editorColorHex(component.inv() and 0xFFFFFF)
    return copy(objects = objects.map { item ->
        if (item is SymbolObject.Shape) item.copy(attributes = item.attributes.mapValues { (key, value) ->
            when {
                key !in setOf("stroke", "fill") -> value
                value.equals("#000000", ignoreCase = true) -> color
                key == "fill" && item.tag == "text" && value.equals("#ffffff", ignoreCase = true) -> complement
                else -> value
            }
        }) else item
    })
}
