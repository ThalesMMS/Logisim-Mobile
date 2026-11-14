package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Updates that attach SoC components to buses, and those the same edit makes to other components of the circuit.
 * [own] holds, per selected component, its other bus references that move with a bus given an identifier.
 */
data class SocBusAttachment(
    val updates: Map<String, String>,
    val others: Map<String, Map<String, String>> = emptyMap(),
    val own: Map<String, Map<String, String>> = emptyMap(),
) {
    /** What the edit changes in the selected component [id]: [updates] and its own references that move. */
    fun updatesFor(id: String): Map<String, String> = own[id].orEmpty() + updates
}

/** Metadata and geometry for the desktop SoC library. Execution is coordinated per circuit instance. */
object SocComponents {
    val BUS = ComponentKind("SOC_BUS")
    val MEMORY = ComponentKind("SOC_MEMORY")
    val PIO = ComponentKind("SOC_PIO")
    val VGA = ComponentKind("SOC_VGA")
    val DMA = ComponentKind("SOC_DMA")
    val UART = ComponentKind("SOC_JTAG_UART")
    val RV32 = ComponentKind("SOC_RV32IM")
    val NIOS2 = ComponentKind("SOC_NIOS2")
    val kinds = setOf(BUS, MEMORY, PIO, VGA, DMA, UART, RV32, NIOS2)
    val BusSelection = AttributeKey.text("SocBusSelection")
    val BusIdentifier = AttributeKey.text("SocBusIdentifier")
    val StartAddress = hex("StartAddress")
    val MemorySize = AttributeKey.int("MemSize", 10, 10..26)
    val Width = AttributeKey.int("width", 1, 1..64)
    val PioDirection = AttributeKey.choice("direction", "inputonly", setOf("inputonly", "outputonly", "bidir", "inout"))
    val OutputResetValue = hex("outputresetvalue")
    val BitOperations = AttributeKey.boolean("outputbitsetclear", false)
    val SyncCapture = AttributeKey.boolean("inputssynccapt", false)
    val CaptureType = AttributeKey.choice("capturetype", "rising", setOf("rising", "falling", "any"))
    val ClearCaptureBits = AttributeKey.boolean("inputscaptbit", false)
    val GenerateIrq = AttributeKey.boolean("genirq", false)
    val IrqType = AttributeKey.choice("irqtype", "level", setOf("level", "edge"))
    val TraceSize = AttributeKey.int("TraceSize", 5, 1..64)
    val TraceVisible = AttributeKey.boolean("TraceVisible", true)
    val BufferAddress = hex("BufferAddress")
    val DisplayMode = AttributeKey.choice("DisplayMode", "160x120", setOf("160x120", "320x240", "640x480", "800x600", "1024x768"))
    val SoftModes = listOf("160x120", "320x240", "640x480", "800x600", "1024x768").mapIndexed { i, size -> AttributeKey.boolean("soft$size", i < 3) }
    val BurstSize = AttributeKey("BurstSize", 16, { raw -> raw.toIntOrNull()?.let { requested ->
        listOf(1, 2, 4, 8, 16, 32, 64, 128, 256).firstOrNull { it >= requested } ?: 256
    } }, Int::toString) { it in 1..256 && it and (it - 1) == 0 }.offering(listOf(1, 2, 4, 8, 16, 32, 64, 128, 256))
    val SourceBus = AttributeKey.text("DmaSrcBus")
    val DestinationBus = AttributeKey.text("DmaDstBus")
    val WriteFifoSize = fifo("WriteFifoSize")
    val ReadFifoSize = fifo("ReadFifoSize")
    val WriteThreshold = AttributeKey.int("WriteThreshold", 8)
    val ReadThreshold = AttributeKey.int("ReadThreshold", 8)
    val IrqWidth = AttributeKey.int("irqWidth", 0, 0..32)
    val ResetVector = hex("resetVector")
    val ExceptionVector = hex("exceptionVector", 0x14u)
    val BreakVector = hex("breakVector", 0x30u)
    val PlicAddress = hex("plicBaseAddress", 0x0C000000u)
    val StateVisible = AttributeKey.boolean("stateVisible", true)

