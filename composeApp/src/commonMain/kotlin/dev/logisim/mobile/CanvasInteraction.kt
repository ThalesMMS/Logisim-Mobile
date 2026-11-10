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
import dev.logisim.mobile.core.inputPorts
import dev.logisim.mobile.core.outputPorts
import kotlin.math.roundToInt

internal enum class CanvasCommand { COPY, CUT, PASTE, DUPLICATE, DELETE, SELECT_ALL, UNDO, REDO, CANCEL, FIT_SELECTION }
internal enum class CanvasDragAction { PAN, MOVE_SELECTION, SELECT_AREA, MOVE_WIRE_CORNER, DRAW_WIRE }
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

internal fun canvasDragAction(
    mode: EditorMode, hit: CanvasHit, additive: Boolean, editWireStart: GridPoint? = null,
): CanvasDragAction = when {
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

internal fun CircuitDocument.editToolConnectionPoint(
    point: Offset, snapped: GridPoint, tolerance: Float, registry: ComponentRegistry,
): GridPoint? {
    val terminals = components.asSequence().flatMap { (it.inputPorts(registry) + it.outputPorts(registry)).asSequence() }
    val wirePoints = wires.asSequence().map { wire ->
        if (wire.from.x == wire.to.x) GridPoint(wire.from.x,
            snapped.y.coerceIn(minOf(wire.from.y, wire.to.y), maxOf(wire.from.y, wire.to.y)))
        else GridPoint(snapped.x.coerceIn(minOf(wire.from.x, wire.to.x), maxOf(wire.from.x, wire.to.x)), wire.from.y)
    }
    return (terminals + wirePoints).map { candidate ->
        candidate to (point - Offset(candidate.x.toFloat(), candidate.y.toFloat())).getDistance()
    }.filter { it.second <= tolerance }.minByOrNull { it.second }?.first
}

internal fun CircuitDocument.editToolWireStart(
    point: Offset, tolerance: Float, registry: ComponentRegistry, snapToGrid: Boolean,
    selectedWires: Set<WireSegment>, alt: Boolean,
): GridPoint? {
    val snapped = canvasPoint(point, snapToGrid)
    val connection = editToolConnectionPoint(point, snapped, tolerance, registry)
    val overSelectedWireInterior = connection != null && selectedWires.any { wire ->
        wire.contains(connection) && connection != wire.from && connection != wire.to
    }
    val normallyWires = connection != null && !overSelectedWireInterior
    return if (normallyWires != alt) connection ?: snapped else null
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
