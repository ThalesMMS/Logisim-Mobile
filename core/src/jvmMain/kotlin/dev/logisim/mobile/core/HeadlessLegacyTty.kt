package dev.logisim.mobile.core

import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Path

internal enum class LegacyTtyMode(val argument: String) {
    TABLE("table"), HALT("halt"), SPEED("speed"), STATS("stats"), TERMINAL("tty"),
}

internal object HeadlessLegacyTty {
    fun simulate(
        project: CircuitProject,
        modes: Set<LegacyTtyMode>,
        tickLimit: Long,
        simulator: CircuitSimulator,
        settings: Map<String, String>,
        initialState: SimulationState,
        stdout: PrintStream,
        stdin: InputStream,
        checkCancellation: () -> Unit,
        saveRam: (SimulationState) -> Unit,
        evolution: Boolean = false,
        stderr: PrintStream = stdout,
    ): Int {
        // Evolution's -t prints floating bits as U and logs the halt reason on stderr.
        val display: (LogicVector) -> String = { displayVector(it, if (evolution) 'U' else 'x') }
        val pins = project.mainCircuit.components
            .filter { it.kind == ComponentKind.INPUT || it.kind == ComponentKind.OUTPUT }
            .sortedWith(compareBy({ it.position.y }, { it.position.x }))
        // The first pin to claim the normalized label gets "halt". Later duplicates get numeric suffixes.
        val halt = pins.firstOrNull { analyzerLabel(it.label) == "halt" }
            ?.takeIf { it.kind == ComponentKind.OUTPUT }
        val outputs = pins.filter { it.kind == ComponentKind.OUTPUT && it !== halt }
        var state = simulator.settle(project, initialState, options = settings)
        val terminal = if (LegacyTtyMode.TERMINAL in modes) {
            HeadlessTerminal(project, state, stdin, stdout, checkCancellation, interactiveInput = true)
        } else null
        return terminal.use {
            val start = System.nanoTime()
            var previous: List<LogicVector>? = null
            val reason: String
            var exit = 0
            while (true) {
                checkCancellation()
                if (LegacyTtyMode.TABLE in modes) {
                    val values = outputs.map { state.portVectors[it.position] ?: LogicVector.unknown(it.width) }
                    if (values != previous) {
                        writeLine(stdout, values.joinToString("\t", transform = display))
                        previous = values
                    }
                }
                if (halt?.width == 1 && state.portVectors[halt.position]?.toULongOrNull() == 1uL) {
                    reason = "halt pin"
                    break
                }
                if (state.oscillating) {
                    reason = "detected oscillation"
                    exit = 1
                    break
                }
                check(state.diagnostics.isEmpty()) { state.diagnostics.joinToString("; ") }
                if (state.ticks >= tickLimit) {
                    reason = "tick limit"
                    break
                }
                state = if (terminal == null) {
                    simulator.settle(project, state, tick = true, options = settings)
                } else {
                    simulator.settleTerminal(project, state, tick = true, events = terminal.keyboardEvents(state),
                        options = settings, onTerminalWrite = terminal::write)
                }
            }
            val millis = (System.nanoTime() - start) / 1_000_000L
            terminal?.finish()
            if (LegacyTtyMode.HALT in modes || exit != 0) writeLine(if (evolution) stderr else stdout, "halted due to $reason")
            if (LegacyTtyMode.SPEED in modes) {
                writeLine(stdout, "${frequency(state.ticks, millis)} Hz (${state.ticks} ticks in $millis milliseconds)")
            }
            if (exit == 0) saveRam(state)
            exit
        }
    }