    private fun hex(name: String, default: UInt = 0u) = AttributeKey(name, default,
        ::parseEvolutionHexInteger,
        { "0x" + it.toString(16) })
    private fun fifo(name: String) = AttributeKey(name, 64, String::toIntOrNull, Int::toString) {
        it in 8..32768 && it and (it - 1) == 0
    }.offering((3..15).map { 1 shl it })

    /** The attributes naming the SoC bus a component is attached to; editors offer the circuit's buses. */
    val busReferences: Set<String> = setOf(BusSelection.name, SourceBus.name, DestinationBus.name)

    /** The identifier other SoC components store to attach to [bus]: its SocBusIdentifier, or its id when blank. */
    fun busId(bus: CircuitComponent): String = attributes(bus)[BusIdentifier].ifBlank { bus.id }

    /**
     * A new bus identifier in Evolution's format: SocBusAttributes writes `0x`, the creation time in milliseconds as
     * sixteen hexadecimal digits, and a hexadecimal suffix. Evolution reads only this identifier, never a mobile id.
     */
    fun newBusIdentifier(
        used: Set<String>,
        millis: Long = Clock.System.now().toEpochMilliseconds(),
        random: Random = Random.Default,
    ): String {
        val time = "0x" + millis.toULong().toString(16).uppercase().padStart(16, '0')
        while (true) {
            val candidate = time + random.nextInt().toUInt().toString(16)
            if (candidate !in used) return candidate
        }
    }

    /** A placed bus keeps its identifier only when no other bus of the circuit has it, as Evolution's new attribute sets. */
    private fun placedBus(bus: CircuitComponent, existing: List<CircuitComponent>): Map<String, String> {
        val used = existing.filter { it.kind == BUS && it.id != bus.id }.mapTo(mutableSetOf()) { runCatching { busId(it) }.getOrDefault(it.id) }
        val own = bus.attributes[BusIdentifier.name].orEmpty()
        return if (own.isNotBlank() && own !in used) bus.attributes else bus.attributes + (BusIdentifier.name to newBusIdentifier(used))
    }

