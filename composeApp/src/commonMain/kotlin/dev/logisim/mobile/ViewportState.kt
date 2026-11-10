package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.VisualBounds
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.bounds
import kotlin.math.max
import kotlin.math.min

internal data class CircuitViewport(val scale: Float = 1f, val pan: Offset = Offset.Zero) {
    fun world(screen: Offset): Offset = (screen - pan) / scale

    fun zoomed(factor: Float, anchor: Offset): CircuitViewport {
        if (!factor.isFinite() || factor <= 0f) return this
        return zoomedTo(scale * factor, anchor)
    }

    fun zoomedTo(target: Float, anchor: Offset): CircuitViewport {
        val next = target.coerceIn(1e-9f, 12f)
        return CircuitViewport(next, anchor + (pan - anchor) * (next / scale))
    }

    fun resized(oldSize: IntSize, newSize: IntSize): CircuitViewport = copy(
        pan = pan + Offset((newSize.width - oldSize.width) / 2f, (newSize.height - oldSize.height) / 2f),
    )
}

/** Session state belongs to the circuit or instance selected by the editor. */
internal data class CanvasViewportState(
    val view: CircuitViewport = CircuitViewport(),
    val size: IntSize = IntSize.Zero,
    val initialized: Boolean = false,
    val fitted: Boolean = true,
) {
    fun resized(
        document: CircuitDocument,
        newSize: IntSize,
        inset: Float,
        bottomInset: Float,
        registry: ComponentRegistry = ComponentCatalog.registry,
        contentBounds: VisualBounds? = null,
    ): CanvasViewportState {
        if (newSize.width <= 0 || newSize.height <= 0) return this
        if (newSize == size && initialized) return this
        val nextView = if (!initialized || fitted) fittedViewport(document, newSize, inset, bottomInset, view, registry, contentBounds)
            else if (size == IntSize.Zero) view else view.resized(size, newSize)
        return copy(view = nextView, size = newSize, initialized = true)
    }

    fun fit(
        document: CircuitDocument,
        inset: Float,
        bottomInset: Float,
        selectedIds: Set<String> = emptySet(),
        selectedWires: Set<WireSegment> = emptySet(),
        selectionOnly: Boolean = false,
        registry: ComponentRegistry = ComponentCatalog.registry,
        contentBounds: VisualBounds? = null,
    ): CanvasViewportState {
        if (size.width <= 0 || size.height <= 0) return this
        val target = viewportFitDocument(document, selectedIds, selectedWires, selectionOnly)
        return copy(
            view = fittedViewport(target, size, inset, bottomInset, view, registry, contentBounds),
            initialized = true,
            fitted = !selectionOnly,
        )
    }
}

internal fun viewportFitDocument(
    document: CircuitDocument,
    selectedIds: Set<String> = emptySet(),
    selectedWires: Set<WireSegment> = emptySet(),
    selectionOnly: Boolean = false,
): CircuitDocument {
    if (!selectionOnly) return document
    val selected = circuitDrawingSelection(document, selectedIds + selectedWires.map(::circuitDrawingWireId))
    return if (selected.components.isEmpty() && selected.wires.isEmpty()) document else selected
}

/** Headless callers can fit electrical geometry without constructing a font renderer. */
internal fun viewportGeometryBounds(document: CircuitDocument, registry: ComponentRegistry = ComponentCatalog.registry): VisualBounds {
    val bounds = document.components.map { it.bounds(registry) }
    val points = document.wires.flatMap { listOf(it.from, it.to) }
    return VisualBounds(
        min(bounds.minOfOrNull { it.left.toDouble() } ?: 0.0, points.minOfOrNull { it.x.toDouble() } ?: Double.POSITIVE_INFINITY),
        min(bounds.minOfOrNull { it.top.toDouble() - 22.0 } ?: 0.0, points.minOfOrNull { it.y.toDouble() } ?: Double.POSITIVE_INFINITY),
        max(bounds.maxOfOrNull { it.right.toDouble() } ?: 400.0, points.maxOfOrNull { it.x.toDouble() } ?: Double.NEGATIVE_INFINITY),
        max(bounds.maxOfOrNull { it.bottom.toDouble() + 12.0 } ?: 280.0, points.maxOfOrNull { it.y.toDouble() } ?: Double.NEGATIVE_INFINITY),
    )
}

internal fun fittedViewport(
    document: CircuitDocument,
    size: IntSize,
    inset: Float,
    bottomInset: Float,
    previous: CircuitViewport = CircuitViewport(),
    registry: ComponentRegistry = ComponentCatalog.registry,
    contentBounds: VisualBounds? = null,
): CircuitViewport {
    val bounds = contentBounds ?: viewportGeometryBounds(document, registry)
    val minX = bounds.left.toFloat()
    val minY = bounds.top.toFloat()
    val maxX = bounds.right.toFloat()
    val maxY = bounds.bottom.toFloat()
    val width = max(100f, bounds.width.toFloat())
    val height = max(100f, bounds.height.toFloat())
    val availableWidth = size.width - 2 * inset
    val fullHeight = size.height - 2 * inset
    val minimumExtent = max(32f, inset)
    if (availableWidth < minimumExtent || fullHeight < minimumExtent) return previous
    fun fitRectangle(availableHeight: Float, fitWidth: Float = availableWidth): CircuitViewport {
        val scale = min(fitWidth / width, availableHeight / height).coerceAtMost(4f)
        return CircuitViewport(scale, Offset(
            inset + (fitWidth - width * scale) / 2f - minX * scale,
            inset + (availableHeight - height * scale) / 2f - minY * scale,
        ))
    }
    val full = fitRectangle(fullHeight)
    val controlsLeft = size.width - bottomInset * (204f / 72f)
    val controlsTop = size.height - bottomInset
    if (maxX * full.scale + full.pan.x <= controlsLeft || maxY * full.scale + full.pan.y <= controlsTop) return full
    val availableHeight = fullHeight - bottomInset
    val leftWidth = controlsLeft - 2 * inset
    val alternatives = listOfNotNull(
        if (availableHeight >= minimumExtent) fitRectangle(availableHeight) else null,
        if (leftWidth >= minimumExtent) fitRectangle(fullHeight, leftWidth) else null,
    )
    return alternatives.maxByOrNull { it.scale } ?: previous
}