    fun writeStatistics(
        project: CircuitProject,
        projectName: String,
        resolved: ResolvedCircuitProject?,
        rows: List<List<String>>,
        stdout: PrintStream,
        checkCancellation: () -> Unit,
        evolution: Boolean = false,
    ) {
        val subcircuits = project.circuits.mapIndexed { index, circuit -> circuit.name to index }.toMap()
        val root = resolved?.sources?.get(resolved.rootSourceId)
        val libraryNames = root?.root?.children?.filter { it.name == "lib" }?.mapNotNull { library ->
            val dependency = root.dependencies[library.attributes["name"]]
            if (dependency == null) library.attributes["desc"] else libraryName(dependency)
        } ?: project.libraries.values.toList()
        val libraries = libraryNames.withIndex().associate { it.value to it.index }
        val externalRanks = resolved?.sources?.values?.associate { source ->
            libraryName(source.source.id) to source.circuits.keys.withIndex().associate { it.value to it.index }
        }.orEmpty()
        val components = rows.dropLast(2).sortedWith(compareBy(
            { if (it[0] == "subcircuit") -1 else libraries[it[1]] ?: Int.MAX_VALUE },
            { (if (it[0] == "subcircuit") subcircuits[it[2]]
                else (toolRanks[it[1]] ?: externalRanks[it[1]])?.get(it[2])) ?: Int.MAX_VALUE },
            { it[2] },
        ))
        val uniqueWidth = rows.last()[4].length
        val recursiveWidth = rows.last()[5].length
        val nameWidth = components.maxOfOrNull { componentName(it).length } ?: 0
        fun counts(row: List<String>): String = row[4].padStart(uniqueWidth) + "\t" + row[5].padStart(recursiveWidth)
        for (row in components) {
            checkCancellation()
            val library = if (row[0] == "subcircuit") projectName else if (row[1] == "#I/O") "Input/Output"
                else row[1].removePrefix("#")
            writeLine(stdout, "${counts(row)}\t${componentName(row).padEnd(nameWidth)}\t$library")
        }
        // Evolution's statsTotalWithout/statsTotalWith; 2.7.1 spells them differently.
        val (without, with) = if (evolution) "TOTAL (without project\u2019s sub circuits)" to "TOTAL (with sub circuits)"
            else "TOTAL (without project's subcircuits)" to "TOTAL (with subcircuits)"
        writeLine(stdout, "${counts(rows[rows.lastIndex - 1])}\t$without")
        writeLine(stdout, "${counts(rows.last())}\t$with")
    }

    private fun writeLine(output: PrintStream, line: String) {
        output.print("$line\n")
        output.flush()
        check(!output.checkError()) { "Cannot write -tty output." }
    }

    private fun analyzerLabel(label: String): String {
        val suffix = StringBuilder()
        var afterSpace = false
        return buildString {
            for (character in label) {
                when {
                    Character.isJavaIdentifierStart(character) -> {
                        append(if (afterSpace) Character.toTitleCase(character) else character)
                        afterSpace = false
                    }
                    Character.isJavaIdentifierPart(character) -> {
                        if (isEmpty()) suffix.append(character) else append(character)
                        afterSpace = false
                    }
                    Character.isWhitespace(character) -> afterSpace = true
                }
            }
            if (isNotEmpty()) append(suffix)
        }
    }

    private fun displayVector(vector: LogicVector, floating: Char): String =
        vector.display.replace('?', floating).reversed().chunked(4).joinToString(" ").reversed()

    private fun frequency(ticks: Long, millis: Long): String {
        val hertz = ticks.toDouble() / millis * 1000
        val precision = when {
            hertz >= 100 -> 1.0
            hertz >= 10 -> 0.1
            hertz >= 1 -> 0.01
            hertz >= 0.01 -> 0.0001
            else -> 0.0000001
        }
        val rounded = (hertz / precision).toInt() * precision
        return if (rounded == rounded.toInt().toDouble()) rounded.toInt().toString() else rounded.toString()
    }

    private fun componentName(row: List<String>): String =
        displayNames[row[1]]?.get(row[2]) ?: row[2]

    private fun libraryName(path: String): String = Path.of(path).fileName.toString().removeSuffix(".circ")

    private val displayNames = mapOf(
        "#Base" to mapOf("Text" to "Label"),
        "#Plexers" to mapOf("BitSelector" to "Bit Selector"),
        "#Arithmetic" to mapOf("BitAdder" to "Bit Adder", "BitFinder" to "Bit Finder"),
        "#Memory" to mapOf("Random" to "Random Generator"),
        "#I/O" to mapOf("DotMatrix" to "LED Matrix"),
    )

    // 2.7.1 presents components in their library toolbar order.
    private val toolRanks = mapOf(
        "#Gates" to "NOT Gate|Buffer|AND Gate|OR Gate|NAND Gate|NOR Gate|XOR Gate|XNOR Gate|Odd Parity|Even Parity|Controlled Buffer|Controlled Inverter",
        "#Wiring" to "Splitter|Pin|Probe|Tunnel|Pull Resistor|Clock|Constant|Power|Ground|Transistor|Transmission Gate|Bit Extender",
        "#Plexers" to "Multiplexer|Demultiplexer|Decoder|Priority Encoder|BitSelector",
        "#Arithmetic" to "Adder|Subtractor|Multiplier|Divider|Negator|Comparator|Shifter|BitAdder|BitFinder",
        "#Memory" to "D Flip-Flop|T Flip-Flop|J-K Flip-Flop|S-R Flip-Flop|Register|Counter|Shift Register|Random|RAM|ROM",
        "#I/O" to "Button|Joystick|Keyboard|LED|7-Segment Display|Hex Digit Display|DotMatrix|TTY",
    ).mapValues { (_, tools) -> tools.split('|').withIndex().associate { it.value to it.index } }
}
