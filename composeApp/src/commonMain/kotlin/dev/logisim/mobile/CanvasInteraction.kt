package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentBounds
import dev.logisim.mobile.core.bounds
import dev.logisim.mobile.core.inputPorts
import dev.logisim.mobile.core.outputPorts
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal enum class CanvasCommand { COPY, CUT, PASTE, DUPLICATE, DELETE, SELECT_ALL, UNDO, REDO, CANCEL, FIT_SELECTION }
internal enum class CanvasDragAction { PAN, MOVE_SELECTION, SELECT_AREA, MOVE_WIRE_CORNER, DRAW_WIRE, PLACE }
internal enum class CanvasGesturePhase { PRESSED, DRAGGING, TRANSFORMING, CANCELLED, FINISHED }

internal sealed interface CanvasHit {
    data class Component(val id: String) : CanvasHit
    data class Wire(val segment: WireSegment) : CanvasHit
    data class WireCorner(val point: GridPoint) : CanvasHit
    data object Empty : CanvasHit
}

internal sealed interface CanvasGestureCompletion {
    data class Tap(val position: Offset) : CanvasGestureCompletion
    data class Drag(val action: CanvasDragAction, val from: Offset, val to: Offset) : CanvasGestureCompletion
}

/** Compose's iOS first-down helper filters out non-primary mouse buttons. */
internal suspend fun AwaitPointerEventScope.awaitCanvasFirstDown(): PointerInputChange {
    while (true) {
        val changes = awaitPointerEvent().changes
        if (changes.isNotEmpty() && changes.all { it.changedToDownIgnoreConsumed() }) return changes.first()
    }
}

/** A touch gesture can acquire more fingers; pen and mouse gestures retain their original pointer. */
internal fun PointerEvent.forCanvasGesture(first: PointerInputChange): PointerEvent? {
    fun belongs(change: PointerInputChange): Boolean = change.id == first.id ||
        (first.type == PointerType.Touch && change.type == PointerType.Touch)

    if (changes.isEmpty()) return null
    if (changes.all(::belongs)) return this
    val gestureChanges = changes.filter(::belongs)
    return if (gestureChanges.isEmpty()) null else PointerEvent(gestureChanges)
}

/** A transform or cancellation cannot later become an edit when only one pointer remains. */
internal class CanvasGestureMachine(
    val start: Offset,
    private val action: CanvasDragAction,
) {
    var phase: CanvasGesturePhase = CanvasGesturePhase.PRESSED
        private set
    var position: Offset = start
        private set

    fun move(event: PointerEvent, pointerId: PointerId, touchSlop: Float): CanvasGesturePhase {
        val change = event.changes.firstOrNull { it.id == pointerId }
        val pressed = event.changes.count { it.pressed }
        if (pressed == 0 && event.changes.any { it.previousPressed && !it.pressed && it.isConsumed }) {
            cancel()
        }
        return move(change?.position ?: position, touchSlop, pressed)
    }

    fun move(position: Offset, touchSlop: Float, pointerCount: Int): CanvasGesturePhase {
        if (phase == CanvasGesturePhase.CANCELLED || phase == CanvasGesturePhase.FINISHED) return phase
        this.position = position
        if (pointerCount > 1) phase = CanvasGesturePhase.TRANSFORMING
        else if (phase == CanvasGesturePhase.PRESSED && (position - start).getDistance() > touchSlop) {
            phase = CanvasGesturePhase.DRAGGING
        }
        return phase
    }

    fun cancel() { phase = CanvasGesturePhase.CANCELLED }

    fun finish(): CanvasGestureCompletion? {
        val result = when (phase) {
            CanvasGesturePhase.PRESSED -> CanvasGestureCompletion.Tap(position)
            CanvasGesturePhase.DRAGGING -> CanvasGestureCompletion.Drag(action, start, position)
            else -> null
        }
        phase = CanvasGesturePhase.FINISHED
        return result
    }
}

/**
 * [placing]: the Place tool's press adds a component. As in Evolution's AddTool, dragging moves the ghost and the
 * release places it; one finger no longer pans the view (two fingers still pan and zoom).
 */
