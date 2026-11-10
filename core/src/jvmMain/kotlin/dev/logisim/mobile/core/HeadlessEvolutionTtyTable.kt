package dev.logisim.mobile.core

import java.io.PrintStream
import java.util.UUID

internal enum class EvolutionTtyFormat(val argument: String) {
    CSV("csv"), TABS("tabs"), BINARY("binary"), HEX("hex"),
}

internal class HeadlessEvolutionTtyTable(formats: Set<EvolutionTtyFormat>) {
    private val binary = EvolutionTtyFormat.BINARY in formats
    private val hex = EvolutionTtyFormat.HEX in formats
    private val separator = when {
        EvolutionTtyFormat.TABS in formats -> "\t"
        EvolutionTtyFormat.CSV in formats -> ","
        else -> " "
    }

    fun writeWithoutHalt(
        project: CircuitProject,
        simulator: CircuitSimulator,
        settings: Map<String, String>,
        stdout: PrintStream,
        checkCancellation: () -> Unit,
    ): Boolean {
        val circuit = project.mainCircuit
        val labels = effectiveLabels(circuit)
        val pinNames = CircuitAnalyzer.canonicalPinNames(circuit) { labels[it.id].orEmpty() }
        val (inputs, outputs) = pinNames.keys.partition { it.kind == ComponentKind.INPUT }
        if (outputs.any { pinNames[it] == "halt" }) return false

        val inputBits = inputs.sumOf { it.width }
        require(inputBits <= 20) { "Evolution -tty table supports at most 20 input bits." }
        val headers = (inputs + outputs).map { pin ->
            val name = pinNames.getValue(pin)
            if (pin.width == 1) name else "$name[${pin.width}]"
        }
        var columnWidths = emptyList<Int>()
        fun write(fields: List<String>) {
            val line = fields.mapIndexed { index, value -> value.padStart(columnWidths[index]) }
            stdout.print(line.joinToString(separator) + "\n")
            check(!stdout.checkError()) { "Cannot write Evolution -tty table output." }
        }

        for (row in 0 until (1 shl inputBits)) {
            checkCancellation()
            var remaining = inputBits
            val stimulus = inputs.associate { pin ->
                remaining -= pin.width
                pin.id to LogicVector.of(pin.width, row.toULong() shr remaining)
            }
            val state = simulator.settle(project, vectorInputs = stimulus, options = settings)
            check(state.oscillating || state.diagnostics.isEmpty()) { state.diagnostics.joinToString("; ") }
            val values = inputs.map { stimulus.getValue(it.id) } + outputs.map { pin ->
                if (state.oscillating) LogicVector.error(pin.width)
                else state.vectorValues[pin.id] ?: LogicVector.unknown(pin.width)
            }
            val fields = values.map(::formatValue)
            if (row == 0) {
                columnWidths = headers.mapIndexed { index, name ->
                    if (separator == " ") maxOf(name.length, fields[index].length) else 0
                }
                write(headers)
            }
            write(fields)
        }
        return true
    }

    private fun formatValue(vector: LogicVector): String {
        val bits = vector.display.replace('?', 'U')
        return when {
            binary -> bits.reversed().chunked(4).joinToString(" ").reversed()
            hex -> hexadecimal(bits)
            vector.width <= 6 -> bits
            else -> "0x" + hexadecimal(bits)
        }
    }

    private fun hexadecimal(bits: String): String = bits.reversed().chunked(4).asReversed().joinToString("") { group ->
        val nibble = group.reversed()
        nibble.firstOrNull { it == 'U' || it == 'E' }?.toString() ?: nibble.toInt(2).toString(16)
    }

    private fun effectiveLabels(circuit: CircuitDocument): Map<String, String> {
        val used = mutableSetOf(circuit.name.uppercase())
        val repaired = mutableMapOf<String, String>()
        // Evolution repairs invalid labels and clears duplicate component labels when reading the file.
        return circuit.components.filter { it.kind != ComponentKind.TUNNEL && it.label.isNotEmpty() }.associate { component ->
            val label = repaired.getOrPut(component.label) { repairLabel(component.label) }
            component.id to if (used.add(label.uppercase())) label else ""
        }
    }

    private fun repairLabel(raw: String): String {
        if (validLabel.matches(raw) && !raw.endsWith('_') && "__" !in raw) return raw
        val trimmed = raw.trim { it <= ' ' }
        var label = trimmed.replace("!", "NOT_").replace("~", "NOT_")
        if (label.firstOrNull()?.let { it in 'A'..'Z' || it in 'a'..'z' } != true) label = "L_$label"
        label = label.replace(invalidCharacter, "_").replace(repeatedUnderscore, "_").trimEnd('_')
        return if (label == trimmed) label else "${label}_${UUID.randomUUID().toString().take(8)}"
    }

    private companion object {
        val validLabel = Regex("[A-Za-z][A-Za-z0-9_]*")
        val invalidCharacter = Regex("[^A-Za-z0-9_]")
        val repeatedUnderscore = Regex("_+")
    }
}
