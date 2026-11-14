package dev.logisim.mobile.core

import kotlin.time.Clock
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.message

fun interface EpochClock {
    fun epochMillis(): Long
}

object SystemEpochClock : EpochClock {
    override fun epochMillis(): Long = Clock.System.now().toEpochMilliseconds()
}

data class PortIoSetPin(val index: Int, val value: LogicValue) : ComponentEvent
data class PortIoTogglePin(val index: Int) : ComponentEvent

data class PortIoState(
    val pins: LogicVector,
    val externalInputs: LogicVector,
) : ComponentState

data class RealTimeClockState(
    val sampleTick: Long,
    val sampleElapsedMillis: Long,
    val epochMillis: Long,
) : ComponentState

/** Pure local I/O components. Network resources are owned by an explicit TelnetSession. */
object ExternalIoComponents {
    val PortIo = ComponentKind("PORT_IO")
    val RgbVideo = ComponentKind("RGB_VIDEO")
    val RealTimeClock = ComponentKind("REAL_TIME_CLOCK")
    val Telnet = ComponentKind("TELNET")
    val ReptarLocalBus = ComponentKind("REPTAR_LOCAL_BUS")

    internal val Number = AttributeKey.int("number", 8, 1..64).labeled("attribute_label_pin_count")
    internal val PortMode = AttributeKey.choice("direction", "IOSingleEnable",
        setOf("onlyinput", "onlyOutput", "IOSingleEnable", "IOMultiEnable")).labeled("attribute_label_port_type")
    internal val VideoWidth = AttributeKey.int("width", 128, 2..4096).labeled("attribute_label_pixel_width")
    internal val VideoHeight = AttributeKey.int("height", 128, 2..4096)
    internal val VideoScale = AttributeKey.int("scale", 2, 1..8)
    internal val VideoColor = AttributeKey.choice("color", "888 RGB (24 bit)",
        VideoColorFormat.entries.map { it.fileValue }.toSet()).labeled("attribute_label_color_model")
    internal val VideoReset = AttributeKey.choice("reset", "Asynchronous",
        setOf("Asynchronous", "Synchronous"))
    internal val VideoCursor = AttributeKey.choice("cursor", "Blinking Dot",
        setOf("Blinking Dot", "No Cursor"))
    internal val ClockType = AttributeKey.choice("timeType", "1", setOf("1", "2"))
    internal val ClockSize = AttributeKey.choice("porsize", "1", setOf("1", "2"))
    internal val TelnetPort = AttributeKey.int("port", 8000, 1..65535)
    internal val TelnetBuffer = AttributeKey.int("buflen", 1024, 1..16_777_216)
    internal val TelnetMode = AttributeKey.boolean("telnetMode", false)
    internal val Trigger = AttributeKey.choice("trigger", "rising", setOf("rising", "falling"))

    fun definitions(clock: EpochClock = SystemEpochClock): List<ComponentDefinition> = listOf(
        ComponentDefinition(PortIo, "PortIO", "#I/O",
            schema = AttributeSchema(listOf(Number, PortMode, CommonAttributes.Facing, CommonAttributes.Label)),
            layout = ::portIoLayout, behavior = PortIoBehavior),
        ComponentDefinition(RgbVideo, "RGB Video", "#I/O",
            schema = AttributeSchema(listOf(VideoWidth, VideoHeight, VideoScale, VideoColor, VideoReset, VideoCursor)),
            layout = ::videoLayout, behavior = VideoBehavior(clock), supportsRotation = false),
        ComponentDefinition(RealTimeClock, "RealTimeClock", "#I/O",
            schema = AttributeSchema(listOf(ClockType, ClockSize, CommonAttributes.Facing)),
            layout = ::clockLayout, behavior = ClockBehavior(clock)),
        ComponentDefinition(Telnet, "Telnet", "#I/O",
            schema = AttributeSchema(listOf(TelnetPort, TelnetBuffer, TelnetMode, Trigger, CommonAttributes.Label)),
            layout = ::telnetLayout, behavior = TelnetBehavior, supportsRotation = false),
        ComponentDefinition(ReptarLocalBus, "ReptarLB", "#I/O",
            defaultAttributes = mapOf("label" to "LocalBus"),
            schema = AttributeSchema(listOf(CommonAttributes.Label)),
            layout = ::reptarLayout, behavior = ReptarBehavior, supportsRotation = false),
    )