internal fun canvasDragAction(
    mode: EditorMode, hit: CanvasHit, additive: Boolean, editWireStart: GridPoint? = null, placing: Boolean = false,
): CanvasDragAction = when {
    mode == EditorMode.PLACE && placing -> CanvasDragAction.PLACE
    (mode == EditorMode.EDIT || mode == EditorMode.WIRE) && editWireStart != null -> CanvasDragAction.DRAW_WIRE
    mode == EditorMode.MOVE && hit is CanvasHit.WireCorner && !additive -> CanvasDragAction.MOVE_WIRE_CORNER
    (mode == EditorMode.SELECT || mode == EditorMode.EDIT) && hit != CanvasHit.Empty -> CanvasDragAction.MOVE_SELECTION
    mode == EditorMode.SELECT || mode == EditorMode.EDIT -> CanvasDragAction.SELECT_AREA
    mode == EditorMode.MOVE && hit != CanvasHit.Empty -> CanvasDragAction.MOVE_SELECTION
    (mode == EditorMode.MOVE || mode == EditorMode.INTERACT) && additive -> CanvasDragAction.SELECT_AREA
    else -> CanvasDragAction.PAN
}

internal data class WireDragPreview(val from: GridPoint, val to: GridPoint, val verticalFirst: Boolean)

internal class WireDragDirection(private val start: GridPoint) {
    var verticalFirst = false
        private set

    fun update(position: GridPoint) {
        // A diagonal keeps the chosen axis. Returning to an axis or the start can change it.
        if (position.x == start.x) verticalFirst = position.y != start.y
        else if (position.y == start.y) verticalFirst = false
    }
}

internal fun fixedCanvasShortcut(key: Key): CanvasCommand? = when (key) {
    Key.Escape -> CanvasCommand.CANCEL
    Key.Delete, Key.Backspace -> CanvasCommand.DELETE
    else -> null
}

internal fun canvasPoint(point: Offset, snapToGrid: Boolean): GridPoint {
    val step = if (snapToGrid) 10 else 1
    return GridPoint((point.x / step).roundToInt() * step, (point.y / step).roundToInt() * step)
}

/** A fingertip reaches a pin from beside it; Evolution's mouse radius is about 8 px. */
internal const val CanvasTouchWireToleranceDp = 17f

/** The 22 dp halo around components and wires for a finger; pens and mice keep 8 dp. */
internal const val CanvasTouchHitToleranceDp = 22f

/**
 * World-unit radii for one press, from the pointer type, the density and the zoom. The mouse keeps Evolution's
 * geometry ([terminal] == [wire], [poke] == 0); touch and stylus reach component ports from about 17 dp away
 * (an Edit tool press only within its halo, [editStart]) and poke targets grow by the hit radius.
 */
internal data class CanvasPointerTolerance(
    /** Components and wires under the press. */
    val hit: Float,
    /** Wires (interiors and ends), and every connection point for the mouse. */
    val wire: Float,
    /** Component ports. */
    val terminal: Float,
    /** How far a poke target reaches beyond its exact geometry. */
    val poke: Float,
) {
    /**
     * Ports reached by an Edit tool press: [terminal], but never beyond the [hit] halo. A press that reaches a port
     * is then already on its component, so the reach never turns a rubber band start into a wire (a stylus keeps
     * the 8 dp halo, and so the 8 dp start reach, while its dragged wire ends still reach ports from 17 dp).
     */
    val editStart: Float get() = min(terminal, hit)
}

internal fun canvasPointerTolerance(type: PointerType, density: Float, scale: Float): CanvasPointerTolerance {
    val hit = (if (type == PointerType.Touch) CanvasTouchHitToleranceDp else 8f) * density / scale
    val wire = min(hit, 8f)
    if (type == PointerType.Mouse) return CanvasPointerTolerance(hit, wire, wire, 0f)
    return CanvasPointerTolerance(hit, wire, max(wire, CanvasTouchWireToleranceDp * density / scale), hit)
}

/**
 * The connection a wire drawn from or to [point] attaches to: the nearest port or wire point within [tolerance],
 * or a component port within [terminalTolerance]. Wire ends keep [tolerance]: a parallel wire one 10-unit pitch
 * away must neither retrace nor join them. With [terminalsInsideBodies] false (an Edit tool press), a port is
 * reached beyond [tolerance] only from outside every body and past the body edge the port lies on, so the Edit
 * tool still drags a small component pressed in or beside its body.
 *
 * For the end of a drag, [from] is its start. It is never a candidate, and a port beyond [tolerance] is reached
 * only when the release is nearer to it than to [from] and the port is not itself within [terminalTolerance] of
 * [from]; otherwise the end stays on the free snapped grid point (the caller's fallback). A short stub from a port
 * then keeps its own grid point even beside a neighbour port one pitch away, instead of shorting the two, while a
 * long drag still reaches a port from a fingertip away.
 *
 * Trade-off kept on purpose (R06-1): a port farther from [from] than the finger's reach still takes a release nearer
 * to it than to [from], even when the free grid point beside it was meant; for example an L-shaped wire from input 0
 * of a five-input AND that ends 10 units left of input 3 attaches to input 3 at 0.85 dp per unit. The snap ring
 * shows that attachment before the finger lifts, and the mouse keeps Evolution's 8-unit reach.
 */
