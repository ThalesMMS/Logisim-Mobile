package dev.logisim.mobile.core

data class MemoryTerminal(
    val name: String,
    val position: GridPoint,
    val direction: PortDirection,
    val width: Int,
    val label: String,
)

data class MemoryLayout(val terminals: List<MemoryTerminal>, val bounds: ComponentBounds)

/** Absolute memory terminals, matching RamAppearance and DualRamAppearance. */
object MemoryLayouts {
    fun layout(position: GridPoint, configuration: MemoryConfiguration): MemoryLayout {
        val c = configuration
        if (c.originalRom) return originalRom(position, c)
        c.originalInterface?.let { return originalRam(position, c, it) }
        val lineEnables = c.lineEnableCount
        val byteEnables = c.byteEnableCount
        val control = when {
            c.readOnly -> 60
            c.enables == MemoryEnables.LINE -> 60 + (if (c.classic) 0 else 30) + lineEnables * 10
            else -> 80 + (if (c.synchronous) 10 else 0) + byteEnables * 10
        }
        val width = if (c.classic || c.bus != MemoryBus.SHARED || c.readOnly) 240 else 250
        val totalControl = control * c.portCount
        val extra = when {
            c.readOnly -> if (c.classic) 80 else minOf(8, c.dataBits) * 20
            c.dualPort && c.classic -> maxOf(70, (lineEnables * 2 + 1) * 10)
            c.dualPort -> maxOf(minOf(8, c.dataBits) * 40, (lineEnables * 2 + 1) * 10)
            c.classic -> maxOf(64, (lineEnables + 1) * 10)
            else -> maxOf(minOf(160, c.dataBits * 20), (lineEnables + 1) * 10)
        }
        val height = if (c.readOnly && c.classic) 140 else totalControl + extra
        val classicBOffset = (((height / 2) + 5) / 10) * 10
        val terminals = mutableListOf<MemoryTerminal>()
        fun add(name: String, x: Int, y: Int, direction: PortDirection, bits: Int, label: String) {
            terminals += MemoryTerminal(name, GridPoint(position.x + x, position.y + y), direction, bits, label)
        }
        fun blockY(index: Int, single: Int): Int = single + if (index == 0) 0 else
            if (c.classic) classicBOffset else control - 10
        fun dataY(port: Int, line: Int): Int {
            val base = if (c.dualPort && !c.classic) totalControl else control
            val block = if (port == 0) 0 else if (c.classic) classicBOffset else minOf(8, c.dataBits) * 20
            return base + (if (!c.classic && c.dataBits == 1) 10 else 0) + block + line * 10
        }
        repeat(c.portCount) { port ->
            add(addressName(port), 0, if (!c.dualPort || c.classic) 10 + port * classicBOffset else 20 + port * (control - 10),
                PortDirection.INPUT, c.addressBits, if (c.dualPort) "A${'A' + port}" else "A")
        }
        repeat(c.portCount) { port -> repeat(c.dataLines) { line ->
            add(outputName(port, line), width, dataY(port, line),
                if (!c.readOnly && c.bus == MemoryBus.SHARED) PortDirection.INOUT else PortDirection.OUTPUT,
                c.dataBits, "D${port * c.dataLines + line}")
        } }
        if (!c.readOnly && c.bus != MemoryBus.SHARED) repeat(c.portCount) { port -> repeat(c.dataLines) { line ->
            add(inputName(port, line), 0, dataY(port, line), PortDirection.INPUT, c.dataBits, "D${port * c.dataLines + line}")
        } }
        if (c.hasOutputEnable) repeat(c.portCount) { port ->
            val y = when {
                !c.dualPort -> if (c.classic && c.enables == MemoryEnables.LINE) 20 else 60
                !c.classic -> blockY(port, 70)
                port == 1 -> classicBOffset + 60
                c.enables == MemoryEnables.LINE -> 20
                else -> 60
            }
            add(outputEnableName(port), 0, y, PortDirection.INPUT, 1, "OE")
        }
        if (!c.readOnly) repeat(c.portCount) { port ->
            val base = if (c.classic && c.enables == MemoryEnables.LINE) 30 else if (c.dualPort && !c.classic) 60 else 50
            add(writeEnableName(port), 0, blockY(port, base), PortDirection.INPUT, 1, "WE")
        }
        if (c.hasClock) repeat(c.portCount) { port ->
            val base = when {
                c.dualPort && !c.classic -> 80 + lineEnables * 10 + byteEnables * 10
                c.dualPort && c.enables == MemoryEnables.LINE -> 40 + lineEnables * 10 + byteEnables * 10
                c.dualPort -> 70
                else -> (if (c.classic && c.enables == MemoryEnables.LINE) 40 else 70) + lineEnables * 10 + byteEnables * 10
            }
            add(clockName(port), 0, blockY(port, base), PortDirection.INPUT, 1, "CLK")
        }
        repeat(c.portCount) { port -> repeat(lineEnables) { line ->
            val base = if (c.classic) 40 else if (c.dualPort) 80 else 70
            add(lineEnableName(port, line), 0, blockY(port, base + line * 10), PortDirection.INPUT, 1, "LE$line")
        } }
        repeat(c.portCount) { port -> repeat(byteEnables) { byte ->
            val base = (if (c.dualPort) 80 + lineEnables * 10 else 70) + (byteEnables - byte - 1) * 10
            add(byteEnableName(port, byte), 0, blockY(port, base), PortDirection.INPUT, 1, "BE$byte")
        } }
        if (!c.readOnly && c.clearPin) add("clear", 40, 0, PortDirection.INPUT, 1, "CLR")
        return MemoryLayout(terminals, ComponentBounds(position.x, position.y, position.x + width, position.y + height))
    }