    /**
     * [updates] for the [selected] components of [circuit], attaching them to buses by identifiers Evolution reads.
     * Choosing a bus saved without an identifier (by an earlier version of the port, which attached components by the
     * bus's mobile id) gives that bus one; [SocBusAttachment.others] then also moves the circuit's other components
     * attached by the old id, and [SocBusAttachment.own] the selected components' other references to it (a DMA's
     * control and source bus when its destination is chosen), so the whole change is one edit.
     */
    fun attach(updates: Map<String, String>, circuit: List<CircuitComponent>, selected: Set<String>,
               newIdentifier: (Set<String>) -> String = { newBusIdentifier(it) }): SocBusAttachment {
        val blank = circuit.filter { bus ->
            bus.kind == BUS && bus.attributes[BusIdentifier.name].isNullOrBlank() &&
                updates.any { (name, value) -> name in busReferences && value == bus.id }
        }
        if (blank.isEmpty()) return SocBusAttachment(updates)
        val used = circuit.filter { it.kind == BUS }.mapTo(mutableSetOf()) { runCatching { busId(it) }.getOrDefault(it.id) }
        val renamed = blank.associate { bus -> bus.id to newIdentifier(used).also { used += it } }
        val others = mutableMapOf<String, Map<String, String>>()
        val own = mutableMapOf<String, Map<String, String>>()
        for (component in circuit) {
            val target = if (component.id in selected) own else others
            val moved = mutableMapOf<String, String>()
            renamed[component.id]?.let { moved[BusIdentifier.name] = it }
            if (component.kind in kinds) for (name in busReferences) renamed[component.attributes[name]]?.let { moved[name] = it }
            // What the edit sets itself wins over a reference that moves.
            if (target === own) moved.keys.removeAll(updates.keys)
            if (moved.isNotEmpty()) target[component.id] = moved
        }
        return SocBusAttachment(updates.mapValues { (name, value) -> if (name in busReferences) renamed[value] ?: value else value }, others, own)
    }
    private fun schema(vararg keys: AttributeKey<*>) = AttributeSchema(listOf(CommonAttributes.Label) + keys)
    fun attributes(component: CircuitComponent): AttributeValues = definition(component.kind).schema.parse(component.attributes)
    fun definition(kind: ComponentKind): ComponentDefinition = definitions().first { it.kind == kind }
    fun definitions(): List<ComponentDefinition> = listOf(
        descriptor(BUS, "SocBus", schema(TraceSize, TraceVisible, BusIdentifier)) { c, a -> layout(c, 640, (a[TraceSize] + 1) * 30, listOf(port(c, "reset", 0, 10))) },
        descriptor(MEMORY, "Socmem", schema(StartAddress, MemorySize, BusSelection)) { c, a ->
            memorySizeInBytes(a)
            layout(c, 320, 60)
        },
        descriptor(PIO, "SocPio", schema(StartAddress, Width, PioDirection, OutputResetValue, BitOperations,
            SyncCapture, CaptureType, ClearCaptureBits, GenerateIrq, IrqType, BusSelection)) { c, a ->
            val direction = a[PioDirection]
            val ports = mutableListOf(port(c, "reset", 0, 110))
            if (a[GenerateIrq] && direction != "outputonly") ports += port(c, "irq", 20, 0, PortDirection.OUTPUT)
            if (direction == "inputonly" || direction == "inout") repeat(a[Width]) { ports += port(c, "input$it", 370 - it * 10, 120) }
            if (direction != "inputonly") repeat(a[Width]) { ports += port(c, "output$it", 370 - it * 10, 0, if (direction == "bidir") PortDirection.INOUT else PortDirection.OUTPUT) }
            layout(c, 380, 120, ports)
        },
        descriptor(VGA, "SocVga", AttributeSchema(listOf(CommonAttributes.Label, StartAddress, BufferAddress, DisplayMode, BusSelection) + SoftModes)) { c, a ->
            val size = a[DisplayMode].split('x').map(String::toInt)
            layout(c, size[0] + 10, size[1] + 40)
        },
        descriptor(DMA, "SocDma", schema(StartAddress, BurstSize, BusSelection, SourceBus, DestinationBus)) { c, _ ->
            layout(c, 320, 120, listOf(port(c, "reset", 0, 100), port(c, "clock", 0, 110), port(c, "irq", 320, 100, PortDirection.OUTPUT)))
        },
        descriptor(UART, "SocJtagUart", schema(StartAddress, WriteFifoSize, WriteThreshold, ReadFifoSize, ReadThreshold, BusSelection)) { c, _ ->
            layout(c, 300, 60, listOf(port(c, "clock", 0, 50), port(c, "reset", 0, 30), port(c, "irq", 300, 50, PortDirection.OUTPUT),
                port(c, "readEnable", 10, 0, PortDirection.OUTPUT), port(c, "clearKeyboard", 20, 0, PortDirection.OUTPUT),
                port(c, "available", 130, 0), port(c, "dataIn", 140, 0, width = 7), port(c, "dataOut", 160, 0, PortDirection.OUTPUT, 7),
                port(c, "write", 180, 0, PortDirection.OUTPUT), port(c, "clearTty", 190, 0, PortDirection.OUTPUT)))
        },
        descriptor(RV32, "Rv32im", schema(ResetVector, IrqWidth, PlicAddress, StateVisible, BusSelection)) { c, a ->
            layout(c, 640, 640, listOf(port(c, "reset", 0, 610), port(c, "clock", 0, 630)) +
                List(a[IrqWidth]) { port(c, "irq$it", 0, 10 + it * 10) })
        },
        descriptor(NIOS2, "Nios2", schema(ResetVector, ExceptionVector, BreakVector, IrqWidth, StateVisible, BusSelection)) { c, a ->
            val custom = listOf("dataA" to 32, "dataB" to 32, "start" to 1, "n" to 8, "a" to 5, "readRA" to 1,
                "b" to 5, "readRB" to 1, "c" to 5, "writeRC" to 1).mapIndexed { i, (name, width) -> port(c, name, 30 + i * 50, 0, PortDirection.OUTPUT, width) }
            layout(c, 640, 650, listOf(port(c, "clock", 0, 640), port(c, "reset", 0, 620)) + custom +
                listOf(port(c, "result", 610, 0, width = 32), port(c, "done", 560, 0)) + List(a[IrqWidth]) { port(c, "irq$it", 0, 40 + it * 10) })
        },
    )
    private fun descriptor(kind: ComponentKind, name: String, schema: AttributeSchema,
        geometry: (CircuitComponent, AttributeValues) -> ComponentLayout) = ComponentDefinition(
        kind, name, "#Soc", schema.defaults, schema, geometry, behavior = SocComponentBehavior, supportsRotation = false, copyAttributes = ::copiedAttributes,
        // Evolution keeps a bus's identifier hidden: editing it would silently detach every component attached to it.
        // It stays in the file.
        presentAttribute = { _, attribute -> if (attribute == BusIdentifier.name) null else AttributePresentation() },
        placedAttributes = if (kind == BUS) ::placedBus else null)
    private fun port(c: CircuitComponent, name: String, x: Int, y: Int, direction: PortDirection = PortDirection.INPUT, width: Int = 1) =
        ComponentTerminal(name, GridPoint(c.position.x + x, c.position.y + y), direction, width)
    private fun layout(c: CircuitComponent, width: Int, height: Int, ports: List<ComponentTerminal> = emptyList()) =
        ComponentLayout(ports, ComponentBounds(c.position.x, c.position.y, c.position.x + width, c.position.y + height))