internal fun CircuitDocument.editToolConnectionPoint(
    point: Offset, snapped: GridPoint, tolerance: Float, registry: ComponentRegistry,
    terminalTolerance: Float = tolerance, terminalsInsideBodies: Boolean = true, from: GridPoint? = null,
): GridPoint? {
    fun Offset.distanceTo(candidate: GridPoint) = (this - Offset(candidate.x.toFloat(), candidate.y.toFloat())).getDistance()
    val portReach = max(tolerance, terminalTolerance)
    val start = from?.let { Offset(it.x.toFloat(), it.y.toFloat()) }
    val startDistance = start?.let { (point - it).getDistance() } ?: Float.POSITIVE_INFINITY
    fun beyondStart(port: GridPoint, distance: Float) =
        start == null || distance < startDistance && start.distanceTo(port) > portReach
    val insideBody by lazy { components.any { it.bounds(registry).strictlyContains(point) } }
    val ports = components.asSequence().flatMap { component ->
        val bounds by lazy { component.bounds(registry) }
        (component.inputPorts(registry) + component.outputPorts(registry)).asSequence().map { port ->
            val distance = point.distanceTo(port)
            val reaches = (terminalsInsideBodies || !insideBody && bounds.isPastEdgeOf(port, point)) &&
                beyondStart(port, distance)
            Triple(port, distance, if (reaches) portReach else tolerance)
        }
    }
    val wirePoints = wires.asSequence().map { wire ->
        projectOntoWire(wire, snapped).let { Triple(it, point.distanceTo(it), tolerance) }
    }
    return (ports + wirePoints).filter { (candidate, distance, reach) -> candidate != from && distance <= reach }
        .minByOrNull { it.second }?.first
}

internal fun ComponentBounds.strictlyContains(point: Offset): Boolean =
    point.x > left && point.x < right && point.y > top && point.y < bottom

/** [point] lies outside these bounds, past an edge that [port] lies on (a corner port has two). */
private fun ComponentBounds.isPastEdgeOf(port: GridPoint, point: Offset): Boolean =
    port.x <= left && point.x < left || port.x >= right && point.x > right ||
        port.y <= top && point.y < top || port.y >= bottom && point.y > bottom

internal fun CircuitDocument.editToolWireStart(
    point: Offset, tolerance: Float, registry: ComponentRegistry, snapToGrid: Boolean,
    selectedWires: Set<WireSegment>, alt: Boolean, terminalTolerance: Float = tolerance,
): GridPoint? {
    val snapped = canvasPoint(point, snapToGrid)
    val connection = editToolConnectionPoint(point, snapped, tolerance, registry, terminalTolerance,
        terminalsInsideBodies = false)
    val overSelectedWireInterior = connection != null && selectedWires.any { wire ->
        wire.contains(connection) && connection != wire.from && connection != wire.to
    }
    val normallyWires = connection != null && !overSelectedWireInterior
    return if (normallyWires != alt) connection ?: snapped else null
}

/** The component nearest [point] within [tolerance] and its distance, 0 inside its bounds (topmost first). */
internal fun CircuitDocument.nearestComponent(
    point: Offset, tolerance: Float, registry: ComponentRegistry,
): Pair<CircuitComponent, Float>? =
    components.asReversed().map { component ->
        val bounds = component.bounds(registry)
        val dx = max(max(bounds.left - point.x, 0f), point.x - bounds.right)
        val dy = max(max(bounds.top - point.y, 0f), point.y - bounds.bottom)
        component to sqrt(dx * dx + dy * dy)
    }.filter { it.second <= tolerance }.minByOrNull { it.second }

/** The wire nearest [point] within [tolerance] and its distance. */
internal fun CircuitDocument.nearestWire(point: Offset, tolerance: Float): Pair<WireSegment, Float>? =
    wires.map { wire ->
        val closest = if (wire.from.x == wire.to.x) Offset(wire.from.x.toFloat(),
            point.y.coerceIn(min(wire.from.y, wire.to.y).toFloat(), max(wire.from.y, wire.to.y).toFloat()))
        else Offset(point.x.coerceIn(min(wire.from.x, wire.to.x).toFloat(), max(wire.from.x, wire.to.x).toFloat()), wire.from.y.toFloat())
        wire to (closest - point).getDistance()
    }.filter { it.second <= tolerance }.minByOrNull { it.second }