    fun isVideoCursorVisible(epochMillis: Long): Boolean = epochMillis / 1_000L % 2L == 0L

    private fun portIoLayout(component: CircuitComponent, values: AttributeValues): ComponentLayout {
        val width = values[Number]
        val direction = values[PortMode]
        val ports = when (direction) {
            "onlyinput" -> listOf(terminal(component, "pins", 10, 0, dev.logisim.mobile.core.PortDirection.OUTPUT, width))
            "onlyOutput" -> listOf(terminal(component, "data", 10, 0, dev.logisim.mobile.core.PortDirection.INPUT, width))
            "IOSingleEnable" -> listOf(
                terminal(component, "enable", 0, 10, dev.logisim.mobile.core.PortDirection.INPUT),
                terminal(component, "data", 10, 0, dev.logisim.mobile.core.PortDirection.INPUT, width),
                terminal(component, "pins", 20, 0, dev.logisim.mobile.core.PortDirection.OUTPUT, width))
            else -> listOf(
                terminal(component, "enable", 0, 10, dev.logisim.mobile.core.PortDirection.INPUT, width),
                terminal(component, "data", 10, 0, dev.logisim.mobile.core.PortDirection.INPUT, width),
                terminal(component, "pins", 20, 0, dev.logisim.mobile.core.PortDirection.OUTPUT, width))
        }
        return ComponentLayout(ports, rotatedBounds(component, 0, 0, 10 + (maxOf(8, width) + 1) / 2 * 10, 50))
    }

    private fun videoLayout(component: CircuitComponent, values: AttributeValues): ComponentLayout {
        val width = values[VideoWidth]
        val height = values[VideoHeight]
        val boxWidth = maxOf(values[VideoScale] * width + 14, 100)
        val boxHeight = maxOf(values[VideoScale] * height + 14, 20)
        fun port(name: String, x: Int, bits: Int = 1) = ComponentTerminal(name,
            GridPoint(component.position.x + x, component.position.y), dev.logisim.mobile.core.PortDirection.INPUT, bits)
        val format = VideoColorFormat.fromFileValue(values[VideoColor])
        return ComponentLayout(listOf(port("reset", 0), port("clock", 10), port("write", 20),
            port("x", 40, addressBits(width)), port("y", 50, addressBits(height)), port("data", 60, format.bits)),
            ComponentBounds(component.position.x - 30, component.position.y - boxHeight,
                component.position.x - 30 + boxWidth, component.position.y))
    }

    private fun clockLayout(component: CircuitComponent, values: AttributeValues): ComponentLayout {
        val size = if (values[ClockSize] == "1") 40 else 20
        // Desktop clock bounds are defined facing west, unlike most wiring components.
        val boundsComponent = component.copy(attributes = component.attributes + ("facing" to when (component.facing) {
            "east" -> "west"; "west" -> "east"; "north" -> "south"; else -> "north"
        }))
        return ComponentLayout(listOf(ComponentTerminal("time", component.position,
            dev.logisim.mobile.core.PortDirection.OUTPUT, 64)), rotatedBounds(boundsComponent, 0, -size / 2, size, size))
    }

    private fun telnetLayout(component: CircuitComponent, values: AttributeValues): ComponentLayout {
        fun port(name: String, x: Int, y: Int, direction: dev.logisim.mobile.core.PortDirection, width: Int = 1) =
            ComponentTerminal(name, GridPoint(component.position.x + x, component.position.y + y), direction, width)
        return ComponentLayout(listOf(
            port("dataIn", -30, 10, dev.logisim.mobile.core.PortDirection.INPUT, 8),
            port("dataOut", 10, 10, dev.logisim.mobile.core.PortDirection.OUTPUT, 8),
            port("clock", -30, 20, dev.logisim.mobile.core.PortDirection.INPUT),
            port("write", -30, 30, dev.logisim.mobile.core.PortDirection.INPUT),
            port("read", 10, 30, dev.logisim.mobile.core.PortDirection.INPUT),
            port("available", 10, 20, dev.logisim.mobile.core.PortDirection.OUTPUT)),
            ComponentBounds(component.position.x - 30, component.position.y - 20,
                component.position.x + 10, component.position.y + 40))
    }

