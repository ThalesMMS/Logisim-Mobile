package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GatesDesktopParityTest {
    @Test
    fun gateBehaviorAndGeometryMatchTheDesktopCorpus() {
        val registry = ComponentRegistry(GatesLibrary.definitions())
        val resource = assertNotNull(javaClass.getResourceAsStream("/gates-desktop.tsv"))
        val lines = resource.bufferedReader().use { it.readLines() }.drop(1)
        assertEquals(312, lines.size)
        val covered = mutableSetOf<String>()
        for (line in lines) {
            val cells = line.split('\t')
            val id = cells[0]
            val name = cells[1]
            covered += name
            val raw = cells[2].split(';').filter { it.isNotBlank() }.associate {
                it.substringBefore('=') to it.substringAfter('=').replace("\\n", "\n")
            }
            val definition = assertNotNull(registry.resolve("#Gates", name, raw), id)
            val component = registry.create(definition.kind, id, GridPoint(100, 100), raw)
            val attributes = registry.attributes(component)
            val layout = registry.layout(component)
            val inputValues = cells[3].split(',').map(::parseValue)
            val connected = cells[4]
            val inputs = layout.inputs.withIndex().associate { (index, port) -> port.name to inputValues[index] }
            val attached = layout.inputs.withIndex().filter { connected[it.index] == '1' }.map { it.value.name }.toSet()
            val result = assertNotNull(definition.behavior).evaluate(ComponentEvaluationContext(
                component, attributes, inputs, attached, options = mapOf("gateUndefined" to cells[5]),
            ))
            assertEquals(cells[6], result.outputs.getValue("out").display, "$id output")
            assertEquals(cells[7].toLong(), result.delay, "$id delay")
            val ports = layout.ports.joinToString(";") { port ->
                val direction = when (port.direction) {
                    PortDirection.INPUT -> 1
                    PortDirection.OUTPUT -> 2
                    PortDirection.INOUT -> 3
                }
                "${port.position.x},${port.position.y},${port.width},$direction"
            }
            assertEquals(cells[8], ports, "$id port positions, widths and directions")
            val bounds = layout.bounds
            assertEquals(cells[9], "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}", "$id bounds")
        }
        assertEquals(setOf("NOT Gate", "Buffer", "AND Gate", "OR Gate", "NAND Gate", "NOR Gate",
            "XOR Gate", "XNOR Gate", "Odd Parity", "Even Parity", "Controlled Buffer", "Controlled Inverter", "PLA"), covered)
    }

    private fun parseValue(value: String) = LogicVector.fromBits(value.reversed().map {
        when (it) {
            '0' -> LogicValue.LOW
            '1' -> LogicValue.HIGH
            'E' -> LogicValue.ERROR
            '?' -> LogicValue.UNKNOWN
            else -> error("Unknown fixture bit $it")
        }
    })
}
