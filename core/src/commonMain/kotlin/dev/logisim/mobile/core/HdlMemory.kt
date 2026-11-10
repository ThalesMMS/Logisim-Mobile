package dev.logisim.mobile.core

internal const val HDL_MEMORY_CELL = "\$mem_v2"

internal class HdlMemory private constructor(
    val attributes: Map<String, String>,
    val readOnly: Boolean,
    val terminals: Map<String, List<HdlBit>>,
) {
    companion object {
        fun decode(cell: HdlNetlistCell, undefined: HdlUndefinedPolicy): HdlMemory? {
            val params = cell.parameters
            val ports = cell.ports
            fun number(key: String) = params[key]?.toIntOrNull(2)
            val width = number("WIDTH") ?: return null
            val addressBits = number("ABITS") ?: return null
            if (width !in 1..64 || addressBits !in 1..12) return null
            val size = 1 shl addressBits
            if (number("SIZE") != size || number("OFFSET") != 0 || number("RD_PORTS") != 1 ||
                number("RD_CLK_ENABLE") != 0 || number("RD_WIDE_CONTINUATION") != 0) return null
            val high = listOf(HdlBit.Constant('1'))
            val low = listOf(HdlBit.Constant('0'))
            if (ports["RD_EN"] != high || ports["RD_ARST"] != low || ports["RD_SRST"] != low) return null
            val address = ports["RD_ADDR"]?.takeIf { it.size == addressBits } ?: return null
            val output = ports["RD_DATA"]?.takeIf { it.size == width && it.all { bit -> bit is HdlBit.Net } } ?: return null
            val nativeAddressBits = maxOf(2, addressBits)
            val terminals = mutableMapOf(
                MemoryLayouts.addressName(0) to (address + List(nativeAddressBits - addressBits) { HdlBit.Constant('0') }),
                MemoryLayouts.outputName(0, 0) to output,
            )

            val readOnly = number("WR_PORTS") == 0
            val attributes = mutableMapOf(
                "addrWidth" to nativeAddressBits.toString(),
                "dataWidth" to width.toString(),
            )
            if (!readOnly) {
                if (number("WR_PORTS") != 1 || number("WR_CLK_ENABLE") != 1 || number("WR_WIDE_CONTINUATION") != 0 ||
                    ports["WR_ADDR"] != address) return null
                val polarity = number("WR_CLK_POLARITY")?.takeIf { it in 0..1 } ?: return null
                val enable = ports["WR_EN"]?.takeIf { it.size == width && it.distinct().size == 1 } ?: return null
                val clock = ports["WR_CLK"]?.takeIf { it.size == 1 } ?: return null
                val input = ports["WR_DATA"]?.takeIf { it.size == width } ?: return null
                attributes += mapOf("trigger" to if (polarity == 1) "rising" else "falling", "asyncread" to "true",
                    "databus" to "bibus")
                terminals[MemoryLayouts.outputEnableName(0)] = high
                terminals[MemoryLayouts.writeEnableName(0)] = enable.take(1)
                terminals[MemoryLayouts.clockName(0)] = clock
                terminals[MemoryLayouts.inputName(0, 0)] = input
            }

            val initial = params["INIT"]?.takeIf { it.length == width * size && it.all { bit -> bit in "01x" } } ?: return null
            val unspecified = if (undefined == HdlUndefinedPolicy.ONE) '1' else '0'
            val cells = buildMap {
                for (index in 0 until size) {
                    val end = initial.length - index * width
                    val value = initial.substring(end - width, end).replace('x', unspecified).toULong(2)
                    if (value != 0uL) put(index.toLong(), value)
                }
            }
            if (cells.isNotEmpty()) attributes["contents"] = MemoryImageCodec.encodeCirc(MemoryImage(nativeAddressBits, width, cells))
            return HdlMemory(attributes, readOnly, terminals)
        }
    }
}