    private fun reptarLayout(component: CircuitComponent, values: AttributeValues): ComponentLayout {
        val names = listOf("chipSelect", "addressLatch", "outputEnable", "writeEnable", "wait", "dataOut",
            "dataIn", "dataTristate", "address", "irq")
        val input = setOf(4, 6, 7, 9)
        return ComponentLayout(names.mapIndexed { index, name -> ComponentTerminal(name,
            GridPoint(component.position.x, component.position.y + index * 10),
            if (index in input) dev.logisim.mobile.core.PortDirection.INPUT else dev.logisim.mobile.core.PortDirection.OUTPUT,
            when (index) { 5, 6 -> 16; 8 -> 9; else -> 1 }) },
            ComponentBounds(component.position.x - 110, component.position.y - 10,
                component.position.x, component.position.y + 100))
    }

    private fun terminal(component: CircuitComponent, name: String, x: Int, y: Int,
                         direction: dev.logisim.mobile.core.PortDirection, width: Int = 1) =
        ComponentTerminal(name, component.relativePoint(GridPoint(x, y)), direction, width)

    private fun rotatedBounds(component: CircuitComponent, x: Int, y: Int, width: Int, height: Int): ComponentBounds {
        val points = listOf(GridPoint(x, y), GridPoint(x + width, y), GridPoint(x, y + height),
            GridPoint(x + width, y + height)).map(component::relativePoint)
        return ComponentBounds(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }

    private fun addressBits(size: Int): Int = (32 - (size - 1).countLeadingZeroBits()).coerceAtLeast(1)

    private object PortIoBehavior : ComponentBehavior {
        override fun initialState(component: CircuitComponent, attributes: AttributeValues): ComponentState =
            PortIoState(LogicVector.unknown(attributes[Number]), LogicVector.unknown(attributes[Number]))

        override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
            val width = context.attributes[Number]
            val previous = context.state as? PortIoState
                ?: initialState(context.component, context.attributes) as PortIoState
            var external = previous.externalInputs.resize(width, LogicValue.UNKNOWN)
            for (event in context.events) {
                when (event) {
                    is PortIoSetPin -> if (event.index in 0 until width) external = external.withBit(event.index, event.value)
                    is PortIoTogglePin -> if (event.index in 0 until width) external = external.withBit(event.index,
                        when (external.bit(event.index)) { LogicValue.UNKNOWN, LogicValue.ERROR -> LogicValue.LOW
                            LogicValue.LOW -> LogicValue.HIGH; LogicValue.HIGH -> LogicValue.UNKNOWN })
                }
            }
            val direction = context.attributes[PortMode]
            val data = context.inputs["data"] ?: LogicVector.unknown(width)
            val enabled = context.inputs["enable"] ?: LogicVector.unknown(if (direction == "IOSingleEnable") 1 else width)
            val pins = when (direction) {
                "onlyinput" -> external
                "onlyOutput" -> data
                else -> LogicVector.fromBits((0 until width).map { bit ->
                    when (enabled.bit(if (direction == "IOSingleEnable") 0 else bit)) {
                        LogicValue.LOW, LogicValue.ERROR -> external.bit(bit)
                        LogicValue.HIGH -> if (external.bit(bit) == LogicValue.UNKNOWN || external.bit(bit) == data.bit(bit))
                            data.bit(bit) else LogicValue.ERROR
                        LogicValue.UNKNOWN -> LogicValue.ERROR
                    }
                })
            }
            val state = PortIoState(pins, external)
            return ComponentEvaluation(if (direction == "onlyOutput") emptyMap() else mapOf("pins" to pins), state)
        }
    }

