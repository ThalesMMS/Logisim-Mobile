package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

internal fun readSymbolLed(node: CircXmlElement, circuit: CircuitDocument, id: String): SymbolObject.Led? {
    if (node.name != "visible-led" || node.children.isNotEmpty() || node.rawXml.contains("<!--")) return null
    if (node.attributes.keys.any { it in setOf("transform", "style", "clip-path") }) return null
    val path = node.attributes["path"] ?: return null
    val match = Regex("""/LED\(\s*(-?\d+)\s*,\s*(-?\d+)\s*\)""").matchEntire(path) ?: return null
    val sourceX = match.groupValues[1].toIntOrNull() ?: return null
    val sourceY = match.groupValues[2].toIntOrNull() ?: return null
    val sourcePosition = GridPoint(sourceX, sourceY)
    val source = circuit.components.singleOrNull { it.kind == IoKinds.Led && it.position == sourcePosition } ?: return null
    val x = node.attributes["x"]?.trim()?.toIntOrNull() ?: return null
    val y = node.attributes["y"]?.trim()?.toIntOrNull() ?: return null
    val radius = node.attributes["value-radius"]?.let { it.trim().toIntOrNull() ?: return null } ?: 5
    if (x !in -1_000_000..1_000_000 || y !in -1_000_000..1_000_000 || radius !in 1..20) return null
    return SymbolObject.Led(id, source.id, GridPoint(x, y), radius,
        node.attributes - setOf("id", "path", "x", "y", "width", "height", "value-radius"))
}

internal fun symbolLedPath(component: CircuitComponent): String = "/LED(${component.position.x},${component.position.y})"

internal fun SymbolObject.Led.validate(circuit: CircuitDocument) {
    val source = circuit.components.singleOrNull { it.kind == IoKinds.Led && it.id == sourceId }
    requireLocalized(source != null && circuit.components.count {
        it.kind == IoKinds.Led && it.position == source.position
    } == 1) { message("appearance_led_missing") }
    requireLocalized(position.x in -1_000_000..1_000_000 && position.y in -1_000_000..1_000_000 && radius in 1..20) {
        message("appearance_invalid_dimensions")
    }
}

internal fun SymbolObject.Led.visualItem(source: CircuitComponent?, state: SimulationState?): VisualItem {
    val sourceState = state?.componentStates?.get(sourceId) as? IoLedState
    val attributes = source?.let(ComponentCatalog.registry::attributes)
    val on = sourceState != null && attributes != null && IoLogic.ledOn(sourceState.value, attributes[IoAttributes.Active])
    val fill = when {
        attributes == null -> IoAttributes.LedOffColor.default
        on -> attributes[IoAttributes.OnColor]
        else -> attributes[IoAttributes.LedOffColor]
    }
    val stroke = this.attributes["stroke-width"]?.trim()?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it in 0.0..10_000.0 } ?: 1.0
    val bounds = VisualBounds(position.x.toDouble(), position.y.toDouble(),
        position.x + 2.0 * radius, position.y + 2.0 * radius)
    val oval = VisualBounds(bounds.left + 1.0, bounds.top + 1.0, bounds.right - 1.0, bounds.bottom - 1.0)
    val outline = if (state == null) 0xff42f498u else 0xff404040u
    return VisualItem(id, bounds.expanded(stroke / 2.0),
        listOf(VisualPrimitive.Ellipse(oval, VisualPaint(fill, outline, stroke.coerceAtLeast(1.0)))))
}
