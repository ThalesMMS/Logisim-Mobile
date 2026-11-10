package dev.logisim.mobile.core

data class IoPointerAction(val press: IoInputEvent, val release: IoInputEvent? = null)

/** Converts circuit-space pointer coordinates into the same events used by the accessible panel. */
object IoInteractions {
    fun press(component: CircuitComponent, point: GridPoint): IoPointerAction? {
        val definition = IoLibrary.definition(component.kind) ?: return null
        val attributes = definition.schema.parse(component.attributes)
        val bounds = definition.layout(component, attributes).bounds
        if (point.x !in bounds.left..bounds.right || point.y !in bounds.top..bounds.bottom) return null
        val local = GridPoint(point.x - component.position.x, point.y - component.position.y)
        return when (component.kind) {
            IoKinds.Button -> IoPointerAction(IoInputEvent.Press(true), IoInputEvent.Press(false))
            IoKinds.Joystick -> IoPointerAction(joystick(local), IoInputEvent.Cancel)
            IoKinds.DipSwitch -> {
                val count = attributes[IoAttributes.Switches]
                val facing = attributes[IoAttributes.DipFacing]
                val canonical = if (facing == Direction.SOUTH) GridPoint(local.x + (count + 1) * 10, local.y + 40)
                    else IoGeometry.rotate(local, facing, Direction.NORTH)
                val index = (canonical.x - 5) / 10
                if (canonical.x < 5 || canonical.x >= count * 10 + 5 || canonical.y !in 0..40) null
                else IoPointerAction(IoInputEvent.ToggleSwitch(index))
            }
            IoKinds.MatrixKeypad -> {
                val scale = if (attributes[IoAttributes.KeypadSize] == "large") 2 else 1
                val x = point.x - bounds.left - 3 * scale
                val y = point.y - bounds.top - 3 * scale
                if (x < 0 || y < 0) return null
                val column = x / (14 * scale)
                val row = y / (14 * scale)
                if (row !in 0..3 || column !in 0..3 || x % (14 * scale) >= 12 * scale || y % (14 * scale) >= 12 * scale) null
                else IoPointerAction(IoInputEvent.KeypadKey(row, column, true), IoInputEvent.KeypadKey(row, column, false))
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
