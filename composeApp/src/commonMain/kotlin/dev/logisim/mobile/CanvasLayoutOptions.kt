package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.ComponentTerminal
import dev.logisim.mobile.core.layout
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.localizedLabel
import dev.logisim.mobile.core.localization.message
import kotlin.math.abs
import kotlin.math.sqrt

/** Canvas.HALO_COLOR. */
internal val AttributeHaloColor = Color(0xFFFF00FF)

/** CanvasPainter's halo: an ellipse through the corners of the component bounds expanded by 5. */
internal fun attributeHaloOval(bounds: Rect): Rect {
    val expanded = bounds.inflate(5f)
    val width = expanded.width * sqrt(2f)
    val height = expanded.height * sqrt(2f)
    return Rect(Offset(expanded.center.x - width / 2f, expanded.center.y - height / 2f), Size(width, height))
}

internal fun DrawScope.drawAttributeHalo(bounds: Rect) {
    val oval = attributeHaloOval(bounds)
    drawOval(AttributeHaloColor, oval.topLeft, oval.size, style = Stroke(3f))
}

/** What InstanceComponent.getToolTip describes at a point: a component and, near one of its ends, that port. */
internal data class ComponentTip(val component: CircuitComponent, val port: ComponentTerminal?)

/** Canvas.getToolTipText: the topmost component containing [point]; ports within Manhattan distance 10 qualify. */
internal fun CircuitDocument.componentTipAt(point: Offset, registry: ComponentRegistry): ComponentTip? {
    for (component in components.asReversed()) {
        val layout = runCatching { component.layout(registry) }.getOrNull() ?: continue
        val port = layout.ports.firstOrNull { abs(it.position.x - point.x) + abs(it.position.y - point.y) < 10f }
        val bounds = layout.bounds
        val inside = point.x >= bounds.left && point.x <= bounds.right && point.y >= bounds.top && point.y <= bounds.bottom
        if (inside || port != null) return ComponentTip(component, port)
    }
    return null
}

/** The tip text; Evolution shows nothing away from a port unless the factory has a default tip. */
internal fun ComponentTip.text(includeComponentOnly: Boolean = false): LocalizedText? {
    val name = if (component.label.isBlank()) component.kind.localizedLabel() else LocalizedText.literal(component.label)
    val port = port ?: return name.takeIf { includeComponentOnly }
    return message("canvas_component_tip", "component" to name, "port" to port.label.ifBlank { port.name })
}
