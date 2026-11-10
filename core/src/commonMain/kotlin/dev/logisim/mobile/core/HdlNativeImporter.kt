package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

sealed interface HdlImportResult {
    data class Success(
        val project: CircuitProject,
        val importedCircuits: List<String>,
        val moduleNames: Map<String, String>,
        val diagnostics: List<HdlImportDiagnostic> = emptyList(),
    ) : HdlImportResult
    data class Failure(val diagnostics: List<HdlImportDiagnostic>) : HdlImportResult
}

/** A detached conversion: failure never mutates or replaces the editor's current project. */
object HdlNativeImporter {
    fun import(
        netlist: HdlNetlist,
        sources: List<HdlInputFile>,
        undefined: HdlUndefinedPolicy = HdlUndefinedPolicy.REJECT,
        existingProject: CircuitProject? = null,
        registry: ComponentRegistry = ComponentCatalog.registry,
    ): HdlImportResult {
        return try {
            requireLocalized(sources.size <= 64 && sources.map { it.name }.distinct().size == sources.size) {
                message(Messages.HdlImportSourcesLimit)
            }
            val moduleNames = netlist.modules.map { it.name }.toSet()
            val unsupported = netlist.modules.flatMap { module ->
                module.cells.filter { it.type !in moduleNames && !supportedCell(it.type) }.map {
                    HdlImportDiagnostic("unsupported-cell", message(Messages.HdlImportUnsupportedCell, "type" to it.type), module.name, it.name)
                }
            }
            if (unsupported.isNotEmpty()) return HdlImportResult.Failure(unsupported)
            val occupied = existingProject?.circuits?.flatMap { listOf(it.name, it.id) }?.toMutableSet() ?: mutableSetOf()
            val names = netlist.modules.associate { module ->
                val base = "hdl_" + module.name.replace(Regex("[^A-Za-z0-9_]"), "_").take(80)
                var name = base
                var suffix = 2
                while (!occupied.add(name)) name = "${base}_${suffix++}"
                module.name to name
            }
            val byName = netlist.modules.associateBy { it.name }
            val interfaces = byName.mapValues { (_, module) -> nativePorts(module.ports) }
            val generated = linkedMapOf<String, CircuitDocument>()
            fun build(name: String): CircuitDocument = generated[name] ?: run {
                val module = byName[name] ?: throw HdlConversionFailure("missing-module", message(Messages.HdlImportModuleMissing, "module" to name), name)
                module.cells.filter { it.type in byName }.forEach { build(it.type) }
                ModuleBuilder(module, names, interfaces, generated, undefined, registry).build().also { generated[name] = it }
            }
            netlist.modules.forEach { build(it.name) }
            val preserved = sources.map { file ->
                "<options><a name=\"logisim-mobile-hdl-source\" file=\"${hdlXml(file.name)}\">${hdlXml(file.text)}</a></options>"
            }
            val project = if (existingProject == null) CircuitProject(
                circuits = generated.values.toList(), mainCircuitName = names.getValue(netlist.top),
                sourceVersion = "5.1.0-dev", extraXml = preserved,
            ) else existingProject.copy(
                circuits = existingProject.circuits + generated.values,
                mainCircuitName = names.getValue(netlist.top),
                extraXml = existingProject.extraXml + preserved,
            )
            HdlImportResult.Success(project, generated.values.map { it.name }, names)
        } catch (error: HdlConversionFailure) {
            HdlImportResult.Failure(listOf(HdlImportDiagnostic(error.code, error.notice, error.module, error.cell)))
        } catch (error: IllegalArgumentException) {
            HdlImportResult.Failure(listOf(HdlImportDiagnostic("invalid-conversion", error.localizedDescription(message(Messages.HdlImportConversionFailed)))))
        }
    }

