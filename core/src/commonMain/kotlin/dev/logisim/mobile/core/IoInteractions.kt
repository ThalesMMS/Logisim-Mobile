package dev.logisim.mobile.core

data class IoPointerAction(val press: IoInputEvent, val release: IoInputEvent? = null)

/** Converts circuit-space pointer coordinates into the same events used by the accessible panel. */
object IoInteractions {
    /**
     * The pointer action at [point]. [tolerance] (circuit units, 0 for the mouse) lets a fingertip reach the nearest
     * DIP switch or keypad key from the gaps and margins around them; the other kinds keep their exact bounds.
     */
    fun press(component: CircuitComponent, point: GridPoint, tolerance: Int = 0): IoPointerAction? {
        val definition = IoLibrary.definition(component.kind) ?: return null
        val attributes = definition.schema.parse(component.attributes)
        val bounds = definition.layout(component, attributes).bounds
        val reach = if (component.kind == IoKinds.DipSwitch || component.kind == IoKinds.MatrixKeypad) tolerance.coerceAtLeast(0) else 0
        if (point.x !in bounds.left - reach..bounds.right + reach || point.y !in bounds.top - reach..bounds.bottom + reach) return null
        val local = GridPoint(point.x - component.position.x, point.y - component.position.y)
        return when (component.kind) {
            IoKinds.Button -> IoPointerAction(IoInputEvent.Press(true), IoInputEvent.Press(false))
            IoKinds.Joystick -> IoPointerAction(joystick(local), IoInputEvent.Cancel)
            IoKinds.DipSwitch -> {
                val count = attributes[IoAttributes.Switches]
                val facing = attributes[IoAttributes.DipFacing]
                val canonical = if (facing == Direction.SOUTH) GridPoint(local.x + (count + 1) * 10, local.y + 40)
                    else IoGeometry.rotate(local, facing, Direction.NORTH)
                // The switches are contiguous, 10 units each from x = 5; beyond the ends the nearest one is within reach.
                val last = count * 10 + 4
                if (canonical.x < 5 - reach || canonical.x > last + reach || canonical.y !in -reach..40 + reach) null
                else IoPointerAction(IoInputEvent.ToggleSwitch((canonical.x.coerceIn(5, last) - 5) / 10))
            }
            IoKinds.MatrixKeypad -> {
                val scale = if (attributes[IoAttributes.KeypadSize] == "large") 2 else 1
                val x = point.x - bounds.left - 3 * scale
                val y = point.y - bounds.top - 3 * scale
                val pitch = 14 * scale
                val key = 12 * scale
                if (reach == 0) {
                    if (x < 0 || y < 0) return null
                    val column = x / pitch
                    val row = y / pitch
                    if (row !in 0..3 || column !in 0..3 || x % pitch >= key || y % pitch >= key) null
                    else IoPointerAction(IoInputEvent.KeypadKey(row, column, true), IoInputEvent.KeypadKey(row, column, false))
                } else {
                    // The nearest key: the 2-unit gaps split halfway, the margins belong to the edge keys.
                    fun nearest(offset: Int) = (offset + (pitch - key) / 2).floorDiv(pitch).coerceIn(0, 3)
                    fun gap(offset: Int, index: Int) = maxOf(index * pitch - offset, offset - (index * pitch + key - 1), 0)
                    val column = nearest(x)
                    val row = nearest(y)
                    if (gap(x, column) > reach || gap(y, row) > reach) null
                    else IoPointerAction(IoInputEvent.KeypadKey(row, column, true), IoInputEvent.KeypadKey(row, column, false))
                }
            }
            else -> null
        }
    }

    fun drag(component: CircuitComponent, point: GridPoint): IoInputEvent? =
        if (component.kind == IoKinds.Joystick) joystick(GridPoint(point.x - component.position.x, point.y - component.position.y))
        else null

    private fun joystick(local: GridPoint): IoInputEvent.MoveJoystick =
        IoInputEvent.MoveJoystick((local.x + 15).coerceIn(-14, 14), (local.y - 5).coerceIn(-14, 14))
}
