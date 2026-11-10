package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentBounds
import dev.logisim.mobile.core.ExternalIoComponents
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.PortIoTogglePin
import dev.logisim.mobile.core.RealTimeClockState
import dev.logisim.mobile.core.TelnetLinkState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalIoRendererTest {
    private fun port(facing: String = "east", number: Int = 8) = CircuitComponent("port", ExternalIoComponents.PortIo,
        GridPoint(100, 200), attributes = mapOf("facing" to facing, "number" to number.toString()))

    @Test
    fun portPinRectanglesMatchDesktopCoordinatesInEveryFacing() {
        assertEquals(ComponentBounds(107, 225, 113, 231), externalIoPinBounds(port(), 0))
        assertEquals(ComponentBounds(107, 235, 113, 241), externalIoPinBounds(port(), 1))
        assertEquals(ComponentBounds(117, 225, 123, 231), externalIoPinBounds(port(), 2))
        assertEquals(ComponentBounds(87, 169, 93, 175), externalIoPinBounds(port("west"), 0))
        assertEquals(ComponentBounds(125, 187, 131, 193), externalIoPinBounds(port("north"), 0))
        assertEquals(ComponentBounds(69, 207, 75, 213), externalIoPinBounds(port("south"), 0))
    }

    @Test
    fun onlyVisiblePinsGenerateTogglesForAllRotations() {
        assertEquals(PortIoTogglePin(0), externalIoEventAt(port(), GridPoint(110, 230)))
        assertEquals(PortIoTogglePin(1), externalIoEventAt(port(), GridPoint(110, 240)))
        assertEquals(PortIoTogglePin(2), externalIoEventAt(port(), GridPoint(120, 230)))
        assertEquals(PortIoTogglePin(0), externalIoEventAt(port("west"), GridPoint(90, 170)))
        assertEquals(PortIoTogglePin(0), externalIoEventAt(port("north"), GridPoint(130, 190)))
        assertEquals(PortIoTogglePin(0), externalIoEventAt(port("south"), GridPoint(70, 210)))
        assertNull(externalIoEventAt(port(), GridPoint(113, 230)))
        assertNull(externalIoEventAt(port(), GridPoint(110, 231)))
        assertNull(externalIoEventAt(port(), GridPoint(110, 220)))
        assertNull(externalIoEventAt(port(number = 1), GridPoint(110, 240)))
        assertNull(externalIoEventAt(port(number = 1), GridPoint(120, 230)))
        assertNull(externalIoEventAt(port().copy(kind = ExternalIoComponents.Telnet), GridPoint(110, 230)))
    }

    @Test
    fun maximumPinCountKeepsTheLastPinInteractive() {
        assertEquals(64, externalIoPinCount(port(number = 64)))
        assertEquals(PortIoTogglePin(63), externalIoEventAt(port(number = 64), GridPoint(420, 240)))
        assertNull(externalIoEventAt(port(number = 63), GridPoint(420, 240)))
    }

    @Test
    fun videoPixelsUseDesktopInsetScaleAndMinimumBodyWidth() {
        val component = CircuitComponent("video", ExternalIoComponents.RgbVideo, GridPoint(100, 200),
            attributes = mapOf("width" to "3", "height" to "2", "scale" to "4"))
        val geometry = externalVideoGeometry(component)
        assertEquals(Rect(70f, 178f, 170f, 200f), geometry.bounds)
        assertEquals(Offset(77f, 185f), geometry.imageOrigin)
        assertEquals(Rect(81f, 189f, 89f, 193f), geometry.pixelBounds(1, 1, length = 2))
    }

    @Test
    fun rtcHandsUseTheCapturedSampleIncludingTimesBeforeTheEpoch() {
        val midnight = externalClockHands(RealTimeClockState(10, 50, 0), 20f)
        assertEquals(0f, midnight.hour.x, .0001f)
        assertEquals(-4.8f, midnight.hour.y, .0001f)
        assertEquals(-6.4f, midnight.minute.y, .0001f)
        val three = externalClockHands(RealTimeClockState(10, 50, 10_800_000), 20f)
        assertEquals(4.8f, three.hour.x, .0001f)
        assertEquals(0f, three.hour.y, .0001f)
        assertEquals(three, externalClockHands(RealTimeClockState(1000, 9000, 10_800_000), 20f))
        val negative = externalClockHands(RealTimeClockState(0, 0, -1), 20f)
        assertTrue(negative.minute.x < 0)
    }

    @Test
    fun portAndTelnetIndicatorsPreserveUnknownErrorAndConnectionState() {
        assertEquals(0xffc0c0c0.toInt(), externalPinColor(LogicValue.UNKNOWN).toArgb())
        assertEquals(0xff006400.toInt(), externalPinColor(LogicValue.LOW).toArgb())
        assertEquals(0xff00d200.toInt(), externalPinColor(LogicValue.HIGH).toArgb())
        assertEquals(0xffd62d24.toInt(), externalPinColor(LogicValue.ERROR).toArgb())
        assertEquals(0xff7a7a7a.toInt(), externalTelnetIndicator(TelnetLinkState.Stopped).toArgb())
        assertEquals(0xffd59100.toInt(), externalTelnetIndicator(TelnetLinkState.Listening).toArgb())
        assertEquals(0xff00b000.toInt(), externalTelnetIndicator(TelnetLinkState.Connected).toArgb())
        assertEquals(0xffd62d24.toInt(), externalTelnetIndicator(TelnetLinkState.Failed("provider")).toArgb())
    }
}