    private fun copiedAttributes(context: ComponentCopyContext): Map<String, String> {
        val used = context.existingComponents.filter { it.kind == BUS }.map {
            attributes(it)[BusIdentifier].ifBlank { it.id }
        }.toMutableSet()
        val replacements = mutableMapOf<String, String>()
        for (source in context.originals.filter { it.kind == BUS }) {
            val copy = context.copiesBySourceId.getValue(source.id)
            var candidate = copy.id + "-bus"
            var suffix = 1
            while (candidate in used) candidate = copy.id + "-bus-" + suffix++
            used += candidate
            replacements[attributes(source)[BusIdentifier].ifBlank { source.id }] = candidate
        }
        val result = context.copied.attributes.toMutableMap()
        if (context.source.kind == BUS) result[BusIdentifier.name] = replacements.getValue(attributes(context.source)[BusIdentifier].ifBlank { context.source.id })
        else for (key in listOf(BusSelection.name, SourceBus.name, DestinationBus.name)) {
            replacements[result[key]]?.let { result[key] = it }
        }
        return result
    }

    private fun memorySizeInBytes(attributes: AttributeValues): ULong {
        val start = attributes[StartAddress] and 3u.inv()
        val size = 1uL shl attributes[MemorySize]
        requireLocalized(start.toULong() + size <= 0x100000000uL) {
            message("soc_memory_address_range", "address" to ("0x" + start.toString(16).padStart(8, '0')), "bytes" to size)
        }
        return size
    }

    fun createDevice(component: CircuitComponent): SocBusDevice? {
        val a = attributes(component)
        return when (component.kind) {
            MEMORY -> SocMemoryDevice(component.id, a[StartAddress], memorySizeInBytes(a))
            PIO -> SocPioDevice(component.id, a[StartAddress], a[Width], when (a[PioDirection]) {
                "outputonly" -> SocPioDirection.OUTPUT; "bidir" -> SocPioDirection.BIDIRECTIONAL
                "inout" -> SocPioDirection.SEPARATE_IN_OUT; else -> SocPioDirection.INPUT
            }, a[OutputResetValue], a[BitOperations], a[SyncCapture], when (a[CaptureType]) {
                "falling" -> SocCaptureEdge.FALLING; "any" -> SocCaptureEdge.ANY; else -> SocCaptureEdge.RISING
            }, a[ClearCaptureBits], a[GenerateIrq], a[IrqType] == "edge")
            DMA -> SocDmaDevice(component.id, a[StartAddress], a[BurstSize], a[SourceBus], a[DestinationBus])
            UART -> SocJtagUartDevice(component.id, a[StartAddress], a[WriteFifoSize], a[ReadFifoSize], a[WriteThreshold], a[ReadThreshold])
            VGA -> SocVgaDevice(component.id, a[StartAddress], a[BufferAddress], listOf("160x120", "320x240", "640x480", "800x600", "1024x768").indexOf(a[DisplayMode]),
                SoftModes.foldIndexed(0u) { i, mask, key -> if (a[key]) mask or (1u shl i) else mask })
            RV32 -> SocPlicDevice(component.id + "/plic", a[PlicAddress], a[IrqWidth])
            else -> null
        }
    }
}
