package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalIoComponentsTest {
    private val clock = TestClock(86_400_123)
    private val definitions = ExternalIoComponents.definitions(clock)

    private fun evaluate(
        kind: ComponentKind,
        attributes: Map<String, String> = emptyMap(),
        inputs: Map<String, LogicVector> = emptyMap(),
        state: ComponentState? = null,
        events: List<ComponentEvent> = emptyList(),
        elapsed: Long = 0,
        automatic: Boolean = false,
    ): ComponentEvaluation {
        val definition = definitions.single { it.kind == kind }
        val component = CircuitComponent("test", kind, GridPoint(100, 200), attributes = attributes)
        val values = definition.schema.parse(attributes)
        val behavior = definition.behavior!!
        return behavior.evaluate(ComponentEvaluationContext(component, values, inputs, inputs.keys,
            state ?: behavior.initialState(component, values), elapsedTimeMillis = elapsed,
            events = events, autoPropagating = automatic))
    }

    @Test
    fun manualPortPinsCycleThroughFloatingLowAndHigh() {
        val attributes = mapOf("number" to "4", "direction" to "onlyinput")
        var result = evaluate(ExternalIoComponents.PortIo, attributes)
        assertEquals("????", result.outputs.getValue("pins").display)
        result = evaluate(ExternalIoComponents.PortIo, attributes, state = result.state,
            events = listOf(PortIoTogglePin(1)))
        assertEquals("??0?", result.outputs.getValue("pins").display)
        result = evaluate(ExternalIoComponents.PortIo, attributes, state = result.state,
            events = listOf(PortIoTogglePin(1)))
        assertEquals("??1?", result.outputs.getValue("pins").display)
        result = evaluate(ExternalIoComponents.PortIo, attributes, state = result.state,
            events = listOf(PortIoTogglePin(1)))
        assertEquals("????", result.outputs.getValue("pins").display)
    }

    @Test
    fun bidirectionalPortUsesTheEnableForEachBitAndReportsConflicts() {
        val attributes = mapOf("number" to "4", "direction" to "IOMultiEnable")
        val events = (0..3).map { PortIoSetPin(it, if (it % 2 == 0) LogicValue.HIGH else LogicValue.LOW) }
        val result = evaluate(ExternalIoComponents.PortIo, attributes,
            inputs = mapOf("data" to LogicVector.of(4, 10u), "enable" to LogicVector.of(4, 3u)), events = events)
        assertEquals("01EE", result.outputs.getValue("pins").display)
        val floatingExternal = evaluate(ExternalIoComponents.PortIo, attributes,
            inputs = mapOf("data" to LogicVector.of(4, 10u), "enable" to LogicVector.high(4)))
        assertEquals("1010", floatingExternal.outputs.getValue("pins").display)
    }

    @Test
    fun bidirectionalPortPreservesExternalPinsWhenEnableIsError() {
        val events = listOf(PortIoTogglePin(0), PortIoTogglePin(1), PortIoTogglePin(1))
        val data = LogicVector.high(3)
        val singleEnable = mapOf("number" to "3", "direction" to "IOSingleEnable")
        val multiEnable = mapOf("number" to "3", "direction" to "IOMultiEnable")
        val single = evaluate(ExternalIoComponents.PortIo, singleEnable,
            mapOf("data" to data, "enable" to LogicVector.error(1)), events = events)
        assertEquals("?10", single.outputs.getValue("pins").display)
        val multi = evaluate(ExternalIoComponents.PortIo, multiEnable,
            mapOf("data" to data, "enable" to LogicVector.fromBits(
                listOf(LogicValue.ERROR, LogicValue.UNKNOWN, LogicValue.HIGH))), events = events)
        assertEquals("1E0", multi.outputs.getValue("pins").display)
    }

    @Test
    fun outputOnlyPortObservesWithoutDrivingTheCircuit() {
        val result = evaluate(ExternalIoComponents.PortIo,
            mapOf("number" to "8", "direction" to "onlyOutput"),
            mapOf("data" to LogicVector.of(8, 0xA5u)))
        assertEquals(emptyMap(), result.outputs)
        assertEquals("10100101", (result.state as PortIoState).pins.display)
    }

    @Test
    fun portIoLayoutMatchesDesktopOrientationAndPinOrder() {
        val definition = definitions.single { it.kind == ExternalIoComponents.PortIo }
        val component = CircuitComponent("io", ExternalIoComponents.PortIo, GridPoint(100, 200),
            attributes = mapOf("number" to "8", "facing" to "north"))
        val layout = definition.layout(component, definition.schema.parse(component.attributes))
        assertEquals(listOf("enable", "data", "pins"), layout.ports.map { it.name })
        assertEquals(listOf(GridPoint(110, 200), GridPoint(100, 190), GridPoint(100, 180)), layout.ports.map { it.position })
        assertEquals(listOf(1, 8, 8), layout.ports.map { it.width })
        assertEquals(ComponentBounds(100, 150, 150, 200), layout.bounds)
        val multi = component.copy(attributes = component.attributes + ("direction" to "IOMultiEnable"))
        assertEquals(listOf(8, 8, 8), definition.layout(multi, definition.schema.parse(multi.attributes)).ports.map { it.width })
    }

    @Test
    fun videoWritesOnlyAtClockEdgesAndKeepsPreviousFramesImmutable() {
        val attributes = mapOf("width" to "3", "height" to "2", "color" to "888 RGB (24 bit)")
        val low = mapOf("clock" to LogicVector.low(1), "write" to LogicVector.low(1),
            "x" to LogicVector.of(2, 1u), "y" to LogicVector.of(1, 1u), "data" to LogicVector.of(24, 0x12ABCDu))
        val initial = evaluate(ExternalIoComponents.RgbVideo, attributes, low).state as VideoState
        val write = evaluate(ExternalIoComponents.RgbVideo, attributes,
            low + mapOf("clock" to LogicVector.high(1), "write" to LogicVector.high(1)), initial).state as VideoState
        assertEquals(0xFF12ABCD.toInt(), write.frame.argbAt(1, 1))
        assertEquals(0xFF000000.toInt(), initial.frame.argbAt(1, 1))
        val held = evaluate(ExternalIoComponents.RgbVideo, attributes,
            low + mapOf("clock" to LogicVector.high(1), "write" to LogicVector.high(1),
                "data" to LogicVector.of(24, 0xFFFFFFu)), write).state as VideoState
        assertEquals(0xFF12ABCD.toInt(), held.frame.argbAt(1, 1))
        assertEquals(0xFF000000.toInt(), held.frame.argbAt(0, 0))
    }

    @Test
    fun videoResizeWritesOnItsFirstEvaluationAndPreservesExistingPixels() {
        val previous = VideoState(VideoFrame(2, 2).withPixel(0, 0, 0xFF0000FF.toInt()), LogicValue.HIGH)
        val attributes = mapOf("width" to "3", "height" to "2", "color" to "888 RGB (24 bit)")
        val highWrite = mapOf("clock" to LogicVector.high(1), "write" to LogicVector.high(1),
            "x" to LogicVector.of(2, 2u), "y" to LogicVector.low(1), "data" to LogicVector.of(24, 0xFF0000u))

        val widened = evaluate(ExternalIoComponents.RgbVideo, attributes, highWrite, previous).state as VideoState
        assertEquals(0xFF0000FF.toInt(), widened.frame.argbAt(0, 0))
        assertEquals(0xFFFF0000.toInt(), widened.frame.argbAt(2, 0))

        val greenWrite = highWrite + ("data" to LogicVector.of(24, 0x00FF00u))
        val held = evaluate(ExternalIoComponents.RgbVideo, attributes, greenWrite, widened).state as VideoState
        assertEquals(0xFFFF0000.toInt(), held.frame.argbAt(2, 0))

        val taller = evaluate(ExternalIoComponents.RgbVideo, attributes + ("height" to "3"),
            greenWrite + ("y" to LogicVector.of(2, 2u)), held).state as VideoState
        assertEquals(0xFFFF0000.toInt(), taller.frame.argbAt(2, 0))
        assertEquals(0xFF00FF00.toInt(), taller.frame.argbAt(2, 2))
    }

    @Test
    fun videoSynchronousResetNeedsAWriteEdgeAndAsynchronousResetDoesNot() {
        val attributes = mapOf("width" to "2", "height" to "2", "reset" to "Synchronous")
        val previous = VideoState(VideoFrame(2, 2).withPixel(0, 0, -1), LogicValue.LOW)
        val resetOnly = mapOf("clock" to LogicVector.low(1), "reset" to LogicVector.high(1))
        val noEdge = evaluate(ExternalIoComponents.RgbVideo, attributes, resetOnly, previous).state as VideoState
        assertEquals(-1, noEdge.frame.argbAt(0, 0))
        val edgeWithoutWrite = evaluate(ExternalIoComponents.RgbVideo, attributes,
            resetOnly + ("clock" to LogicVector.high(1)), previous).state as VideoState
        assertEquals(-1, edgeWithoutWrite.frame.argbAt(0, 0))
        val writeEdge = evaluate(ExternalIoComponents.RgbVideo, attributes,
            resetOnly + mapOf("clock" to LogicVector.high(1), "write" to LogicVector.high(1)), previous).state as VideoState
        assertEquals(VideoFrame.BLACK, writeEdge.frame.argbAt(0, 0))
        val async = evaluate(ExternalIoComponents.RgbVideo, attributes + ("reset" to "Asynchronous"),
            resetOnly, previous).state as VideoState
        assertEquals(VideoFrame.BLACK, async.frame.argbAt(0, 0))
    }

    @Test
    fun videoColorModesAndResizePreserveTheirDefinedPixels() {
        assertEquals(0xFFFF0000.toInt(), VideoColorFormat.RGB565.argb(0xF800))
        assertEquals(0xFF00FF00.toInt(), VideoColorFormat.RGB555.argb(0x03E0))
        assertEquals(0xFF0000FF.toInt(), VideoColorFormat.RGB444.argb(0x00F))
        assertEquals(0xFFFFFF00.toInt(), VideoColorFormat.RGB111.argb(6))
        assertEquals(0xFF777777.toInt(), VideoColorFormat.GRAY4.argb(7))
        assertEquals(0xFF800000.toInt(), VideoColorFormat.XTERM16.argb(1))
        assertEquals(0xFF00005F.toInt(), VideoColorFormat.XTERM256.argb(17))
        assertEquals(0xFF0000AA.toInt(), VideoColorFormat.VGA256.argb(1))
        assertEquals(0xFF0A0A0A.toInt(), VideoColorFormat.ATARI.argb(1))
        val resized = VideoFrame(3, 2).withPixel(1, 1, 0xFF123456.toInt()).resize(2, 4)
        assertEquals(0xFF123456.toInt(), resized.argbAt(1, 1))
        assertEquals(VideoFrame.BLACK, resized.argbAt(1, 3))
        val pixels = resized.copyArgbPixels()
        pixels[3] = 0
        assertEquals(0xFF123456.toInt(), resized.argbAt(1, 1))
    }

    @Test
    fun videoLayoutAndAttributesUseDesktopLimits() {
        val definition = definitions.single { it.kind == ExternalIoComponents.RgbVideo }
        val component = CircuitComponent("video", ExternalIoComponents.RgbVideo, GridPoint(100, 200))
        val layout = definition.layout(component, definition.schema.parse(emptyMap()))
        assertEquals(listOf(1, 1, 1, 7, 7, 24), layout.ports.map { it.width })
        assertEquals(ComponentBounds(70, -70, 340, 200), layout.bounds)
        assertFailsWith<IllegalArgumentException> { definition.schema.parse(mapOf("width" to "1")) }
        assertFailsWith<IllegalArgumentException> { definition.schema.parse(mapOf("height" to "4097")) }
        assertFailsWith<IllegalArgumentException> { definition.schema.parse(mapOf("color" to "unknown")) }
    }

    @Test
    fun realTimeClockIsInjectableAndSchedulesOnlyWhileAutomatic() {
        val millis = evaluate(ExternalIoComponents.RealTimeClock)
        assertEquals(86_400_123uL, millis.outputs.getValue("time").toULongOrNull())
        assertEquals(null, millis.nextWakeupMillis)
        clock.millis = 1_999
        val seconds = evaluate(ExternalIoComponents.RealTimeClock, mapOf("timeType" to "2"), elapsed = 50, automatic = true)
        assertEquals(1uL, seconds.outputs.getValue("time").toULongOrNull())
        assertEquals(60, seconds.nextWakeupMillis)
        clock.millis = -1
        assertEquals(ULong.MAX_VALUE, evaluate(ExternalIoComponents.RealTimeClock,
            mapOf("timeType" to "2")).outputs.getValue("time").toULongOrNull())
    }

    @Test
    fun realTimeClockSamplesOncePerOuterTransactionToKeepDeltaSettlingDeterministic() {
        clock.millis = 1000
        val first = evaluate(ExternalIoComponents.RealTimeClock, elapsed = 10)
        clock.millis = 1001
        val sameTransaction = evaluate(ExternalIoComponents.RealTimeClock, state = first.state, elapsed = 10)
        assertEquals(1000uL, sameTransaction.outputs.getValue("time").toULongOrNull())
        val nextTransaction = evaluate(ExternalIoComponents.RealTimeClock, state = sameTransaction.state, elapsed = 20)
        assertEquals(1001uL, nextTransaction.outputs.getValue("time").toULongOrNull())
    }

    @Test
    fun reptarPreservesElectricalInterfaceButDoesNotInventSimulation() {
        val result = evaluate(ExternalIoComponents.ReptarLocalBus)
        assertEquals(6, result.outputs.size)
        assertTrue(result.outputs.values.all { it.isUnknown })
        assertTrue(result.diagnostics.single().contains("não possui simulação"))
        assertFalse(result.diagnostics.single().isBlank())
    }

    private class TestClock(var millis: Long) : EpochClock {
        override fun epochMillis(): Long = millis
    }
}