    private class VideoBehavior(private val epochClock: EpochClock) : ComponentBehavior {
        override fun initialState(component: CircuitComponent, attributes: AttributeValues): ComponentState =
            VideoState(VideoFrame(attributes[VideoWidth], attributes[VideoHeight]))

        override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
            val width = context.attributes[VideoWidth]
            val height = context.attributes[VideoHeight]
            val previous = context.state as? VideoState ?: VideoState(VideoFrame(width, height))
            val clock = context.inputs["clock"]?.bit(0) ?: LogicValue.UNKNOWN
            val resized = previous.frame.width != width || previous.frame.height != height
            // Evolution recreates the clock state when it resizes the framebuffer.
            val rising = resized || previous.lastClock == null || previous.lastClock == LogicValue.LOW && clock == LogicValue.HIGH
            val write = context.inputs["write"]?.bit(0) == LogicValue.HIGH
            val reset = context.inputs["reset"]?.bit(0) == LogicValue.HIGH
            val x = context.inputs["x"]?.toULongOrNull()?.toLong() ?: -1
            val y = context.inputs["y"]?.toULongOrNull()?.toLong() ?: -1
            val color = context.inputs["data"]?.toULongOrNull()?.toInt() ?: -1
            var frame = previous.frame.resize(width, height)
            if (rising && write) {
                if (x in 0 until width.toLong() && y in 0 until height.toLong()) {
                    frame = frame.withPixel(x.toInt(), y.toInt(), VideoColorFormat.fromFileValue(context.attributes[VideoColor]).argb(color))
                }
                if (reset && context.attributes[VideoReset] == "Synchronous") frame = frame.clear()
            }
            if (reset && context.attributes[VideoReset] == "Asynchronous") frame = frame.clear()
            val cursorEnabled = context.attributes[VideoCursor] == "Blinking Dot"
            val epochMillis = if (cursorEnabled) epochClock.epochMillis() else 0L
            val cursorVisible = cursorEnabled && isVideoCursorVisible(epochMillis)
            val millisInSecond = epochMillis % 1_000L
            val untilPhaseChange = if (epochMillis <= -1_000L) 1L - millisInSecond else 1_000L - millisInSecond
            val nextBlink = if (cursorEnabled && context.autoPropagating &&
                context.elapsedTimeMillis <= Long.MAX_VALUE - untilPhaseChange
            ) context.elapsedTimeMillis + untilPhaseChange else null
            return ComponentEvaluation(emptyMap(), VideoState(frame, clock, x.toInt(), y.toInt(), color, cursorVisible),
                delay = 0, nextWakeupMillis = nextBlink)
        }
    }

    private class ClockBehavior(private val clock: EpochClock) : ComponentBehavior {
        override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
            val previous = context.state as? RealTimeClockState
            val sample = if (previous?.sampleTick == context.ticks && previous.sampleElapsedMillis == context.elapsedTimeMillis)
                previous else RealTimeClockState(context.ticks, context.elapsedTimeMillis, clock.epochMillis())
            val millis = sample.epochMillis
            val value = if (context.attributes[ClockType] == "1") millis else floorDivide(millis, 1000)
            return ComponentEvaluation(mapOf("time" to LogicVector.of(64, value.toULong())), state = sample, delay = 0,
                nextWakeupMillis = if (context.autoPropagating) context.elapsedTimeMillis + 10 else null)
        }
    }

    private object ReptarBehavior : ComponentBehavior {
        override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation = ComponentEvaluation(
            reptarLayout(context.component, context.attributes).outputs.associate { it.name to LogicVector.unknown(it.width) },
            diagnostics = listOf(message("external_io_reptar_unavailable").resolve(AppLanguage.PORTUGUESE)),
            notices = listOf(message("external_io_reptar_unavailable")))
    }

    private fun floorDivide(value: Long, divisor: Long): Long =
        value / divisor - if (value < 0 && value % divisor != 0L) 1 else 0
}