    fun importJson(
        json: String,
        sources: List<HdlInputFile>,
        top: String? = null,
        undefined: HdlUndefinedPolicy = HdlUndefinedPolicy.REJECT,
        existingProject: CircuitProject? = null,
        registry: ComponentRegistry = ComponentCatalog.registry,
    ): HdlImportResult = when (val parsed = YosysNetlistCodec.read(json, top)) {
        is HdlNetlistResult.Success -> import(parsed.netlist, sources, undefined, existingProject, registry)
        is HdlNetlistResult.Failure -> HdlImportResult.Failure(parsed.diagnostics)
    }
}

private val nativeCellTypes = setOf(
    "\$_AND_", "\$_OR_", "\$_XOR_", "\$_XNOR_", "\$_NAND_", "\$_NOR_", "\$_NOT_", "\$_BUF_",
    "\$_ANDNOT_", "\$_ORNOT_", "\$_MUX_", "\$_NMUX_", "\$_TBUF_", "\$_DFF_P_", "\$_DFF_N_", "\$_DLATCH_P_", "\$_DLATCH_N_",
)
private val asynchronousDff = Regex("\\\$_DFF_([PN])([PN])([01])_")
private val setResetDff = Regex("\\\$_DFFSR_([PN])([PN])([PN])_")
private val wordCellTypes = setOf("\$add", "\$sub", "\$mux", "\$dff", "\$dffe", "\$sdff", "\$sdffe", "\$sdffce")
private fun supportedCell(type: String): Boolean =
    type == HDL_MEMORY_CELL || type in nativeCellTypes || type in wordCellTypes || asynchronousDff.matches(type) || setResetDff.matches(type)

private class HdlConversionFailure(val code: String, val notice: LocalizedText, val module: String?, val cell: String? = null) :
    LocalizedArgumentException(notice)

private data class NativeHdlPort(val source: HdlNetlistPort, val label: String, val direction: PortDirection)

private fun nativePorts(ports: List<HdlNetlistPort>): List<NativeHdlPort> {
    val labels = ports.filter { it.direction != PortDirection.INOUT }.mapTo(mutableSetOf()) { it.name }
    fun unique(base: String): String {
        var label = base
        var suffix = 2
        while (!labels.add(label)) label = "${base}_${suffix++}"
        return label
    }
    return ports.flatMap { port ->
        if (port.direction == PortDirection.INOUT) listOf(
            NativeHdlPort(port, unique("${port.name}_in"), PortDirection.INPUT),
            NativeHdlPort(port, unique("${port.name}_out"), PortDirection.OUTPUT),
        ) else listOf(NativeHdlPort(port, port.name, port.direction))
    }
}

