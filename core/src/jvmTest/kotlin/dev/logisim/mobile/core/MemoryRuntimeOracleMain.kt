package dev.logisim.mobile.core

import java.io.File

fun main(args: Array<String>) {
    var configuration = MemoryConfiguration()
    var state = MemoryRuntimeState.initial(configuration)
    var id = ""
    var step = 0
    File(args.single()).forEachLine { line ->
        val fields = line.split('\t')
        if (fields[0] == "@") {
            id = fields[1]
            val attributes = fields[3].split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
            configuration = MemoryConfiguration.fromAttributes(fields[2], attributes)
            state = MemoryRuntimeState.initial(configuration)
            step = 0
            val layout = MemoryLayouts.layout(GridPoint(100, 200), configuration)
            val bounds = layout.bounds
            val geometry = buildString {
                append("${bounds.left},${bounds.top},${bounds.width},${bounds.height}")
                for (port in layout.terminals) append(";${port.position.x},${port.position.y},${port.width},${when(port.direction) {
                    PortDirection.INPUT -> 1; PortDirection.OUTPUT -> 2; PortDirection.INOUT -> 3
                }}")
            }
            println("$id\tG\t$geometry")
        } else {
            val inputs = fields[1].split(';').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=') }
            fun value(key: String, width: Int) = when (val text = inputs[key]) {
                null, "U" -> LogicVector.unknown(width)
                "E" -> LogicVector.error(width)
                else -> LogicVector.of(width, text.toULong(16))
            }
            fun bit(key: String) = value(key, 1).bit(0)
            val ports = List(configuration.portCount) { port -> MemoryPortInput(
                value("a$port", configuration.addressBits),
                List(configuration.dataLines) { value("d${port}_$it", configuration.dataBits) },
                bit("oe$port"), bit("we$port"), bit("clk$port"),
                List(configuration.lineEnableCount) { bit("le${port}_$it") },
                List(configuration.byteEnableCount) { bit("be${port}_$it") }) }
            state = MemoryCore.evaluate(configuration, state, ports, bit("clear")).state
            println("$id\t${step++}\t${state.outputs.flatten().joinToString(";") { it.display }}")
        }
    }
}