    private fun originalRom(position: GridPoint, configuration: MemoryConfiguration): MemoryLayout {
        val (x, y) = position
        return MemoryLayout(
            listOf(
                MemoryTerminal(addressName(0), GridPoint(x - 140, y), PortDirection.INPUT, configuration.addressBits, "A"),
                MemoryTerminal(outputName(0, 0), position, PortDirection.INOUT, configuration.dataBits, "D"),
                MemoryTerminal(ORIGINAL_CHIP_SELECT, GridPoint(x - 90, y + 40), PortDirection.INPUT, 1, "sel"),
            ),
            ComponentBounds(x - 140, y - 40, x, y + 40),
        )
    }

    private fun originalRam(position: GridPoint, configuration: MemoryConfiguration, mode: OriginalRamInterface): MemoryLayout {
        val terminals = buildList {
            fun port(name: String, x: Int, y: Int, label: String, bits: Int = 1, direction: PortDirection = PortDirection.INPUT) {
                add(MemoryTerminal(name, GridPoint(position.x + x, position.y + y), direction, bits, label))
            }
            port(addressName(0), -140, 0, "A", configuration.addressBits)
            port(outputName(0, 0), 0, 0, "D", configuration.dataBits, PortDirection.INOUT)
            port(ORIGINAL_CHIP_SELECT, -90, 40, "sel")
            port(outputEnableName(0), -50, 40, "ld")
            port("clear", -30, 40, "clr")
            if (mode != OriginalRamInterface.ASYNCHRONOUS) port(clockName(0), -70, 40, "CLK")
            if (mode == OriginalRamInterface.SEPARATE) {
                port(writeEnableName(0), -110, 40, "str")
                port(inputName(0, 0), -140, 20, "D", configuration.dataBits)
            }
        }
        return MemoryLayout(terminals, ComponentBounds(position.x - 140, position.y - 40, position.x, position.y + 40))
    }

    internal const val ORIGINAL_CHIP_SELECT = "chipSelect"
    fun addressName(port: Int) = "address$port"
    fun outputName(port: Int, line: Int) = "dataOut${port}_$line"
    fun inputName(port: Int, line: Int) = "dataIn${port}_$line"
    fun outputEnableName(port: Int) = "outputEnable$port"
    fun writeEnableName(port: Int) = "writeEnable$port"
    fun clockName(port: Int) = "clock$port"
    fun lineEnableName(port: Int, line: Int) = "lineEnable${port}_$line"
    fun byteEnableName(port: Int, byte: Int) = "byteEnable${port}_$byte"
}