/**
 * What a press at [point] is on. Inside a component's bounds (ports lie on them) it is the topmost component, as
 * Evolution's getAllContaining. Beside components, within the halo, the nearer of a wire and a component wins,
 * the wire on a tie, so the wire running out of a small pin can still be poked, selected or split.
 */
internal fun CircuitDocument.hit(point: Offset, tolerance: Float, registry: ComponentRegistry): CanvasHit {
    val component = nearestComponent(point, tolerance, registry)
    if (component != null && component.second == 0f) return CanvasHit.Component(component.first.id)
    val wire = nearestWire(point, tolerance)
    return when {
        wire != null && (component == null || wire.second <= component.second) -> CanvasHit.Wire(wire.first)
        component != null -> CanvasHit.Component(component.first.id)
        else -> CanvasHit.Empty
    }
}

internal fun wireSplitPoint(wire: WireSegment, point: Offset, snapToGrid: Boolean): GridPoint? {
    if (!point.x.isFinite() || !point.y.isFinite()) return null
    return projectOntoWire(wire, canvasPoint(point, snapToGrid)).takeIf { it != wire.from && it != wire.to }
}

internal fun projectOntoWire(wire: WireSegment, point: GridPoint): GridPoint =
    if (wire.from.x == wire.to.x) GridPoint(wire.from.x,
        point.y.coerceIn(minOf(wire.from.y, wire.to.y), maxOf(wire.from.y, wire.to.y)))
    else GridPoint(point.x.coerceIn(minOf(wire.from.x, wire.to.x), maxOf(wire.from.x, wire.to.x)), wire.from.y)

/** Component terminals remain fixed; a handle edits routing rather than detaching a pin. */
internal fun wireHandlePoints(
    document: CircuitDocument,
    selectedWires: Set<WireSegment>,
    registry: ComponentRegistry = ComponentCatalog.registry,
): List<GridPoint> {
    val ports = document.components.flatMap { it.inputPorts(registry) + it.outputPorts(registry) }.toSet()
    return document.wires.filter { wire -> selectedWires.any {
        it == wire || (it.from == wire.to && it.to == wire.from)
    } }.flatMap { listOf(it.from, it.to) }.distinct().filterNot { it in ports }
        .sortedWith(compareBy<GridPoint> { it.y }.thenBy { it.x })
}

internal fun nearestWireHandle(points: List<GridPoint>, point: Offset, tolerance: Float): GridPoint? =
    points.map { it to (Offset(it.x.toFloat(), it.y.toFloat()) - point).getDistance() }
        .filter { it.second <= tolerance }.minByOrNull { it.second }?.first

internal fun movedWireHandle(from: GridPoint, start: Offset, current: Offset, snapToGrid: Boolean): GridPoint =
    canvasPoint(Offset(from.x.toFloat(), from.y.toFloat()) + current - start, snapToGrid)

/** Evolution's Canvas auto-pan: a 32 dp margin, steps of 4 to 16 dp, one step every 40 ms (C43). */
internal const val CanvasAutoPanMarginDp = 32f
internal const val CanvasAutoPanMinStepDp = 4f
internal const val CanvasAutoPanMaxStepDp = 16f
internal const val CanvasAutoPanFrameMillis = 40L
/** The most auto-pan steps one pass of the gesture loop applies after a stall. */
internal const val CanvasAutoPanMaxSteps = 3L

/**
 * Canvas.autoPanDelta along one axis: how far the view scrolls per tick for a pointer at [pointer] in a view of
 * [extent], 0 outside the [margin] at either end and from [minStep] up to [maxStep] deeper in it (negative toward the
 * start). A view too small to hold both margins never scrolls.
 */
internal fun autoPanDelta(pointer: Float, extent: Float, margin: Float, minStep: Float, maxStep: Float): Float {
    if (!pointer.isFinite() || extent <= margin * 2) return 0f
    fun step(depth: Float) = minStep + (maxStep - minStep) * depth.coerceIn(1f, margin) / margin
    if (pointer < margin) return -step(margin - pointer)
    val trailing = extent - margin
    if (pointer >= trailing) return step(pointer - trailing + 1f)
    return 0f
}

/** The pan change, in pixels, of one auto-pan tick for a drag at [position] on a [width] x [height] canvas. */
internal fun canvasAutoPanDelta(position: Offset, width: Float, height: Float, density: Float): Offset {
    val margin = CanvasAutoPanMarginDp * density
    val min = CanvasAutoPanMinStepDp * density
    val max = CanvasAutoPanMaxStepDp * density
    // Scrolling toward the right edge moves the circuit left: the pan goes the other way.
    return Offset(-autoPanDelta(position.x, width, margin, min, max), -autoPanDelta(position.y, height, margin, min, max))
}
