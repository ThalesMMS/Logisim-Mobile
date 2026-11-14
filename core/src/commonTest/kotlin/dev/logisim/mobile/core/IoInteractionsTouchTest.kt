package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A fingertip reaches the nearest DIP switch or keypad key; the mouse keeps the exact geometry. */
class IoInteractionsTouchTest {
    private fun component(kind: ComponentKind, attributes: Map<String, String> = emptyMap()) =
        CircuitComponent("io", kind, GridPoint(200, 200), attributes = attributes)

    @Test
    fun aFingertipReachesTheNearestKeypadKeyFromAGapOrMargin() {
        val keypad = component(IoKinds.MatrixKeypad)
        fun key(row: Int, column: Int) = IoPointerAction(IoInputEvent.KeypadKey(row, column, true),
            IoInputEvent.KeypadKey(row, column, false))
        // x = 185 is the first unit of the 2-unit gap after key (0, 0), x = 186 the second.
        assertNull(IoInteractions.press(keypad, GridPoint(185, 180)))
        assertEquals(key(0, 0), IoInteractions.press(keypad, GridPoint(185, 180), tolerance = 20))
        assertNull(IoInteractions.press(keypad, GridPoint(186, 180)))
        assertEquals(key(0, 1), IoInteractions.press(keypad, GridPoint(186, 180), tolerance = 20))
        // The margin left of the first column belongs to it.
        assertNull(IoInteractions.press(keypad, GridPoint(171, 180)))
        assertEquals(key(0, 0), IoInteractions.press(keypad, GridPoint(171, 180), tolerance = 20))
        // On a key the result is the key either way.
        assertEquals(key(0, 0), IoInteractions.press(keypad, GridPoint(180, 180)))
        assertEquals(key(0, 0), IoInteractions.press(keypad, GridPoint(180, 180), tolerance = 20))
        assertNull(IoInteractions.press(keypad, GridPoint(120, 180), tolerance = 20))
    }

    @Test
    fun aFingertipReachesTheEndSwitchesOfADipSwitch() {
        // Facing north at (200, 200): switch i covers x 205 + 10i..214 + 10i and y 200..240.
        val dip = component(IoKinds.DipSwitch, mapOf("facing" to "north", "number" to "8"))
        assertEquals(IoInputEvent.ToggleSwitch(2), IoInteractions.press(dip, GridPoint(229, 220))?.press)
        assertEquals(IoInputEvent.ToggleSwitch(2), IoInteractions.press(dip, GridPoint(229, 220), tolerance = 20)?.press)
        assertNull(IoInteractions.press(dip, GridPoint(202, 220)))
        assertEquals(IoInputEvent.ToggleSwitch(0), IoInteractions.press(dip, GridPoint(202, 220), tolerance = 20)?.press)
        assertNull(IoInteractions.press(dip, GridPoint(296, 220)))
        assertEquals(IoInputEvent.ToggleSwitch(7), IoInteractions.press(dip, GridPoint(296, 220), tolerance = 20)?.press)
        assertNull(IoInteractions.press(dip, GridPoint(250, 245)))
        assertEquals(IoInputEvent.ToggleSwitch(4), IoInteractions.press(dip, GridPoint(250, 245), tolerance = 20)?.press)
        assertNull(IoInteractions.press(dip, GridPoint(250, 270), tolerance = 20))
    }

    @Test
    fun buttonsAndJoysticksKeepTheirExactBounds() {
        val button = component(IoKinds.Button)
        assertEquals(IoInputEvent.Press(true), IoInteractions.press(button, GridPoint(190, 200), tolerance = 20)?.press)
        assertNull(IoInteractions.press(button, GridPoint(205, 200), tolerance = 20))
    }
}