private class ModuleBuilder(
    private val module: HdlNetlistModule,
    private val names: Map<String, String>,
    private val interfaces: Map<String, List<NativeHdlPort>>,
    private val children: Map<String, CircuitDocument>,
    private val undefined: HdlUndefinedPolicy,
    private val registry: ComponentRegistry,
) {
    private val components = mutableListOf<CircuitComponent>()
    private val wires = mutableListOf<WireSegment>()
    private var sequence = 0
    private var temporaryNet = (module.ports.flatMap { it.bits } + module.cells.flatMap { it.ports.values.flatten() })
        .filterIsInstance<HdlBit.Net>().maxOfOrNull { it.id }?.plus(1) ?: 1
    private val constants = mutableSetOf<Char>()
    // Only the module's own drivers feed the drive pin. Exporting the resolved
    // net here would feed a stale value back across a subcircuit after release.
    private val inoutDrives = mutableMapOf<HdlBit, HdlBit>()
    private val wordBuses = linkedMapOf<List<HdlBit>, String>()

    fun build(): CircuitDocument {
        if (module.cells.size > 8192) fail("layout-limit", message(Messages.HdlImportEditableCellsLimit))
        val inouts = module.ports.filter { it.direction == PortDirection.INOUT }
        for (port in inouts) for (bit in port.bits) {
            if (bit !is HdlBit.Net || bit in inoutDrives) {
                fail("bidirectional-port", message(Messages.HdlImportBidirectionalPort, "port" to port.name))
            }
            inoutDrives[bit] = freshNet()
        }
        var inputY = 80
        var outputY = 80
        val pins = interfaces.getValue(module.name).map { port ->
            val input = port.direction == PortDirection.INPUT
            val bidirectional = port.source.direction == PortDirection.INOUT
            val bits = port.source.bits
            val y = if (input) inputY else outputY
            val component = CircuitComponent(
                nextId("port"), if (input) ComponentKind.INPUT else ComponentKind.OUTPUT,
                GridPoint(if (input) 40 else 1100, y), label = port.label,
                attributes = buildMap {
                    put("width", bits.size.toString())
                    put("facing", if (input) "east" else "west")
                    put("type", if (input) "input" else "output")
                    put("appearance", "classic")
                    if (input && bidirectional) put("behavior", "tristate")
                },
            )
            components += component
            val carriesLocalDrive = if (bidirectional) !input else input
            val pinBits = if (carriesLocalDrive) driveBits(bits) else bits
            connect(component.position, pinBits, if (input) 1 else -1)
            if (input) inputY += bits.size * 10 + 100 else outputY += bits.size * 10 + 100
            component
        }
        val childSpan = module.cells.mapNotNull { children[it.type] }.distinctBy { it.name }.maxOfOrNull { child ->
            val pins = child.components.filter { it.kind == ComponentKind.INPUT || it.kind == ComponentKind.OUTPUT }
            pins.zip(symbolPorts(pins)).maxOfOrNull { (pin, port) -> port.position.y + pin.width * 10 + 100 } ?: 0
        } ?: 0
        val rowSpacing = maxOf(1000, childSpan + 100)
        val hasMemory = module.cells.any { it.type == HDL_MEMORY_CELL }
        val columnSpacing = if (hasMemory) 720 else 420
        fun cellPosition(index: Int) = GridPoint(1500 + (index % 12) * columnSpacing, 100 + (index / 12) * rowSpacing)
        for ((index, cell) in module.cells.withIndex()) {
            val position = cellPosition(index)
            when (cell.type) {
                HDL_MEMORY_CELL -> memory(cell, position)
                in children -> subcircuit(cell, position)
                else -> primitive(cell, position)
            }
        }
        for ((index, port) in inouts.withIndex()) {
            controlledBuffer(cellPosition(module.cells.size + index), driveBits(port.bits), HdlBit.Constant('1'), port.bits)
        }
        val wordBusX = maxOf(7000, cellPosition(11).x + columnSpacing)
        for ((index, bus) in wordBuses.entries.withIndex()) {
            val point = GridPoint(wordBusX + (index % 24) * 320, 100 + (index / 24) * 800)
            addTunnel(point, bus.value, bus.key.size, 1)
            connect(point, bus.key, 1)
        }
        for ((index, value) in constants.sorted().withIndex()) {
            val position = GridPoint(80 + index * 100, maxOf(inputY, outputY) + 80)
            components += CircuitComponent(nextId("constant"), ComponentKind.CONSTANT, position,
                attributes = mapOf("width" to "1", "value" to value.toString()))
            tunnel(position, HdlBit.Constant(value), 1)
        }
        val points = components.flatMap { it.layout(registry).ports.map(ComponentTerminal::position) } +
            wires.flatMap { listOf(it.from, it.to) }
        if (points.any { it.x !in -1_000_000..1_000_000 || it.y !in -1_000_000..1_000_000 }) {
            fail("layout-limit", message(Messages.HdlImportCoordinateLimit))
        }
        return CircuitDocument(
            name = names.getValue(module.name), components = components.toList(), wires = wires.distinct(),
            attributes = mapOf("appearance" to "custom", "hdl.module" to module.name),
            extraXml = listOf(appearance(pins)),
            xmlAttributes = if (hasMemory) mapOf("logisim-mobile-source" to "5.1.0-dev") else emptyMap(),
        )
    }

    private fun primitive(cell: HdlNetlistCell, position: GridPoint) {
        fun bit(name: String): HdlBit {
            val bits = cell.ports[name] ?: fail("missing-cell-port", message(Messages.HdlImportCellPortMissing, "type" to cell.type, "port" to name), cell)
            if (bits.size != 1) fail("unnormalized-cell", message(Messages.HdlImportCellNotNormalized, "type" to cell.type, "port" to name), cell)
            return bits.single()
        }
        fun binary(kind: ComponentKind, a: HdlBit = bit("A"), b: HdlBit = bit("B"), y: HdlBit = bit("Y")) =
            gate(kind, position, listOf(a, b), y)
        when (cell.type) {
            in wordCellTypes -> word(cell, position)
            "\$_AND_" -> binary(ComponentKind.AND)
            "\$_OR_" -> binary(ComponentKind.OR)
            "\$_XOR_" -> binary(ComponentKind.XOR)
            "\$_XNOR_" -> binary(ComponentKind.XNOR)
            "\$_NAND_" -> binary(ComponentKind.NAND)
            "\$_NOR_" -> binary(ComponentKind.NOR)
            "\$_NOT_" -> gate(ComponentKind.NOT, position, listOf(bit("A")), bit("Y"))
            "\$_BUF_" -> gate(ComponentKind.BUFFER, position, listOf(bit("A")), bit("Y"))
            "\$_ANDNOT_", "\$_ORNOT_" -> {
                val inverted = freshNet()
                gate(ComponentKind.NOT, position.copy(y = position.y + 100), listOf(bit("B")), inverted)
                binary(if (cell.type == "\$_ANDNOT_") ComponentKind.AND else ComponentKind.OR, bit("A"), inverted)
            }
            "\$_MUX_", "\$_NMUX_" -> {
                val inverted = freshNet(); val low = freshNet(); val high = freshNet(); val out =
                    if (cell.type == "\$_NMUX_") freshNet() else bit("Y")
                gate(ComponentKind.NOT, position.copy(y = position.y + 100), listOf(bit("S")), inverted)
                gate(ComponentKind.AND, position.copy(y = position.y + 200), listOf(bit("A"), inverted), low)
                gate(ComponentKind.AND, position.copy(y = position.y + 300), listOf(bit("B"), bit("S")), high)
                gate(ComponentKind.OR, position, listOf(low, high), out)
                if (cell.type == "\$_NMUX_") gate(ComponentKind.NOT, position.copy(y = position.y + 400), listOf(out), bit("Y"))
            }
            "\$_TBUF_" -> controlledBuffer(position, listOf(bit("A")), bit("E"), driveBits(listOf(bit("Y"))))
            "\$_DFF_P_", "\$_DFF_N_" -> flipFlop(cell, position, bit("D"), bit("C"), bit("Q"),
                if (cell.type == "\$_DFF_N_") "falling" else "rising")
            "\$_DLATCH_P_", "\$_DLATCH_N_" -> {
                val c = CircuitComponent(nextId("latch"), ComponentKind.REGISTER, position,
                    attributes = mapOf("width" to "1", "appearance" to "classic",
                        "trigger" to if (cell.type == "\$_DLATCH_N_") "low" else "high"))
                components += c
                listOf(bit("D"), bit("E"), HdlBit.Constant('1'), HdlBit.Constant('0')).forEachIndexed { i, b ->
                    connect(c.inputPorts(registry)[i], listOf(b), -1)
                }
                connect(c.outputPorts(registry)[0], driveBits(listOf(bit("Q"))), 1)
            }
            else -> {
                val async = asynchronousDff.matchEntire(cell.type)
                val setReset = setResetDff.matchEntire(cell.type)
                if (async != null) {
                    var reset = bit("R")
                    if (async.groupValues[2] == "N") {
                        val inverted = freshNet()
                        gate(ComponentKind.NOT, position.copy(y = position.y + 100), listOf(reset), inverted)
                        reset = inverted
                    }
                    flipFlop(cell, position, bit("D"), bit("C"), bit("Q"),
                        if (async.groupValues[1] == "N") "falling" else "rising",
                        if (async.groupValues[3] == "0") reset else HdlBit.Constant('0'),
                        if (async.groupValues[3] == "1") reset else HdlBit.Constant('0'))
                } else if (setReset != null) {
                    fun activeHigh(port: String, polarity: String, y: Int): HdlBit {
                        if (polarity == "P") return bit(port)
                        val inverted = freshNet()
                        gate(ComponentKind.NOT, position.copy(y = position.y + y), listOf(bit(port)), inverted)
                        return inverted
                    }
                    flipFlop(cell, position, bit("D"), bit("C"), bit("Q"),
                        if (setReset.groupValues[1] == "N") "falling" else "rising",
                        activeHigh("R", setReset.groupValues[3], 100), activeHigh("S", setReset.groupValues[2], 200))
                } else fail("unsupported-cell", message(Messages.HdlImportUnsupportedCell, "type" to cell.type), cell)
            }
        }
    }

    private fun controlledBuffer(position: GridPoint, input: List<HdlBit>, enable: HdlBit, output: List<HdlBit>) {
        val component = CircuitComponent(nextId("tribuf"), ComponentKind.CONTROLLED_BUFFER, position,
            attributes = mapOf("width" to input.size.toString(), "facing" to "east"))
        components += component
        val inputs = component.inputPorts(registry)
        connect(inputs[0], input, -1)
        connect(inputs[1], listOf(enable), -1)
        connect(component.outputPorts(registry)[0], output, 1)
    }

    private fun word(cell: HdlNetlistCell, position: GridPoint) {
        fun invalid(port: String): Nothing = fail("unnormalized-cell",
            message(Messages.HdlImportCellNotNormalized, "type" to cell.type, "port" to port), cell)
        fun port(name: String, width: Int? = null): List<HdlBit> {
            val bits = cell.ports[name] ?: fail("missing-cell-port",
                message(Messages.HdlImportCellPortMissing, "type" to cell.type, "port" to name), cell)
            if (bits.size !in 1..64 || width != null && bits.size != width) invalid(name)
            return bits
        }
        fun flag(name: String): Boolean = when (cell.parameters[name]?.trimStart('0')) {
            "" -> false
            "1" -> true
            else -> invalid(name)
        }
        val low = HdlBit.Constant('0')
        val high = HdlBit.Constant('1')

        fun emit(kind: ComponentKind, at: GridPoint, width: Int, signals: Map<String, List<HdlBit>>,
                 attributes: Map<String, String> = emptyMap()) {
            val component = CircuitComponent(nextId("word"), kind, at, label = if (at == position) cell.name else "",
                attributes = mapOf("width" to width.toString()) + attributes)
            components += component
            for (terminal in component.layout(registry).ports) {
                val signal = signals[terminal.name] ?: continue
                if (signal.size != terminal.width) invalid(terminal.name)
                val direction = if (terminal.direction == PortDirection.OUTPUT) 1 else -1
                val bits = if (direction == 1) driveBits(signal) else signal
                if (bits.size == 1) {
                    tunnel(terminal.position, bits.single(), direction)
                } else {
                    val label = wordBuses.getOrPut(bits) { "hdl_bus_${wordBuses.size}" }
                    connectTunnel(terminal.position, label, bits.size, direction)
                }
            }
        }

        fun mux(at: GridPoint, a: List<HdlBit>, b: List<HdlBit>, select: HdlBit, y: List<HdlBit>) =
            emit(ComponentKind.MULTIPLEXER, at, y.size,
                mapOf("in0" to a, "in1" to b, "select" to listOf(select), "out" to y),
                mapOf("select" to "1", "enable" to "false"))

        when (cell.type) {
            "\$add", "\$sub" -> {
                val output = port("Y")
                val aSigned = flag("A_SIGNED")
                val bSigned = flag("B_SIGNED")
                val signed = aSigned && bSigned
                fun operand(name: String): List<HdlBit> {
                    val bits = port(name)
                    return List(output.size) { bits.getOrElse(it) { if (signed) bits.last() else low } }
                }
                val addition = cell.type == "\$add"
                val kind = if (addition) ArithmeticOperation.ADDER else ArithmeticOperation.SUBTRACTOR
                emit(ComponentKind(kind.name), position, output.size,
                    mapOf("a" to operand("A"), "b" to operand("B"), "result" to output,
                        (if (addition) "carryIn" else "borrowIn") to listOf(low)))
            }
            "\$mux" -> {
                val output = port("Y")
                mux(position, port("A", output.size), port("B", output.size), port("S", 1).single(), output)
            }
            else -> {
                val output = port("Q")
                val width = output.size
                val clock = port("CLK", 1)
                val rising = flag("CLK_POLARITY")
                var data = port("D", width)
                var enable: HdlBit = high
                if (cell.type in setOf("\$dffe", "\$sdffe", "\$sdffce")) {
                    enable = port("EN", 1).single()
                    if (!flag("EN_POLARITY")) {
                        val inverted = freshNet()
                        gate(ComponentKind.NOT, position.copy(y = position.y + 300), listOf(enable), inverted)
                        enable = inverted
                    }
                }
                if (cell.type in setOf("\$sdff", "\$sdffe", "\$sdffce")) {
                    var reset = port("SRST", 1).single()
                    if (!flag("SRST_POLARITY")) {
                        val inverted = freshNet()
                        gate(ComponentKind.NOT, position.copy(y = position.y + 400), listOf(reset), inverted)
                        reset = inverted
                    }
                    val value = cell.parameters["SRST_VALUE"]?.takeIf {
                        it.length == width && it.all { bit -> bit in "01xz" }
                    } ?: invalid("SRST_VALUE")
                    val selected = List(width) { freshNet() }
                    mux(position.copy(y = position.y + 150), data, value.reversed().map { HdlBit.Constant(it) }, reset, selected)
                    data = selected
                    // sdffe checks reset before enable, while sdffce checks enable first.
                    if (cell.type == "\$sdffe") {
                        val resetOrEnable = freshNet()
                        gate(ComponentKind.OR, position.copy(y = position.y + 500), listOf(reset, enable), resetOrEnable)
                        enable = resetOrEnable
                    }
                }
                emit(ComponentKind.REGISTER, position, width,
                    mapOf("data" to data, "clock" to clock, "enable" to listOf(enable),
                        "clear" to listOf(low), "q" to output),
                    mapOf("appearance" to "classic", "trigger" to if (rising) "rising" else "falling"))
            }
        }
    }

    private fun flipFlop(cell: HdlNetlistCell, position: GridPoint, data: HdlBit, clock: HdlBit, q: HdlBit,
                         trigger: String, clear: HdlBit = HdlBit.Constant('0'), preset: HdlBit = HdlBit.Constant('0')) {
        val c = CircuitComponent(nextId("dff"), ComponentKind.D_FLIP_FLOP, position, label = cell.name,
            attributes = mapOf("appearance" to "classic", "trigger" to trigger))
        components += c
        listOf(data, clock, clear, preset).forEachIndexed { i, b -> connect(c.inputPorts(registry)[i], listOf(b), -1) }
        connect(c.outputPorts(registry)[0], driveBits(listOf(q)), 1)
    }

    private fun gate(kind: ComponentKind, position: GridPoint, inputs: List<HdlBit>, output: HdlBit) {
        val c = CircuitComponent(nextId("gate"), kind, position, attributes = buildMap {
            put("width", "1"); put("facing", "east")
            if (kind == ComponentKind.NOT) put("size", "30")
            else if (kind != ComponentKind.BUFFER) { put("inputs", "2"); put("size", "50") }
        })
        components += c
        inputs.forEachIndexed { i, bit -> connect(c.inputPorts(registry)[i], listOf(bit), -1) }
        connect(c.outputPorts(registry)[0], driveBits(listOf(output)), 1)
    }

    private fun memory(cell: HdlNetlistCell, position: GridPoint) {
        val memory = HdlMemory.decode(cell, undefined)
            ?: fail("unsupported-memory", message(Messages.HdlImportUnsupportedCell, "type" to cell.type), cell)
        val component = CircuitComponent(
            nextId("memory"), if (memory.readOnly) ComponentKind.ROM else ComponentKind.RAM, position,
            label = cell.name, attributes = memory.attributes,
        )
        components += component
        for (port in component.layout(registry).ports) {
            val connectedBits = memory.terminals.getValue(port.name)
            val bits = if (port.direction == PortDirection.OUTPUT) driveBits(connectedBits) else connectedBits
            val direction = if (port.direction == PortDirection.INPUT) -1 else 1
            if (bits.size == 1) {
                connect(port.position, bits, direction)
            } else {
                val isDataInput = port.name == MemoryLayouts.inputName(0, 0)
                val split = GridPoint(port.position.x + direction * (if (isDataInput) 200 else 80), port.position.y)
                val label = "${component.id}-${port.name}"
                for (end in listOf(port.position, split)) {
                    components += CircuitComponent(nextId("tunnel"), ComponentKind.TUNNEL, end, label = label,
                        attributes = mapOf("width" to bits.size.toString(), "label" to label,
                            "facing" to if (direction == 1) "west" else "east"))
                }
                connect(split, bits, direction)
            }
        }
    }

    private fun subcircuit(cell: HdlNetlistCell, position: GridPoint) {
        val child = children.getValue(cell.type)
        val pins = child.components.filter { it.kind == ComponentKind.INPUT || it.kind == ComponentKind.OUTPUT }
        val ports = symbolPorts(pins)
        val c = CircuitComponent(nextId("instance"), ComponentKind.SUBCIRCUIT, position, label = cell.name,
            circuitName = names.getValue(cell.type), explicitPorts = ports,
            explicitBounds = symbolBounds(pins), attributes = mapOf("facing" to "east"))
        components += c
        for ((binding, port) in interfaces.getValue(cell.type).zip(ports)) {
            val name = binding.source.name
            val bits = cell.ports[name] ?: fail("missing-instance-port", message(Messages.HdlImportInstancePortMissing, "instance" to cell.name, "port" to name), cell)
            if (bits.size != port.width) fail("instance-width", message(Messages.HdlImportInstanceWidth, "instance" to cell.name, "port" to name), cell)
            val input = port.direction == PortDirection.INPUT
            connect(c.relativePoint(port.position), if (input) bits else driveBits(bits), if (input) -1 else 1)
        }
    }

    private fun driveBits(bits: List<HdlBit>): List<HdlBit> = bits.map { inoutDrives[it] ?: it }

    private fun connect(point: GridPoint, bits: List<HdlBit>, direction: Int) {
        if (bits.size == 1) { tunnel(point, bits.single(), direction); return }
        val splitter = CircuitComponent(nextId("splitter"), ComponentKind.SPLITTER,
            GridPoint(point.x + direction * 40, point.y), attributes = buildMap {
                put("incoming", bits.size.toString()); put("fanout", bits.size.toString())
                put("facing", if (direction == 1) "east" else "west"); put("appear", "left")
                bits.indices.forEach { put("bit$it", it.toString()) }
            })
        components += splitter
        wire(point, splitter.position)
        registry.layout(splitter).ports.drop(1).zip(bits).forEach { (port, bit) -> tunnel(port.position, bit, direction) }
    }

    private fun tunnel(point: GridPoint, bit: HdlBit, direction: Int) {
        val resolved = if (bit is HdlBit.Constant && bit.value == 'x') when (undefined) {
            HdlUndefinedPolicy.REJECT -> fail("undefined-constant", message(Messages.HdlImportUndefinedConstant))
            HdlUndefinedPolicy.ZERO -> HdlBit.Constant('0')
            HdlUndefinedPolicy.ONE -> HdlBit.Constant('1')
        } else bit
        if (resolved == HdlBit.Constant('z')) return
        val label = when (resolved) {
            is HdlBit.Net -> "hdl_n_${resolved.id}"
            is HdlBit.Constant -> { constants += resolved.value; "hdl_const_${resolved.value}" }
        }
        connectTunnel(point, label, 1, direction)
    }

    private fun connectTunnel(point: GridPoint, label: String, width: Int, direction: Int) {
        val terminals = components.flatMap { it.layout(registry).ports.map(ComponentTerminal::position) }.filter { it != point }.toSet()
        val existingEnds = wires.flatMap { listOf(it.from, it.to) }.filter { it != point }.toSet()
        val end = listOf(
            GridPoint(point.x + direction * 20, point.y), GridPoint(point.x, point.y + 20),
            GridPoint(point.x, point.y - 20), GridPoint(point.x - direction * 20, point.y),
        ).firstOrNull { candidate ->
            val segment = WireSegment(point, candidate)
            (terminals + existingEnds).none { segment.contains(it) } && wires.none { it.contains(candidate) }
        } ?: fail("routing-conflict", message(Messages.HdlImportRoutingConflict))
        addTunnel(end, label, width, direction)
        wire(point, end)
    }

    private fun addTunnel(point: GridPoint, label: String, width: Int, direction: Int) {
        components += CircuitComponent(nextId("tunnel"), ComponentKind.TUNNEL, point, label = label,
            attributes = mapOf("width" to width.toString(), "label" to label, "facing" to if (direction == 1) "west" else "east"))
    }

    private fun wire(from: GridPoint, to: GridPoint) { if (from != to) wires += WireSegment(from, to) }
    private fun freshNet(): HdlBit = HdlBit.Net(temporaryNet++)
    private fun nextId(prefix: String) = "hdl-$prefix-${sequence++}"
    private fun fail(code: String, notice: LocalizedText, cell: HdlNetlistCell? = null): Nothing =
        throw HdlConversionFailure(code, notice, module.name, cell?.name)
}

