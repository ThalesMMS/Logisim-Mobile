package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

internal class HdlEmitter(
    private val module: HdlModule,
    private val language: HdlLanguage,
    private val diagnostics: MutableList<HdlExportDiagnostic>,
    private val forFpga: Boolean,
) {
    private val declarations = mutableListOf<String>()
    private val statements = mutableListOf<String>()
    private val verilog: Boolean get() = language == HdlLanguage.VERILOG
    private val externalPorts = module.ports.map { Triple(it.name, it.direction, it.bits.size) } +
        module.pinBanks.map { Triple(it.name, it.direction, it.width) }

    fun emit(): String {
        for (port in module.ports) for ((bit, signal) in port.bits.withIndex()) {
            if (port.direction == PortDirection.INPUT) assign(signal, index(port.name, bit))
            else assign(index(port.name, bit), signal)
        }
        for ((target, source) in module.timingAssignments) assign(target, source)
        for (cell in module.cells) lower(cell)
        for (instance in module.instances) emitInstance(instance)
        return if (verilog) emitVerilog() else emitVhdl()
    }

    private fun emitVerilog(): String = buildString {
        append("// Generated deterministically from circuit ${comment(module.document.id)}.\n")
        append("// Defined binary inputs; simulator propagation delays are not represented.\n")
        append("`default_nettype none\n")
        append("module ${module.name}")
        if (module.timingParameters.isNotEmpty()) {
            append(module.timingParameters.joinToString(", ", " #(", ")") { "parameter $it = 1'b0" })
        }
        append("(")
        append((externalPorts.map { it.first } + module.timingPorts.map { it.name }).joinToString(", "))
        append(");\n")
        for ((name, direction, width) in externalPorts) {
            append("  ${direction.name.lowercase()} wire [${width - 1}:0] $name;\n")
        }
        for ((name, direction) in module.timingPorts) {
            append("  ${direction.name.lowercase()} wire $name;\n")
        }
        for (signal in module.signals) append("  wire $signal;\n")
        declarations.forEach { append("  ").append(it).append('\n') }
        statements.forEach { append(it.prependIndent("  ")).append('\n') }
        append("endmodule\n`default_nettype wire\n")
    }

    private fun emitVhdl(): String = buildString {
        append("-- Generated deterministically from circuit ${comment(module.document.id)}.\n")
        append("-- VHDL-2008. Defined binary inputs; simulator delays are not represented.\n")
        append("library ieee;\nuse ieee.std_logic_1164.all;\nuse ieee.numeric_std.all;\n\n")
        append("entity ${module.name} is\n")
        if (module.timingParameters.isNotEmpty()) {
            append(module.timingParameters.joinToString(";\n", "  generic (\n", "\n  );\n") { "    $it : boolean := false" })
        }
        val ports = externalPorts.map { (name, direction, width) ->
            "    $name : ${vhdlMode(direction)} std_logic_vector(${width - 1} downto 0)"
        } + module.timingPorts.map { (name, direction) -> "    $name : ${vhdlMode(direction)} std_logic" }
        if (ports.isNotEmpty()) {
            append(ports.joinToString(";\n", "  port (\n", "\n  );\n"))
        }
        append("end entity;\n\narchitecture rtl of ${module.name} is\n")
        append("  function hdl_choose(condition : boolean; yes_value, no_value : std_logic_vector) return std_logic_vector is\n")
        append("  begin\n    if condition then return yes_value; else return no_value; end if;\n  end function;\n")
        for (signal in module.signals) append("  signal $signal : std_logic;\n")
        declarations.forEach { append("  ").append(it).append('\n') }
        append("begin\n")
        statements.forEach { append(it.prependIndent("  ")).append('\n') }
        append("end architecture;\n")
    }

    private fun vhdlMode(direction: PortDirection): String = when (direction) {
        PortDirection.INPUT -> "in"
        PortDirection.OUTPUT -> "out"
        PortDirection.INOUT -> "inout"
    }

    private fun lower(cell: HdlCell) {
        val kind = cell.component.kind.name
        try {
            when (kind) {
                "CONSTANT", "POWER", "GROUND" -> constant(cell)
                "AND", "OR", "XOR", "NAND", "NOR", "XNOR", "NOT", "BUFFER", "ODD_PARITY", "EVEN_PARITY" -> gate(cell)
                "TTL_7400", "TTL_7402", "TTL_7404", "TTL_7408", "TTL_7432", "TTL_7486" -> ttlGatePackage(cell)
                "TTL_7474" -> ttl7474(cell)
                "BIT_EXTENDER" -> extender(cell)
                "MULTIPLEXER", "DEMULTIPLEXER", "DECODER", "BIT_SELECTOR", "PRIORITY_ENCODER" -> plexer(cell)
                "ADDER", "SUBTRACTOR", "NEGATOR", "COMPARATOR", "MIN_MAX", "MULTIPLIER", "SHIFTER", "ABSOLUTE" -> arithmetic(cell)
                "D_FLIP_FLOP", "T_FLIP_FLOP", "JK_FLIP_FLOP", "SR_FLIP_FLOP", "REGISTER", "COUNTER" -> storage(cell)
                "BIT_FINDER" -> bitFinder(cell)
                "EXPONENTIATOR" -> exponentiator(cell)
                "SQUARE_ROOT" -> squareRoot(cell)
                "SHIFT_REGISTER" -> shiftRegister(cell)
                "RANDOM" -> random(cell)
                "ROM" -> rom(cell)
                "RAM" -> ram(cell)
                "VHDL_ENTITY" -> if (verilog) unsupported(cell) else cell.vhdl?.let { emitVhdlEntity(cell, it) }
                "CONTROLLED_BUFFER", "CONTROLLED_INVERTER" -> controlledBuffer(cell)
                "PORT_IO" -> portIo(cell)
                "REPTAR_LOCAL_BUS" -> if (verilog) unsupported(cell) else reptarLocalBus(cell)
                "TRANSISTOR", "TRANSMISSION_GATE", "PULL_RESISTOR" ->
                    error(cell, "HDL_TRISTATE_COMPONENT", "Tri-state and weak-driver components are outside the synthesizable RTL profile.")
                else -> unsupported(cell)
            }
        } catch (exception: IllegalArgumentException) {
            error(cell, "HDL_UNSUPPORTED_CONFIGURATION", exception.message ?: "The component configuration is not supported.",
                exception.localizedDescription(LocalizedText.literal("The component configuration is not supported.")))
        }
    }

    private fun unsupported(cell: HdlCell) {
        error(cell, "HDL_UNSUPPORTED_COMPONENT", "No synthesizable HDL mapping is registered for ${cell.component.kind.name}.")
    }

    private fun emitVhdlEntity(cell: HdlCell, design: HdlIpDesign) {
        val inputs = cell.inputs.associateBy { it.terminal.name }
        val outputs = cell.outputs.withIndex().associate { (index, endpoint) -> endpoint.terminal.name to index }
        val ports = design.ports.joinToString(", ") { port ->
            val scalar = port.kind == "std_logic"
            val actual = if (port.direction == PortDirection.INPUT) {
                val endpoint = requireNotNull(inputs[port.name]) { "Input ${port.name} is missing." }
                if (scalar) valueBit(cell, endpoint, null) else value(cell, endpoint, null)
            } else {
                val outputIndex = requireNotNull(outputs[port.name]) { "Output ${port.name} is missing." }
                val signal = output(cell, outputIndex)
                if (scalar) index(signal, 0) else signal
            }
            "${port.name} => $actual"
        }
        val generics = design.vhdlGenericActuals.entries.joinToString(", ") { (name, value) -> "$name => $value" }
        statements += buildString {
            append("${cell.prefix} : entity work.${design.topLevel}")
            if (generics.isNotEmpty()) append(" generic map ($generics)")
            append(" port map ($ports);")
        }
    }

    private fun constant(cell: HdlCell) {
        val output = output(cell, 0)
        val width = cell.outputs[0].width
        val value = when (cell.component.kind.name) {
            "GROUND" -> 0uL
            "POWER" -> ULong.MAX_VALUE
            else -> parseUnsigned(cell.component.attributes["value"] ?: "1")
                ?: throw LocalizedArgumentException(message("hdl_reason_constant_value"))
        }
        assign(output, vectorLiteral(width, value))
    }

    private fun gate(cell: HdlCell) {
        requireLocalized((cell.component.attributes["out"] ?: "01") == "01") { message("hdl_reason_open_collector") }
        val kind = cell.component.kind.name
        val result = output(cell, 0)
        val width = cell.outputs[0].width
        val and = kind in setOf("AND", "NAND")
        requireLocalized(cell.inputs.any { endpoint -> endpoint.driven.any { it } }) { message("hdl_reason_undriven_gate") }
        val inputs = cell.inputs.mapIndexed { index, endpoint ->
            val negated = cell.component.attributes["negate$index"] == "true"
            val raw = value(cell, endpoint, if (negated) !and else and)
            if (negated) inverted(raw) else raw
        }
        val expression = when (kind) {
            "NOT" -> inverted(inputs.single())
            "BUFFER" -> inputs.single()
            "AND", "NAND" -> inputs.joinToString(if (verilog) " & " else " and ") { "($it)" }
            "OR", "NOR" -> inputs.joinToString(if (verilog) " | " else " or ") { "($it)" }
            "XOR", "XNOR" -> if (cell.component.attributes["xor"] == "odd" || inputs.size <= 2)
                inputs.joinToString(if (verilog) " ^ " else " xor ") { "($it)" }
            else exactlyOne(inputs)
            "ODD_PARITY", "EVEN_PARITY" -> inputs.joinToString(if (verilog) " ^ " else " xor ") { "($it)" }
            else -> vectorLiteral(width, 0uL)
        }
        assign(result, if (kind in setOf("NAND", "NOR", "XNOR", "EVEN_PARITY")) inverted(expression) else expression)
    }

    private fun controlledBuffer(cell: HdlCell) {
        val data = value(cell, endpoint(cell, 0, "data"), null)
        val enable = control(cell, 1, false, "enable")
        val driven = if (cell.component.kind == ComponentKind.CONTROLLED_INVERTER) inverted(data) else data
        assign(output(cell, 0), conditional(isHigh(enable), driven, released(cell.outputs[0].width)))
    }

    private fun reptarLocalBus(cell: HdlCell) {
        val ports = cell.reptarBanks.map { port -> "${requireNotNull(port.reptarBank).suffix} => ${port.name}" }.toMutableList()
        val inputs = cell.inputs.associateBy { it.terminal.name }
        val outputs = cell.outputs.withIndex().associate { (index, endpoint) -> endpoint.terminal.name to index }
        for ((port, terminal) in mapOf(
            "wait_request" to "wait", "interrupt_request" to "irq", "data_tristate" to "dataTristate", "data_in" to "dataIn",
        )) {
            val endpoint = inputs.getValue(terminal)
            val default = terminal == "dataTristate"
            val actual = if (endpoint.width == 1) valueBit(cell, endpoint, default)
                else "${cell.prefix}_$port".also { signal ->
                    declare(signal, endpoint.width)
                    assign(signal, value(cell, endpoint, default))
                }
            ports += "$port => $actual"
        }
        for ((port, terminal) in mapOf(
            "chip_select" to "chipSelect", "address_latch" to "addressLatch", "output_enable" to "outputEnable",
            "write_enable" to "writeEnable", "data_out" to "dataOut", "address_out" to "address",
        )) {
            val outputIndex = outputs.getValue(terminal)
            val signal = output(cell, outputIndex)
            ports += "$port => ${if (cell.outputs[outputIndex].width == 1) index(signal, 0) else signal}"
        }
        statements += "${cell.prefix}_bridge : entity work.${ReptarLocalBusHdl.ENTITY} port map (${ports.joinToString(", ")});"
    }

    private fun portIo(cell: HdlCell) {
        val port = requireNotNull(cell.pinBank)
        if (port.direction != PortDirection.OUTPUT) assign(output(cell, 0), port.name)
        if (port.direction == PortDirection.INPUT) return

        val data = endpoint(cell, 0, "data")
        if (port.direction == PortDirection.OUTPUT) {
            assign(port.name, value(cell, data, null))
            return
        }
        val enable = endpoint(cell, 0, "enable")
        if (enable.width == 1) {
            assign(port.name, conditional(isHigh(valueBit(cell, enable, false)), value(cell, data, null), released(port.width)))
            return
        }
        val z = if (verilog) "1'bz" else "'Z'"
        for (bit in 0 until port.width) {
            val active = if (enable.driven[bit]) enable.bits[bit] else bit(false)
            val drive = if (data.driven[bit]) data.bits[bit] else missing(cell, data.terminal.name)
            assign(index(port.name, bit), if (verilog) conditional(isHigh(active), drive, z)
                else "$drive when ${isHigh(active)} else $z")
        }
    }

    private fun released(width: Int): String =
        if (verilog) "${width}'bz" else "std_logic_vector'(${width - 1} downto 0 => 'Z')"

    private fun exactlyOne(inputs: List<String>): String = inputs.indices.joinToString(if (verilog) " | " else " or ") { selected ->
        inputs.mapIndexed { index, value -> if (index == selected) "($value)" else inverted(value) }
            .joinToString(if (verilog) " & " else " and ").let { "($it)" }
    }

    private fun ttlGatePackage(cell: HdlCell) {
        requireLocalized(cell.component.attributes["VccGndPorts"] != "true") { message("hdl_reason_ttl_power_pins") }
        val kind = cell.component.kind.name
        val pinGroups = when (kind) {
            "TTL_7402" -> TtlCombinational.norGatePins
            "TTL_7404" -> TtlCombinational.hexGatePins
            else -> TtlCombinational.quadGatePins
        }
        val operator = when (kind) {
            "TTL_7400", "TTL_7408" -> if (verilog) "&" else "and"
            "TTL_7402", "TTL_7432" -> if (verilog) "|" else "or"
            "TTL_7486" -> if (verilog) "^" else "xor"
            else -> null
        }
        val invert = kind == "TTL_7400" || kind == "TTL_7402" || kind == "TTL_7404"
        val inputs = cell.inputs.associateBy { it.terminal.name }
        val outputs = cell.outputs.associateBy { it.terminal.name }
        for ((output, pins) in pinGroups) {
            val values = pins.map { pin -> valueBit(cell, requireNotNull(inputs["pin$pin"]), default = false) }
            val expression = if (operator == null) values.single() else values.joinToString(" $operator ")
            val target = requireNotNull(outputs["pin$output"]).bits.single()
            assign(target, if (invert) inverted(expression) else expression)
        }
    }

    private fun ttl7474(cell: HdlCell) {
        requireLocalized(cell.component.attributes["VccGndPorts"] != "true") { message("hdl_reason_ttl_power_pins") }
        val inputs = cell.inputs.associateBy { it.terminal.name }
        val outputs = cell.outputs.mapIndexed { index, endpoint -> endpoint.terminal.name to index }.toMap()
        fun input(pin: Int, timing: Boolean = false): String {
            val name = "pin$pin"
            val source = valueBit(cell, inputs.getValue(name), default = false)
            if (!timing || source != bit(false)) return source
            val signal = "${cell.prefix}_$name"
            declare(signal, 1)
            val scalar = index(signal, 0)
            assign(scalar, source)
            return scalar
        }
        for (pins in listOf(listOf(1, 2, 3, 4, 5, 6), listOf(13, 12, 11, 10, 9, 8))) {
            val (clearPin, dataPin, clockPin, presetPin, qPin) = pins
            val clear = input(clearPin, timing = true)
            val data = input(dataPin)
            val clock = input(clockPin, timing = true)
            val preset = input(presetPin, timing = true)
            val q = output(cell, outputs.getValue("pin$qPin"), procedural = true)
            val qBit = index(q, 0)
            assign(output(cell, outputs.getValue("pin${pins.last()}")), inverted(q))
            statements += if (verilog) {
                val presetSignal = "${q}_effective_preset"
                declare(presetSignal, 1)
                val effectivePreset = index(presetSignal, 0)
                assign(effectivePreset, "$preset | ~$clear")
                """
                    always @(posedge $clock or negedge $clear or negedge $effectivePreset) begin
                      if (!$clear) $qBit <= 1'b0;
                      else if (!$effectivePreset) $qBit <= 1'b1;
                      else $qBit <= $data;
                    end
                """.trimIndent()
            } else {
                val sensitivity = listOf(clock, clear, preset).distinct().joinToString(", ")
                """
                    ${q}_process : process($sensitivity)
                    begin
                      if $clear = '0' then
                        $qBit <= '0';
                      elsif $preset = '0' then
                        $qBit <= '1';
                      elsif rising_edge($clock) then
                        $qBit <= $data;
                      end if;
                    end process;
                """.trimIndent()
            }
        }
    }

    private fun extender(cell: HdlCell) {
        val data = endpoint(cell, 0, "in", "data")
        val output = output(cell, 0)
        val width = cell.outputs[0].width
        val bits = data.bits.mapIndexed { bit, name -> if (data.driven[bit]) name else missing(cell, data.terminal.name) }
        val extension = when (cell.component.attributes["type"] ?: "sign") {
            "zero" -> bit(false)
            "one" -> bit(true)
            "sign" -> bits.last()
            "input" -> control(cell, 1, false, "extend")
            else -> throw LocalizedArgumentException(message("hdl_reason_extension_mode"))
        }
        assign(output, vector(List(width) { index -> bits.getOrElse(index) { extension } }))
    }

    private fun plexer(cell: HdlCell) {
        val kind = cell.component.kind.name
        requireLocalized(cell.component.attributes["tristate"] != "true" && cell.component.attributes["disabled"] != "Z") {
            message("hdl_reason_floating_output")
        }
        when (kind) {
            "MULTIPLEXER" -> {
                val count = 1 shl cell.component.attributeInt("select", 1)
                val select = endpoint(cell, count, "select")
                val enabled = optionalControl(cell, "enable", true, count + 1)
                val output = output(cell, 0, procedural = true)
                val choices = (0 until count).map { index -> value(cell, endpoint(cell, index, "in$index"), null) }
                caseAssignment(cell, output, cell.outputs[0].width, value(cell, select, null), choices, enabled)
            }
            "DEMULTIPLEXER" -> {
                val data = value(cell, endpoint(cell, 0, "data"), null)
                val select = value(cell, endpoint(cell, 1, "select"), null)
                val enabled = optionalControl(cell, "enable", true)
                for (index in cell.outputs.indices) {
                    val output = output(cell, index)
                    val chosen = boolAnd(enabled, equal(select, vectorLiteral(endpoint(cell, 1, "select").width, index.toULong())))
                    assign(output, conditional(chosen, data, vectorLiteral(cell.outputs[index].width, 0uL)))
                }
            }
            "DECODER" -> {
                val selectEndpoint = endpoint(cell, 0, "select")
                val select = value(cell, selectEndpoint, null)
                val enabled = optionalControl(cell, "enable", true, 1)
                for (index in cell.outputs.indices) assign(output(cell, index),
                    conditional(boolAnd(enabled, equal(select, vectorLiteral(selectEndpoint.width, index.toULong()))),
                        vectorLiteral(1, 1uL), vectorLiteral(1, 0uL)))
            }
            "BIT_SELECTOR" -> {
                val dataEndpoint = endpoint(cell, 0, "data")
                val selectEndpoint = endpoint(cell, 1, "select")
                val data = value(cell, dataEndpoint, null)
                val select = value(cell, selectEndpoint, null)
                val width = cell.outputs[0].width
                val count = (dataEndpoint.width + width - 1) / width
                val chunks = (0 until count).map { group ->
                    vector(List(width) { index -> dataEndpoint.bits.getOrNull(group * width + index) ?: bit(false) })
                }
                // Validate the complete input even when padding means a group has fewer bits.
                require(data.isNotBlank())
                caseAssignment(cell, output(cell, 0, procedural = true), width, select, chunks, truth())
            }
            "PRIORITY_ENCODER" -> {
                val encoded = cell.outputs.indexOfFirst { it.terminal.name == "out" }.takeIf { it >= 0 } ?: 0
                val width = cell.outputs[encoded].width
                val count = 1 shl width
                val enabled = optionalControl(cell, "enable", true)
                val inputs = (0 until count).map { control(cell, it, false, "in$it") }
                var result = vectorLiteral(width, 0uL)
                for (index in inputs.indices) result = conditional(isHigh(inputs[index]), vectorLiteral(width, index.toULong()), result)
                assign(output(cell, encoded), conditional(enabled, result, vectorLiteral(width, 0uL)))
                val present = inputs.joinToString(if (verilog) " || " else " or ") { isHigh(it) }.ifEmpty(::falsity)
                cell.outputs.forEachIndexed { index, endpoint ->
                    if (index != encoded) {
                        val condition = if (endpoint.terminal.name == "enableOut") boolAnd(enabled, boolNot(present)) else boolAnd(enabled, present)
                        assign(output(cell, index), conditional(condition, vectorLiteral(1, 1uL), vectorLiteral(1, 0uL)))
                    }
                }
            }
        }
    }

    private fun arithmetic(cell: HdlCell) {
        val kind = cell.component.kind.name
        val first = value(cell, endpoint(cell, 0, "a", "data"), null)
        val width = cell.inputs[0].width
        fun second() = value(cell, endpoint(cell, 1, "b", "distance"), null)
        when (kind) {
            "ADDER", "SUBTRACTOR" -> {
                val second = second()
                val carry = control(cell, 2, false, "carryIn", "borrowIn")
                val temporary = "${cell.prefix}_wide"
                declare(temporary, width + 1)
                val symbol = if (kind == "ADDER") "+" else "-"
                val expression = if (verilog) "{1'b0, $first} $symbol {1'b0, $second} $symbol $carry"
                    else "std_logic_vector(resize(unsigned($first), ${width + 1}) $symbol resize(unsigned($second), ${width + 1}) $symbol unsigned'(0 => $carry))"
                assign(temporary, expression)
                assign(output(cell, 0), slice(temporary, width - 1, 0))
                if (cell.outputs.size > 1) assign(output(cell, 1), vector(listOf(index(temporary, width))))
            }
            "NEGATOR" -> assign(output(cell, 0), if (verilog) "-$first" else "std_logic_vector(-signed($first))")
            "ABSOLUTE" -> {
                val negative = isHigh(endpoint(cell, 0, "a", "data").bits[width - 1])
                val negated = if (verilog) "-$first" else "std_logic_vector(-signed($first))"
                assign(output(cell, 0), conditional(negative, negated, first))
                if (cell.outputs.size > 1) {
                    val minimum = 1uL shl (width - 1)
                    assign(output(cell, 1), conditional(equal(first, vectorLiteral(width, minimum)), vectorLiteral(1, 1uL), vectorLiteral(1, 0uL)))
                }
            }
            "COMPARATOR", "MIN_MAX" -> {
                val second = second()
                val signed = cell.component.attributes["mode"] != "unsigned"
                val a = numeric(first, signed)
                val b = numeric(second, signed)
                if (kind == "COMPARATOR") {
                    for ((index, op) in listOf(">", if (verilog) "==" else "=", "<").withIndex()) {
                        assign(output(cell, index), conditional("($a $op $b)", vectorLiteral(1, 1uL), vectorLiteral(1, 0uL)))
                    }
                } else {
                    assign(output(cell, 0), conditional("($a < $b)", first, second))
                    assign(output(cell, 1), conditional("($a > $b)", first, second))
                }
            }
            "MULTIPLIER" -> {
                val second = second()
                val carry = value(cell, endpoint(cell, 2, "carryIn"), false)
                val mode = cell.component.multiplierMode(width)
                requireLocalized(mode != "signedUnsigned") { message("hdl_reason_mixed_multiply") }
                val signed = mode != "unsigned"
                val temporary = "${cell.prefix}_product"
                declare(temporary, width * 2)
                val expression = if (verilog) {
                    if (signed) {
                        val aSign = endpoint(cell, 0, "a").bits.last()
                        val bSign = endpoint(cell, 1, "b").bits.last()
                        val carrySign = endpoint(cell, 2, "carryIn").let { if (it.driven.last()) it.bits.last() else bit(false) }
                        "\$signed({{${width}{$aSign}}, $first}) * \$signed({{${width}{$bSign}}, $second}) + \$signed({{${width}{$carrySign}}, $carry})"
                    } else "${numeric(first, false)} * ${numeric(second, false)} + {{${width}{1'b0}}, $carry}"
                } else "std_logic_vector(${numeric(first, signed)} * ${numeric(second, signed)} + resize(${numeric(carry, signed)}, ${width * 2}))"
                assign(temporary, expression)
                assign(output(cell, 0), slice(temporary, width - 1, 0))
                assign(output(cell, 1), slice(temporary, width * 2 - 1, width))
            }
            "SHIFTER" -> {
                val distance = second()
                val shift = cell.component.attributes["shift"] ?: "ll"
                val expression = if (verilog) when (shift) {
                    "ll" -> "$first << $distance"
                    "lr" -> "$first >> $distance"
                    "ar" -> "\$signed($first) >>> $distance"
                    "rl" -> "($first << ($distance % $width)) | ($first >> (($width - ($distance % $width)) % $width))"
                    "rr" -> "($first >> ($distance % $width)) | ($first << (($width - ($distance % $width)) % $width))"
                    else -> throw LocalizedArgumentException(message("hdl_reason_shift_mode"))
                } else {
                    val function = when (shift) { "ll" -> "shift_left"; "lr", "ar" -> "shift_right"; "rl" -> "rotate_left"; "rr" -> "rotate_right"; else -> throw LocalizedArgumentException(message("hdl_reason_shift_mode")) }
                    "std_logic_vector($function(${numeric(first, shift == "ar")}, to_integer(unsigned($distance))))"
                }
                assign(output(cell, 0), expression)
            }
        }
    }

    private fun storage(cell: HdlCell) {
        val kind = cell.component.kind.name
        val qIndex = cell.outputs.indexOfFirst { it.terminal.name == "q" }.takeIf { it >= 0 } ?: 0
        val width = cell.outputs[qIndex].width
        val q = output(cell, qIndex, procedural = true, initial = 0uL)
        val clear = control(cell, if (kind == "REGISTER" || kind == "COUNTER") 3 else 2, false, "clear")
        val preset = if (kind.endsWith("FLIP_FLOP")) control(cell, 3, false, "preset") else bit(false)
        val trigger = cell.component.attributes["trigger"] ?: "rising"
        requireLocalized(trigger in setOf("rising", "falling", "high", "low")) { message("hdl_reason_clock_trigger") }
        val fpgaClock = cell.fpgaClock
        val clock = when (trigger) {
            "rising", "falling" -> fpgaClock?.edgeClock
            // Evolution feeds its inverted clock-tree level to the ordinary low-level latch.
            "low" -> fpgaClock?.invertedLevel
            else -> null
        } ?: control(cell, 1, null, "clock")
        var enable = truth()
        var data = when (kind) {
            "D_FLIP_FLOP", "REGISTER" -> value(cell, endpoint(cell, 0, "d", "data"), null)
            "T_FLIP_FLOP" -> conditional(isHigh(control(cell, 0, false, "t")), inverted(q), q)
            "JK_FLIP_FLOP" -> {
                val j = control(cell, 0, false, "j")
                val k = control(cell, 1, false, "k")
                conditional(boolAnd(isHigh(j), isHigh(k)), inverted(q), conditional(isHigh(j), vectorLiteral(1, 1uL), conditional(isHigh(k), vectorLiteral(1, 0uL), q)))
            }
            "SR_FLIP_FLOP" -> {
                val s = control(cell, 0, false, "s")
                val r = control(cell, 1, false, "r")
                if (fpgaClock != null) {
                    // Evolution's FPGA next state holds Q when both S and R are high.
                    "(($q & $s) | (~($r) & $s) | ($q & ~($r)))"
                } else {
                    requireLocalized(s == bit(false) || r == bit(false)) { message("hdl_reason_sr_undefined") }
                    conditional(isHigh(s), vectorLiteral(1, 1uL), conditional(isHigh(r), vectorLiteral(1, 0uL), q))
                }
            }
            "COUNTER" -> counterNext(cell, q, width, clear)
            else -> q
        }
        if (kind == "REGISTER") enable = isHigh(control(cell, 2, true, "enable"))
        if (kind.endsWith("FLIP_FLOP")) enable = optionalControl(cell, "enable", true)
        if (kind == "COUNTER") {
            val load = isHigh(control(cell, 2, false, "load"))
            val countEnable = isHigh(control(cell, 4, true, "enable"))
            enable = boolOr(load, countEnable)
        }
        if (kind in setOf("D_FLIP_FLOP", "T_FLIP_FLOP", "JK_FLIP_FLOP", "SR_FLIP_FLOP") && cell.outputs.size > 1) {
            val other = cell.outputs.indices.first { it != qIndex }
            assign(output(cell, other), inverted(q))
        }
        if (verilog) {
            val edge = if (trigger == "falling") "negedge" else "posedge"
            val sensitivity = if (trigger in setOf("high", "low")) "*" else
                "$edge $clock" + listOf(clear, preset).filter { it != bit(false) }.distinct().joinToString("") { " or posedge $it" }
            val condition = when (trigger) {
                "high" -> boolAnd(isHigh(clock), enable)
                "low" -> boolAnd(boolNot(isHigh(clock)), enable)
                else -> fpgaClock?.let { boolAnd(it.edgeEnable(trigger == "falling"), enable) } ?: enable
            }
            statements += "always @($sensitivity) begin\n  if (${isHigh(clear)}) $q <= ${vectorLiteral(width, 0uL)};\n" +
                (if (preset != bit(false)) "  else if (${isHigh(preset)}) $q <= ${vectorLiteral(width, ULong.MAX_VALUE)};\n" else "") +
                "  else if ($condition) $q <= $data;\nend"
        } else {
            val falling = trigger == "falling"
            val isLatch = trigger == "high" || trigger == "low"
            val sampleClock = if (fpgaClock != null && !isLatch) vhdlFpgaSampleClock(cell, clock, falling) else clock
            val sensitivity = if (isLatch) "all" else
                listOf(sampleClock, clear, preset).filter { it != bit(false) }.distinct().joinToString(", ")
            val event = when {
                trigger == "high" -> isHigh(clock)
                trigger == "low" -> boolNot(isHigh(clock))
                falling && fpgaClock == null -> "falling_edge($sampleClock)"
                else -> "rising_edge($sampleClock)"
            }
            if (fpgaClock != null && !isLatch) enable = boolAnd(fpgaClock.edgeEnable(falling), enable)
            statements += "${cell.prefix}_process : process($sensitivity)\nbegin\n  if ${isHigh(clear)} then\n    $q <= ${vectorLiteral(width, 0uL)};\n" +
                (if (preset != bit(false)) "  elsif ${isHigh(preset)} then\n    $q <= ${vectorLiteral(width, ULong.MAX_VALUE)};\n" else "") +
                "  elsif $event then\n    if $enable then $q <= $data; end if;\n  end if;\nend process;"
        }
    }

    private fun counterNext(cell: HdlCell, q: String, width: Int, clear: String): String {
        val mask = if (width == 64) ULong.MAX_VALUE else (1uL shl width) - 1uL
        val maximum = SequentialLibrary.counterMaximum(cell.component.attributes, width)
        val loadEndpoint = endpoint(cell, 2, "load")
        val load = isHigh(valueBit(cell, loadEndpoint, false))
        val count = isHigh(control(cell, 4, true, "enable"))
        val up = if (cell.component.isOriginalSequential) boolNot(boolAnd(load, count))
            else isHigh(control(cell, 5, true, "up"))
        val loading = if (cell.component.isOriginalSequential) boolAnd(load, boolNot(count)) else load
        val rawLoad = value(cell, endpoint(cell, 0, "data"), if (loadEndpoint.driven.any { it } || cell.component.attributes["ongoal"] == "load") null else false)
        val limit = vectorLiteral(width, maximum)
        val loaded = if (forFpga) rawLoad else {
            val maskedLoad = "($rawLoad ${if (verilog) "&" else "and"} $limit)"
            val signedLoad = cell.component.isOriginalSequential && width == 32
            conditional("(${numeric(rawLoad, signedLoad)} > ${numeric(limit, signedLoad)})", maskedLoad, rawLoad)
        }
        val top = equal(q, vectorLiteral(width, maximum))
        val bottom = equal(q, vectorLiteral(width, 0uL))
        val terminal = conditionalBoolean(up, top, bottom)
        val advanced = if (verilog) conditional(up, "$q + ${vectorLiteral(width, 1uL)}", "$q - ${vectorLiteral(width, 1uL)}")
            else conditional(up, "std_logic_vector(unsigned($q) + 1)", "std_logic_vector(unsigned($q) - 1)")
        val onGoal = when (cell.component.attributes["ongoal"] ?: "wrap") {
            "stay" -> q
            "load" -> loaded
            "continue" -> advanced
            "wrap" -> conditional(up, vectorLiteral(width, 0uL), vectorLiteral(width, maximum))
            else -> throw LocalizedArgumentException(message("hdl_reason_counter_limit"))
        }
        if (cell.outputs.size > 1) {
            val carry = conditional(boolAnd(boolNot(isHigh(clear)), terminal), vectorLiteral(1, 1uL), vectorLiteral(1, 0uL))
            assign(output(cell, 1), carry)
        }
        return conditional(loading, loaded, conditional(terminal, onGoal, advanced))
    }

    private fun bitFinder(cell: HdlCell) {
        val input = endpoint(cell, 0, "data")
        val bits = input.bits.mapIndexed { bit, signal ->
            if (input.driven[bit]) signal else missing(cell, input.terminal.name)
        }
        val mode = cell.component.attributes["type"] ?: "low1"
        val target = bit(mode.endsWith('1'))
        val width = cell.outputs[1].width
        val order = if (mode.startsWith("high")) bits.indices else bits.indices.reversed()
        val matches = bits.map { equal(it, target) }
        val found = matches.joinToString(if (verilog) " || " else " or ")
        assign(output(cell, 0), conditional(found, vectorLiteral(1, 1uL), vectorLiteral(1, 0uL)))
        val result = output(cell, 1, procedural = verilog)
        if (verilog) {
            val body = order.joinToString("\n") { bit -> "  if (${matches[bit]}) $result = ${vectorLiteral(width, bit.toULong())};" }
            statements += "always @(*) begin\n  $result = ${vectorLiteral(width, 0uL)};\n$body\nend"
        } else {
            val choices = order.reversed().joinToString("\n  ") { bit ->
                "${vectorLiteral(width, bit.toULong())} when ${matches[bit]} else"
            }
            statements += "$result <= $choices\n  ${vectorLiteral(width, 0uL)};"
        }
    }

    private fun exponentiator(cell: HdlCell) {
        val base = endpoint(cell, 0, "base")
        val exponent = endpoint(cell, 1, "exponent")
        val width = base.width
        val wide = width * 2
        val baseValue = value(cell, base, null)
        val exponentValue = value(cell, exponent, null)
        val signed = (cell.component.attributes["mode"] ?: "unsigned") != "unsigned"
        val power = "${cell.prefix}_power"
        val result = "${cell.prefix}_pow"
        val exp = "${cell.prefix}_exponent"
        declare(exp, width)
        assign(exp, exponentValue)
        declare(result, wide, procedural = verilog)
        if (verilog) {
            declare(power, wide, procedural = true)
            val i = "${cell.prefix}_bit"
            declarations += "integer $i;"
            val initialBase = if (signed) "\$signed($baseValue)" else baseValue
            val negative = if (signed) "\n  if ($exp[${width - 1}] && $baseValue != ${vectorLiteral(width, 1uL)}) $result = 0;" else ""
            statements += """
                always @(*) begin
                  $power = $initialBase;
                  $result = 1;
                  for ($i = 0; $i < $width; $i = $i + 1) begin
                    if ($exp[$i]) $result = $result * $power;
                    $power = $power * $power;
                  end$negative
                end
            """.trimIndent()
        } else {
            val initialBase = if (signed) "unsigned(resize(signed($baseValue), $wide))" else "resize(unsigned($baseValue), $wide)"
            val negative = if (signed) "\n  if $exp(${width - 1}) = '1' and $baseValue /= ${vectorLiteral(width, 1uL)} then\n    product := (others => '0');\n  end if;" else ""
            statements += """
                ${cell.prefix}_exponentiate : process(all)
                  variable factor, product : unsigned(${wide - 1} downto 0);
                begin
                  factor := $initialBase;
                  product := to_unsigned(1, $wide);
                  for i in 0 to ${width - 1} loop
                    if $exp(i) = '1' then product := resize(product * factor, $wide); end if;
                    factor := resize(factor * factor, $wide);
                  end loop;$negative
                  $result <= std_logic_vector(product);
                end process;
            """.trimIndent()
        }
        assign(output(cell, 0), slice(result, width - 1, 0))
        assign(output(cell, 1), slice(result, wide - 1, width))
    }

    private fun squareRoot(cell: HdlCell) {
        val width = cell.inputs[0].width
        val low = value(cell, endpoint(cell, 0, "data"), null)
        val high = value(cell, endpoint(cell, 1, "upper"), false)
        val root = output(cell, 0, procedural = verilog)
        val outputRemainder = output(cell, 1)
        val radicand = "${cell.prefix}_radicand"
        val remainder = "${cell.prefix}_remainder"
        declare(radicand, 2 * width)
        assign(radicand, if (verilog) "{$high, $low}" else "$high & $low")
        declare(remainder, width + 2, procedural = verilog)
        if (verilog) {
            val trial = "${cell.prefix}_trial"
            val i = "${cell.prefix}_pair"
            declare(trial, width + 2, procedural = true)
            declarations += "integer $i;"
            statements += """
                always @(*) begin
                  $remainder = 0;
                  $root = 0;
                  for ($i = ${width - 1}; $i >= 0; $i = $i - 1) begin
                    $remainder = {$remainder[${width - 1}:0], $radicand[2*$i +: 2]};
                    $trial = {$root, 2'b01};
                    $root = $root << 1;
                    if ($remainder >= $trial) begin
                      $remainder = $remainder - $trial;
                      $root[0] = 1'b1;
                    end
                  end
                end
            """.trimIndent()
        } else {
            statements += """
                ${cell.prefix}_root : process(all)
                  variable partial, trial : unsigned(${width + 1} downto 0);
                  variable result : unsigned(${width - 1} downto 0);
                begin
                  partial := (others => '0');
                  result := (others => '0');
                  for i in ${width - 1} downto 0 loop
                    partial := partial(${width - 1} downto 0) & unsigned($radicand(2*i+1 downto 2*i));
                    trial := result & "01";
                    result := shift_left(result, 1);
                    if partial >= trial then
                      partial := partial - trial;
                      result(0) := '1';
                    end if;
                  end loop;
                  $root <= std_logic_vector(result);
                  $remainder <= std_logic_vector(partial);
                end process;
            """.trimIndent()
        }
        assign(outputRemainder, slice(remainder, width - 1, 0))
    }

    private fun shiftRegister(cell: HdlCell) {
        val serial = endpoint(cell, 0, "serial")
        val width = serial.width
        val length = cell.component.attributes["length"]?.toIntOrNull()?.coerceIn(1, 64) ?: 8
        val stages = List(length) { "${cell.prefix}_stage$it" }
        stages.forEach { declare(it, width, procedural = true, initial = 0uL) }
        cell.outputs.forEachIndexed { i, port ->
            val stage = if (port.terminal.name == "q") stages.last() else stages[port.terminal.name.removePrefix("stage").toInt()]
            assign(output(cell, i), stage)
        }
        val clear = control(cell, 3, false, "clear")
        val fpgaClock = cell.fpgaClock
        val clock = fpgaClock?.edgeClock ?: control(cell, 2, null, "clock")
        val shift = isHigh(control(cell, 1, false, "shift"))
        val load = optionalControl(cell, "load", false)
        val serialValue = value(cell, serial, false)
        val next = stages.mapIndexed { i, stage ->
            val shifted = conditional(shift, stages.getOrNull(i - 1) ?: serialValue, stage)
            val parallel = cell.inputs.firstOrNull { it.terminal.name == "parallel$i" }
            if (parallel == null) shifted else conditional(load, value(cell, parallel, false), shifted)
        }
        val zero = vectorLiteral(width, 0uL)
        val edge = cell.component.attributes["trigger"] == "falling"
        fun updates(values: List<String>) = stages.zip(values).joinToString("\n") { (stage, value) -> "    $stage <= $value;" }
        val reset = updates(List(length) { zero })
        val advance = updates(next)
        if (verilog) {
            val sensitivity = (if (edge) "negedge " else "posedge ") + clock +
                if (clear == bit(false)) "" else " or posedge $clear"
            val enable = fpgaClock?.let { " if (${it.edgeEnable(edge)})" }.orEmpty()
            statements += "always @($sensitivity) begin\n  if (${isHigh(clear)}) begin\n$reset\n  end else$enable begin\n$advance\n  end\nend"
        } else {
            val sampleClock = if (fpgaClock != null) vhdlFpgaSampleClock(cell, clock, edge) else clock
            val sensitivity = if (clear == bit(false)) sampleClock else "$sampleClock, $clear"
            val edgeFunction = if (edge && fpgaClock == null) "falling_edge" else "rising_edge"
            val event = "$edgeFunction($sampleClock)"
            val condition = fpgaClock?.let { boolAnd(event, it.edgeEnable(edge)) } ?: event
            statements += "${cell.prefix}_shift : process($sensitivity)\nbegin\n  if ${isHigh(clear)} then\n$reset\n  elsif $condition then\n$advance\n  end if;\nend process;"
        }
    }

    private fun vhdlFpgaSampleClock(cell: HdlCell, clock: String, falling: Boolean): String {
        // The Evolution VHDL cell samples after its concurrent clock assignment settles.
        val sample = "${cell.prefix}_fpga_sample_clock"
        declarations += "signal $sample : std_logic;"
        assign(sample, if (falling) "not $clock" else clock)
        return sample
    }

    private fun HdlClockSignals.edgeEnable(falling: Boolean): String = isHigh(if (falling) fallEnable else riseEnable)

    private fun random(cell: HdlCell) {
        val width = cell.outputs[0].width
        val seed = cell.component.attributes["seed"]?.toIntOrNull()?.toUInt()?.toULong() ?: 0uL
        // The desktop HDL generator uses a deterministic seed for hardware, including seed zero.
        val initial = RandomSequence.seeded(if (seed == 0uL) 0x5deece66duL else seed).words
        val state = List(4) { "${cell.prefix}_random$it" }
        val (s0, s1, s2, s3) = state
        val sum = "${cell.prefix}_sum"
        val result = "${cell.prefix}_result"
        val mixed = "${cell.prefix}_mixed"
        val phase = "${cell.prefix}_reset_phase"
        val falling = cell.component.attributes["trigger"] == "falling"
        val fpgaClock = cell.fpgaClock
        val clock = when {
            fpgaClock == null -> control(cell, 0, null, "clock")
            falling -> requireNotNull(fpgaClock.fallingSamplerClock) { "Random falling sampler clock is missing." }
            else -> fpgaClock.edgeClock
        }
        val componentEnable = isHigh(control(cell, 1, true, "enable"))
        val enable = fpgaClock?.let { boolAnd(it.edgeEnable(falling), componentEnable) } ?: componentEnable
        val reset = isHigh(control(cell, 2, false, "reset"))
        state.forEach { declare(it, 64, procedural = true) }
        listOf(sum, result, mixed).forEach { declare(it, 64) }
        declare(phase, 2, procedural = true)
        val xor = if (verilog) "^" else "xor"
        fun add(left: String, right: String) = if (verilog) "$left + $right" else "std_logic_vector(unsigned($left) + unsigned($right))"
        fun rotate(name: String, count: Int) = if (verilog) "{${slice(name, 63 - count, 0)}, ${slice(name, 63, 64 - count)}}"
            else "std_logic_vector(rotate_left(unsigned($name), $count))"
        val shifted = if (verilog) "($s1 << 17)" else "std_logic_vector(shift_left(unsigned($s1), 17))"
        assign(sum, add(s0, s3))
        assign(result, add(rotate(sum, 23), s0))
        assign(mixed, "$s3 $xor $s1")
        assign(output(cell, 0), slice(result, width - 1, 0))
        val next = listOf("$s0 $xor $mixed", "$s1 $xor $s2 $xor $s0", "$s2 $xor $s0 $xor $shifted", rotate(mixed, 45))
        val restart = state.zip(initial).joinToString("\n") { (name, word) -> "      $name <= ${vectorLiteral(64, word)};" }
        val advance = state.zip(next).joinToString("\n") { (name, word) -> "      $name <= $word;" }
        val holding = vectorLiteral(2, 1uL)
        val releasing = vectorLiteral(2, 2uL)
        val ready = vectorLiteral(2, 3uL)
        // Power-up and clear progress through the same two recovery edges as the desktop HDL.
        if (verilog) {
            val edge = if (falling && fpgaClock == null) "negedge" else "posedge"
            statements += """
                always @($edge $clock) begin
                  case ($phase)
                    $ready: if ($reset) begin
                $restart
                      $phase <= $holding;
                    end else if ($enable) begin
                $advance
                    end
                    $holding: $phase <= $releasing;
                    $releasing: $phase <= $reset ? $holding : $ready;
                    default: begin
                $restart
                      $phase <= $holding;
                    end
                  endcase
                end
            """.trimIndent()
        } else {
            val edge = if (falling && fpgaClock == null) "falling_edge" else "rising_edge"
            // VHDL case choices must be locally static. Use unqualified bit strings here.
            statements += """
                ${cell.prefix}_prng : process($clock)
                begin
                  if $edge($clock) then
                    case $phase is
                      when "11" =>
                        if $reset then
                $restart
                          $phase <= $holding;
                        elsif $enable then
                $advance
                        end if;
                      when "01" => $phase <= $releasing;
                      when "10" => $phase <= hdl_choose($reset, $holding, $ready);
                      when others =>
                $restart
                        $phase <= $holding;
                    end case;
                  end if;
                end process;
            """.trimIndent()
        }
    }

    private fun ram(cell: HdlCell) {
        val configuration = MemoryComponents.configuration(cell.component)
        val fpgaClock = cell.fpgaClock
        if (configuration.enables == MemoryEnables.LINE) {
            ramLines(cell, configuration, fpgaClock)
            return
        }
        val vhdlDedicated = if (!verilog && fpgaClock != null) {
            fpgaRamBytesVhdl(cell, configuration, fpgaClock) ?: return
        } else null
        val byteCount = configuration.byteEnableCount
        if (fpgaClock != null && (configuration.bus != MemoryBus.SEPARATE || !configuration.synchronous ||
                configuration.dualPort || configuration.clearPin)) {
            error(cell, "HDL_FPGA_CLOCK_CONSUMER_UNSUPPORTED",
                "Internal FPGA RAM timing requires a single-port, separate-bus RAM with an edge trigger and no clear pin.")
            return
        }
        val outputEnable = cell.inputs.firstOrNull { it.terminal.name == MemoryLayouts.outputEnableName(0) }
        val dedicatedMode = fpgaClock?.dedicatedRamMode()
        val portableSupported = configuration.bus == MemoryBus.SEPARATE && configuration.synchronous &&
            configuration.enables == MemoryEnables.BYTE && !configuration.asynchronousRead && !configuration.clearPin &&
            (byteCount > 0 || outputEnable?.driven?.any { it } != true)
        if (dedicatedMode == null) {
            requireLocalized(portableSupported) { message("hdl_reason_ram_profile") }
        } else if (dedicatedMode != truth() && !portableSupported) {
            error(cell, "HDL_FPGA_CLOCK_CONSUMER_UNSUPPORTED",
                "This RAM profile requires an internal FPGA clock in every instance of its circuit.")
            return
        }
        val address = value(cell, endpoint(cell, 0, MemoryLayouts.addressName(0)), null)
        val data = value(cell, endpoint(cell, 1, MemoryLayouts.inputName(0, 0)), null)
        val write = isHigh(control(cell, 3, false, MemoryLayouts.writeEnableName(0)))
        val output = output(cell, 0, procedural = true)
        val dedicated = vhdlDedicated ?: fpgaClock?.let { fpgaRamBytes(cell, configuration, it, address, data, write, output) }
        if (dedicatedMode == truth()) {
            statements += checkNotNull(dedicated)
            return
        }
        val clock = control(cell, 4, null, MemoryLayouts.clockName(0))
        val width = configuration.dataBits
        val depth = 1 shl configuration.addressBits
        val words = "${cell.prefix}_words"
        val location = if (verilog) "$words[$address]" else "$words(to_integer(unsigned($address)))"
        fun update(word: String, input: String, result: String, writeEnabled: String, readEnabled: String? = null): String {
            val read = if (configuration.readBehavior == MemoryReadBehavior.AFTER_WRITE) {
                conditional(writeEnabled, input, word)
            } else word
            val readAssignment = "$result <= $read;"
            return if (verilog) {
                "if ($writeEnabled) $word <= $input;\n" +
                    (readEnabled?.let { "if ($it) $readAssignment" } ?: readAssignment)
            } else {
                "if $writeEnabled then $word <= $input; end if;\n" +
                    (readEnabled?.let { "if $it then $readAssignment end if;" } ?: readAssignment)
            }
        }
        val updates = if (byteCount == 0) update(location, data, output, write) else {
            val dataBus = "${cell.prefix}_ram_data"
            declare(dataBus, width)
            assign(dataBus, data)
            val read = optionalControl(cell, MemoryLayouts.outputEnableName(0), default = false)
            // Evolution's RTL retains an output byte while its BE or OE is low.
            (0 until byteCount).joinToString("\n") { byte ->
                val low = byte * 8
                val high = minOf(low + 7, width - 1)
                val enabled = optionalControl(cell, MemoryLayouts.byteEnableName(0, byte), default = false)
                update(slice(location, high, low), slice(dataBus, high, low), slice(output, high, low),
                    boolAnd(write, enabled), boolAnd(read, enabled))
            }
        }
        val falling = configuration.trigger == MemoryTrigger.FALLING
        if (verilog) {
            val edge = if (falling) "negedge" else "posedge"
            val index = "${cell.prefix}_init_index"
            val memory = "reg [${width - 1}:0] $words [0:${depth - 1}];"
            val portable = """
                |initial begin
                |  for ($index = 0; $index < $depth; $index = $index + 1)
                |    $words[$index] = ${vectorLiteral(width, 0uL)};
                |end
                |always @($edge $clock) begin
                ${updates.prependIndent("|  ")}
                |end
            """.trimMargin()
            emitRamVerilog(cell, dedicatedMode, dedicated, listOf(memory, "integer $index;"), portable)
        } else {
            val edge = if (falling) "falling_edge" else "rising_edge"
            val type = "${words}_type"
            declarations += "type $type is array (0 to ${depth - 1}) of std_logic_vector(${width - 1} downto 0);"
            declarations += "signal $words : $type := (others => (others => '0'));"
            emitRamVhdl(cell, dedicatedMode, dedicated, """
                |process($clock)
                |begin
                |  if $edge($clock) then
                ${updates.prependIndent("|    ")}
                |  end if;
                |end process;
            """.trimMargin())
        }
    }

    private fun fpgaRamBytesVhdl(cell: HdlCell, configuration: MemoryConfiguration, clock: HdlClockSignals): String? {
        val unsupported = when {
            configuration.enables == MemoryEnables.LINE -> "VHDL FPGA tick timing is not available for line-enabled RAM."
            configuration.bus != MemoryBus.SEPARATE || !configuration.synchronous ||
                configuration.readOnly || configuration.dualPort || configuration.clearPin ->
                "VHDL FPGA RAM requires a single port with separate buses, an edge trigger and no clear pin."
            else -> null
        }
        if (unsupported != null) {
            error(cell, "HDL_FPGA_CLOCK_CONSUMER_UNSUPPORTED", unsupported)
            return null
        }
        fun scalar(name: String): String =
            cell.inputs.firstOrNull { it.terminal.name == name }?.let { valueBit(cell, it, false) } ?: bit(false)
        val tick = if (configuration.trigger == MemoryTrigger.FALLING) clock.fallEnable else clock.riseEnable
        return FpgaEvolutionRamByteVhdl.vhdl(
            prefix = cell.prefix,
            configuration = configuration,
            clock = clock.edgeClock,
            tick = tick,
            address = value(cell, endpoint(cell, -1, MemoryLayouts.addressName(0)), null),
            data = value(cell, endpoint(cell, -1, MemoryLayouts.inputName(0, 0)), null),
            write = scalar(MemoryLayouts.writeEnableName(0)),
            output = output(cell, 0),
            outputEnable = scalar(MemoryLayouts.outputEnableName(0)),
            byteEnables = List(configuration.byteEnableCount) { byte -> scalar(MemoryLayouts.byteEnableName(0, byte)) },
        )
    }

    private fun fpgaRamBytes(
        cell: HdlCell,
        configuration: MemoryConfiguration,
        clock: HdlClockSignals,
        address: String,
        data: String,
        write: String,
        output: String,
    ): String = buildString {
        val prefix = cell.prefix
        val width = configuration.dataBits
        val addressHigh = configuration.addressBits - 1
        val byteCount = configuration.byteEnableCount
        val words = "${prefix}_words"
        val pipeline = "${prefix}_ram_ticks"
        val sampledAddress = "${prefix}_ram_address"
        val readAddress = "${prefix}_ram_read_address"
        val memoryAddress = if (configuration.asynchronousRead) "${prefix}_ram_mem_address" else sampledAddress
        val sampledData = "${prefix}_ram_data"
        val sampledWrite = "${prefix}_ram_we"
        val sampledOutput = "${prefix}_ram_oe"
        val sampledBytes = "${prefix}_ram_byte_enables"
        val memoryData = "${prefix}_ram_read"
        val tick = if (configuration.trigger == MemoryTrigger.FALLING) clock.fallEnable else clock.riseEnable
        // Evolution hides the read/write ordering attribute when asynchronous read is enabled.
        val writeStage = if (!configuration.asynchronousRead && configuration.readBehavior == MemoryReadBehavior.AFTER_WRITE)
            0 else 2
        appendLine("reg [${width - 1}:0] $words [0:${(1 shl configuration.addressBits) - 1}];")
        appendLine("reg [2:0] $pipeline;")
        appendLine("reg [$addressHigh:0] $sampledAddress;")
        if (configuration.asynchronousRead) {
            appendLine("reg [$addressHigh:0] $readAddress;")
            appendLine("wire [$addressHigh:0] $memoryAddress = ($pipeline[$writeStage] == 1'b1) ? $sampledAddress : $readAddress;")
        }
        appendLine("reg [${width - 1}:0] $sampledData, $memoryData;")
        appendLine("reg $sampledWrite;")
        if (byteCount > 0) {
            appendLine("reg $sampledOutput;")
            appendLine("reg [${byteCount - 1}:0] $sampledBytes;")
        }
        appendLine("always @(posedge ${clock.edgeClock}) begin")
        appendLine("  $pipeline <= {$pipeline[1:0], $tick};")
        if (configuration.asynchronousRead) {
            appendLine("  $readAddress <= ($pipeline[0] == 1'b1) ? $address : $readAddress;")
        }
        appendLine("  if ($tick) begin")
        appendLine("    $sampledAddress <= $address;")
        appendLine("    $sampledData <= $data;")
        appendLine("    $sampledWrite <= $write;")
        if (byteCount > 0) {
            val read = optionalControl(cell, MemoryLayouts.outputEnableName(0), default = false)
            appendLine("    $sampledOutput <= $read;")
            for (byte in 0 until byteCount) {
                // Evolution's FPGA port map reverses the physical BE terminals.
                val terminal = MemoryLayouts.byteEnableName(0, byteCount - byte - 1)
                val enable = optionalControl(cell, terminal, default = false)
                appendLine("    $sampledBytes[$byte] <= $enable;")
            }
        }
        appendLine("  end")
        val word = "$words[$memoryAddress]"
        appendLine("  $memoryData <= $word;")
        val writeEnabled = "$pipeline[$writeStage] && $sampledWrite"
        if (byteCount == 0) {
            // Evolution ties OE high in FPGA RTL when there are no byte enables.
            appendLine("  if ($writeEnabled) $word <= $sampledData;")
            appendLine("  if ($pipeline[2]) $output <= $memoryData;")
        } else {
            for (byte in 0 until byteCount) {
                val low = byte * 8
                val high = minOf(low + 7, width - 1)
                val destination = slice(word, high, low)
                appendLine("  if ($writeEnabled && $sampledBytes[$byte]) $destination <= ${slice(sampledData, high, low)};")
                appendLine("  if ($pipeline[2] && $sampledOutput && $sampledBytes[$byte]) " +
                    "${slice(output, high, low)} <= ${slice(memoryData, high, low)};")
            }
        }
        append("end")
    }

    private fun HdlClockSignals.dedicatedRamMode(): String =
        requireNotNull(ramDedicatedMode) { "FPGA RAM mode is missing." }

    private fun emitRamVerilog(
        cell: HdlCell,
        dedicatedMode: String?,
        dedicated: String?,
        portableDeclarations: List<String>,
        portable: String,
    ) {
        if (dedicated == null) {
            declarations += portableDeclarations
            statements += portable
        } else {
            val portableBody = (portableDeclarations + portable).joinToString("\n")
            statements += """
                |generate
                |if ($dedicatedMode) begin : ${cell.prefix}_dedicated_ram
                ${dedicated.trimEnd().prependIndent("|  ")}
                |end else begin : ${cell.prefix}_portable_ram
                ${portableBody.prependIndent("|  ")}
                |end
                |endgenerate
            """.trimMargin()
        }
    }

    /** Selects the reference tick pipeline or the ordinary-clock RAM for each instance of a shared entity. */
    private fun emitRamVhdl(cell: HdlCell, dedicatedMode: String?, dedicated: String?, portable: String) {
        if (dedicated == null) {
            statements += portable
            return
        }
        val mode = checkNotNull(dedicatedMode) { "FPGA RAM mode is missing." }
        statements += """
            |${cell.prefix}_dedicated_ram : if $mode generate
            ${dedicated.trimEnd().prependIndent("|  ")}
            |end generate;
            |${cell.prefix}_portable_ram : if not $mode generate
            ${portable.trimEnd().prependIndent("|  ")}
            |end generate;
        """.trimMargin()
    }

    private fun ramLines(cell: HdlCell, configuration: MemoryConfiguration, fpgaClock: HdlClockSignals?) {
        // In FPGA exports, Evolution also accepts a shared bus: each line reads back the RAM output driving it.
        val shared = configuration.bus == MemoryBus.SHARED
        val separateWithoutClear = (configuration.bus == MemoryBus.SEPARATE || forFpga && shared) && !configuration.clearPin
        if (fpgaClock != null && (configuration.clearPin || configuration.bus == MemoryBus.CONTROLLED ||
                configuration.readOnly || configuration.dualPort)) {
            error(cell, "HDL_FPGA_CLOCK_CONSUMER_UNSUPPORTED",
                "Internal FPGA RAM timing requires a single-port line-enabled RAM without a controlled data bus or clear pin.")
            return
        }
        val dedicatedMode = fpgaClock?.dedicatedRamMode()
        val dedicatedOnly = dedicatedMode == truth()
        val portableSupported = separateWithoutClear && configuration.synchronous
        if (dedicatedMode == null) {
            requireLocalized(portableSupported) { message("hdl_reason_ram_line_profile") }
        } else if (!dedicatedOnly && !portableSupported) {
            error(cell, "HDL_FPGA_CLOCK_CONSUMER_UNSUPPORTED",
                "This line-enabled RAM profile requires an internal FPGA clock in every instance of its circuit.")
            return
        }
        val address = value(cell, endpoint(cell, 0, MemoryLayouts.addressName(0)), null)
        val write = optionalControl(cell, MemoryLayouts.writeEnableName(0), false)
        val falling = configuration.trigger == MemoryTrigger.FALLING
        val dedicated = fpgaClock?.let {
            val lines = List(configuration.dataLines) { line ->
                val outputName = MemoryLayouts.outputName(0, line)
                val output = output(cell, cell.outputs.indexOfFirst { it.terminal.name == outputName })
                val inputName = if (shared) outputName else MemoryLayouts.inputName(0, line)
                val input = value(cell, endpoint(cell, -1, inputName), null)
                val terminal = MemoryLayouts.lineEnableName(0, line)
                val enable = when {
                    configuration.dataLines == 1 -> null
                    verilog -> optionalControl(cell, terminal, false)
                    else -> control(cell, -1, false, terminal)
                }
                FpgaEvolutionRamLineHdl.Line(input, output, enable)
            }
            // Evolution runs the same pipeline on the selected clock-tree level for level triggers.
            val (clock, tick) = when (configuration.trigger) {
                MemoryTrigger.RISING -> it.edgeClock to it.riseEnable
                MemoryTrigger.FALLING -> it.edgeClock to it.fallEnable
                MemoryTrigger.HIGH -> control(cell, -1, null, MemoryLayouts.clockName(0)) to bit(true)
                MemoryTrigger.LOW -> requireNotNull(it.invertedLevel) { "The inverted FPGA clock level is missing." } to bit(true)
            }
            if (verilog) {
                FpgaEvolutionRamLineHdl.verilog(cell.prefix, configuration, clock, tick, address, write, lines)
            } else {
                val writeSignal = control(cell, -1, false, MemoryLayouts.writeEnableName(0))
                FpgaEvolutionRamLineHdl.vhdl(cell.prefix, configuration, clock, tick, address, writeSignal, lines)
            }
        }
        if (dedicatedOnly) {
            statements += checkNotNull(dedicated)
            return
        }
        val clock = control(cell, -1, null, MemoryLayouts.clockName(0))
        val width = configuration.dataBits
        val depth = 1 shl configuration.addressBits
        val zero = vectorLiteral(width, 0uL)
        val invalid = if (verilog) "{${width}{1'bx}}" else "std_logic_vector'(\"${"X".repeat(width)}\")"
        val addressInt = if (verilog) address else "to_integer(unsigned($address))"
        val aligned = if (configuration.allowMisaligned || configuration.dataLines == 1) truth() else {
            val mask = vectorLiteral(configuration.addressBits, (configuration.dataLines - 1).toULong())
            val maskedAddress = if (verilog) "($address & $mask)" else "($address and $mask)"
            equal(maskedAddress, vectorLiteral(configuration.addressBits, 0uL))
        }
        val words = "${cell.prefix}_words"
        val portableReads = mutableListOf<String>()
        fun read(output: String, expression: String) {
            if (dedicated == null) assign(output, expression)
            else portableReads += if (verilog) "assign $output = $expression;" else "$output <= $expression;"
        }
        val writes = buildList {
            for (line in 0 until configuration.dataLines) {
                val outputName = MemoryLayouts.outputName(0, line)
                val output = output(cell, cell.outputs.indexOfFirst { it.terminal.name == outputName })
                if (line >= depth) {
                    read(output, conditional(aligned, zero, invalid))
                    continue
                }
                val location = if (verilog) "$words[$addressInt + $line]" else "$words($addressInt + $line)"
                val inRange = "($addressInt < ${depth - line})"
                val enable = optionalControl(cell, MemoryLayouts.lineEnableName(0, line), configuration.dataLines == 1)
                val writeLine = boolAnd(inRange, enable)
                val dataName = if (shared) outputName else MemoryLayouts.inputName(0, line)
                val data = value(cell, endpoint(cell, -1, dataName), null)
                if (verilog) {
                    read(output, conditional(aligned, conditional(inRange, location, zero), invalid))
                    add("    if ($writeLine) $location <= $data;")
                } else {
                    // The conditional assignment avoids evaluating an out-of-range array index.
                    read(output, "$invalid when ${boolNot(aligned)} else $location when $inRange else $zero")
                    add("      if $writeLine then $location <= $data; end if;")
                }
            }
        }.joinToString("\n")
        val writeAddress = boolAnd(write, aligned)
        if (verilog) {
            val edge = if (falling) "negedge" else "posedge"
            val index = "${cell.prefix}_init_index"
            val memory = "reg [${width - 1}:0] $words [0:${depth - 1}];"
            val portable = """
                initial begin
                  for ($index = 0; $index < $depth; $index = $index + 1)
                    $words[$index] = $zero;
                end
                always @($edge $clock) begin
                  if ($writeAddress) begin
                $writes
                  end
                end
            """.trimIndent()
            emitRamVerilog(cell, dedicatedMode, dedicated, listOf(memory, "integer $index;"),
                (portableReads + portable).joinToString("\n"))
        } else {
            val edge = if (falling) "falling_edge" else "rising_edge"
            val type = "${words}_type"
            declarations += "type $type is array (0 to ${depth - 1}) of std_logic_vector(${width - 1} downto 0);"
            declarations += "signal $words : $type := (others => (others => '0'));"
            val portable = """
                process($clock)
                begin
                  if $edge($clock) then
                    if $writeAddress then
                $writes
                    end if;
                  end if;
                end process;
            """.trimIndent()
            emitRamVhdl(cell, dedicatedMode, dedicated, (portableReads + portable).joinToString("\n"))
        }
    }

    private fun rom(cell: HdlCell) {
        val select = cell.inputs.firstOrNull { it.terminal.name == MemoryLayouts.ORIGINAL_CHIP_SELECT }
        requireLocalized(select?.driven?.any { it } != true) { message("hdl_reason_floating_output") }
        requireLocalized((cell.component.attributes["line"] ?: "single") == "single") { message("hdl_reason_rom_data_lines") }
        requireLocalized(cell.outputs.size == 1) { message("hdl_reason_rom_outputs") }
        val address = endpoint(cell, 0, "address0", "address")
        requireLocalized(address.width <= 24) { message("hdl_reason_rom_address_width") }
        val output = output(cell, 0, procedural = true)
        val words = parseRom(cell.component.attributes["contents"].orEmpty(), address.width, cell.outputs[0].width)
        val choices = words.entries.sortedBy { it.key }.map { it.key.toString() to vectorLiteral(cell.outputs[0].width, it.value) }
        caseAssignments(output, cell.outputs[0].width, value(cell, address, null), choices, truth())
    }

    private fun caseAssignment(cell: HdlCell, output: String, width: Int, selector: String, choices: List<String>, enabled: String) {
        requireLocalized(choices.size <= 256) { message("hdl_reason_selector_limit") }
        caseAssignments(output, width, selector, choices.mapIndexed { index, value -> index.toString() to value }, enabled)
    }

    private fun caseAssignments(output: String, width: Int, selector: String, choices: List<Pair<String, String>>, enabled: String) {
        statements += if (verilog) buildString {
            append("always @* begin\n  $output = ${vectorLiteral(width, 0uL)};\n  if ($enabled) begin\n    case ($selector)\n")
            choices.forEach { (index, value) -> append("      $index: $output = $value;\n") }
            append("      default: $output = ${vectorLiteral(width, 0uL)};\n    endcase\n  end\nend")
        } else buildString {
            append("process(all)\nbegin\n  $output <= ${vectorLiteral(width, 0uL)};\n  if $enabled then\n    case to_integer(unsigned($selector)) is\n")
            choices.forEach { (index, value) -> append("      when $index => $output <= $value;\n") }
            append("      when others => $output <= ${vectorLiteral(width, 0uL)};\n    end case;\n  end if;\nend process;")
        }
    }

    private fun emitInstance(instance: HdlInstance) {
        val associations = instance.ports.mapIndexed { index, binding ->
            val actual = if (binding.child.direction == PortDirection.INPUT) value(null, binding.endpoint, null)
                else "${instance.id}_port$index".also { signal ->
                    declare(signal, binding.child.bits.size)
                    binding.endpoint.bits.forEachIndexed { bit, target -> assign(target, this.index(signal, bit)) }
                }
            if (verilog) ".${binding.child.name}($actual)" else "${binding.child.name} => $actual"
        } + instance.pinBanks.map { (child, parent) ->
            if (verilog) ".$child($parent)" else "$child => $parent"
        } + instance.timingBindings.map { (child, parent) ->
            if (verilog) ".$child($parent)" else "$child => $parent"
        }
        val parameters = if (instance.timingParameters.isEmpty()) "" else if (verilog) {
            instance.timingParameters.entries.joinToString(", ", " #(", ")") { (name, value) -> ".$name($value)" }
        } else {
            instance.timingParameters.entries.joinToString(", ", " generic map (", ")") { (name, value) -> "$name => $value" }
        }
        statements += if (verilog) "${instance.moduleName}$parameters ${instance.id} (${associations.joinToString(", ")});"
            else if (associations.isEmpty()) "${instance.id} : entity work.${instance.moduleName}$parameters;"
            else "${instance.id} : entity work.${instance.moduleName}$parameters port map (${associations.joinToString(", ")});"
    }

    private val declared = mutableSetOf<String>()
    private fun output(cell: HdlCell, index: Int, procedural: Boolean = false, initial: ULong? = null): String {
        requireLocalized(index in cell.outputs.indices) { message("hdl_reason_missing_output") }
        val name = "${cell.prefix}_out$index"
        if (declared.add(name)) {
            declare(name, cell.outputs[index].width, procedural, initial)
            cell.outputs[index].bits.forEachIndexed { bit, signal -> assign(signal, this.index(name, bit)) }
        }
        return name
    }

    private fun declare(name: String, width: Int, procedural: Boolean = false, initial: ULong? = null) {
        val initializer = initial?.let { " := ${vectorLiteral(width, it)}" }.orEmpty()
        declarations += if (verilog) "${if (procedural) "reg" else "wire"} [${width - 1}:0] $name${initial?.let { " = ${vectorLiteral(width, it)}" }.orEmpty()};"
            else "signal $name : std_logic_vector(${width - 1} downto 0)$initializer;"
    }

    private fun endpoint(cell: HdlCell, fallback: Int, vararg names: String): HdlEndpoint =
        names.firstNotNullOfOrNull { name -> cell.inputs.firstOrNull { it.terminal.name == name } }
            ?: cell.inputs.getOrNull(fallback) ?: throw IllegalArgumentException("Input ${names.firstOrNull() ?: fallback} is missing.")

    private fun value(cell: HdlCell?, endpoint: HdlEndpoint, default: Boolean?): String = vector(endpoint.bits.mapIndexed { bit, signal ->
        if (endpoint.driven[bit]) signal else default?.let(::bit) ?: missing(cell, endpoint.terminal.name)
    })
    private fun valueBit(cell: HdlCell, endpoint: HdlEndpoint, default: Boolean?): String {
        requireLocalized(endpoint.width == 1) { message("hdl_reason_control_width") }
        return if (endpoint.driven[0]) endpoint.bits[0] else default?.let(::bit) ?: missing(cell, endpoint.terminal.name)
    }
    private fun control(cell: HdlCell, fallback: Int, default: Boolean?, vararg names: String): String =
        valueBit(cell, endpoint(cell, fallback, *names), default)
    private fun optionalControl(cell: HdlCell, name: String, default: Boolean, fallback: Int? = null): String =
        (cell.inputs.firstOrNull { it.terminal.name == name } ?: fallback?.let { cell.inputs.getOrNull(it) })?.let { isHigh(valueBit(cell, it, default)) } ?: if (default) truth() else falsity()
    private fun missing(cell: HdlCell?, terminal: String): String {
        diagnostics += HdlExportDiagnostic("HDL_UNDRIVEN_INPUT", HdlDiagnosticSeverity.ERROR,
            "An input bit has no driver.", module.document.id, cell?.component?.id, terminal)
        return bit(false)
    }
    private fun assign(target: String, expression: String) {
        statements += if (verilog) "assign $target = $expression;" else "$target <= $expression;"
    }
    private fun vector(bits: List<String>): String = if (verilog) "{${bits.asReversed().joinToString(", ")}}"
        else if (bits.size == 1) "std_logic_vector'(0 => ${bits[0]})" else "std_logic_vector'(${bits.asReversed().joinToString(" & ")})"
    private fun vectorLiteral(width: Int, value: ULong): String {
        val binary = value.toString(2).takeLast(width).padStart(width, '0')
        return if (verilog) "${width}'b$binary" else "std_logic_vector'(\"$binary\")"
    }
    private fun bit(high: Boolean): String = if (verilog) if (high) "1'b1" else "1'b0" else if (high) "'1'" else "'0'"
    private fun index(name: String, bit: Int) = if (verilog) "$name[$bit]" else "$name($bit)"
    private fun slice(name: String, high: Int, low: Int) = if (verilog) "$name[$high:$low]" else "$name($high downto $low)"
    private fun inverted(value: String) = if (verilog) "~($value)" else "not ($value)"
    private fun numeric(value: String, signed: Boolean) = if (verilog) if (signed) "\$signed($value)" else "\$unsigned($value)"
        else "${if (signed) "signed" else "unsigned"}($value)"
    private fun isHigh(scalar: String) = when (scalar) {
        bit(false) -> falsity()
        bit(true) -> truth()
        else -> if (verilog) "($scalar == 1'b1)" else "($scalar = '1')"
    }
    private fun equal(a: String, b: String) = "($a ${if (verilog) "==" else "="} $b)"
    private fun conditional(condition: String, yes: String, no: String) = if (verilog) "($condition ? $yes : $no)" else "hdl_choose($condition, $yes, $no)"
    private fun conditionalBoolean(condition: String, yes: String, no: String) = boolOr(boolAnd(condition, yes), boolAnd(boolNot(condition), no))
    private fun boolAnd(a: String, b: String) = "(($a) ${if (verilog) "&&" else "and"} ($b))"
    private fun boolOr(a: String, b: String) = "(($a) ${if (verilog) "||" else "or"} ($b))"
    private fun boolNot(value: String) = if (verilog) "!($value)" else "not ($value)"
    private fun truth() = if (verilog) "1'b1" else "true"
    private fun falsity() = if (verilog) "1'b0" else "false"
    private fun error(cell: HdlCell, code: String, message: String, reason: LocalizedText = LocalizedText.literal(message)) {
        diagnostics += HdlExportDiagnostic(code, HdlDiagnosticSeverity.ERROR, message, module.document.id, cell.component.id, reason = reason)
    }

    private fun parseRom(contents: String, addressBits: Int, dataBits: Int): Map<Long, ULong> {
        if (contents.isBlank()) return emptyMap()
        return when (val decoded = MemoryImageCodec.decodeCirc(contents,
            MemoryImageLimits(maxNonZeroWords = 65_536))) {
            is MemoryImageImportResult.Success -> decoded.image.resized(addressBits, dataBits).nonZeroWords
            is MemoryImageImportResult.Failure -> throw IllegalArgumentException(decoded.diagnostics.joinToString("; "))
        }
    }
    private fun parseUnsigned(raw: String): ULong? {
        val text = raw.trim()
        if (text.startsWith('-')) return parseUnsigned(text.drop(1))?.let { 0uL - it }
        return when {
            text.startsWith("0x", true) -> text.drop(2).toULongOrNull(16)
            text.startsWith("0b", true) -> text.drop(2).toULongOrNull(2)
            text.length > 1 && text.startsWith('0') -> text.drop(1).toULongOrNull(8)
            else -> text.toULongOrNull()
        }
    }
    private fun comment(text: String): String = text.replace('\n', ' ').replace('\r', ' ')
}