private fun symbolPorts(pins: List<CircuitComponent>): List<ComponentPort> {
    var inputY = 0; var outputY = 0
    return pins.map { pin ->
        val input = pin.kind == ComponentKind.INPUT
        val y = if (input) inputY else outputY
        if (input) inputY += pin.width * 10 + 100 else outputY += pin.width * 10 + 100
        ComponentPort(pin.id, GridPoint(if (input) -120 else 0, y),
            if (input) PortDirection.INPUT else PortDirection.OUTPUT, pin.width, pin.label)
    }
}

private fun symbolBounds(pins: List<CircuitComponent>): ComponentBounds {
    val ports = symbolPorts(pins)
    return ComponentBounds(-120, -20, 0, (ports.maxOfOrNull { it.position.y } ?: 0) + 40)
}

private fun appearance(pins: List<CircuitComponent>): String {
    val bounds = symbolBounds(pins)
    return buildString {
        append("<appear><rect x=\"100\" y=\"100\" width=\"120\" height=\"")
        append(bounds.height).append("\" stroke=\"#000000\" fill=\"none\"/>")
        for ((pin, port) in pins.zip(symbolPorts(pins))) {
            append("<circ-port x=\"").append(220 + port.position.x).append("\" y=\"").append(120 + port.position.y)
                .append("\" dir=\"").append(if (pin.kind == ComponentKind.INPUT) "in" else "out")
                .append("\" pin=\"").append(pin.position.x).append(',').append(pin.position.y).append("\"/>")
        }
        append("<circ-anchor x=\"220\" y=\"120\" facing=\"east\"/></appear>")
    }
}

internal fun hdlXml(text: String): String = buildString {
    text.forEach { append(when (it) {
        '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&apos;"
        '\r' -> "&#13;"; '\n' -> "&#10;"; '\t' -> "&#9;"
        else -> it.toString()
    }) }
}
