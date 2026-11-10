package dev.logisim.mobile.core

/** Adds private timing ports while retaining one HDL module for each circuit definition. */
internal class HdlFpgaClockLowering(
    private val project: CircuitProject,
    analysis: FpgaConnectivityAnalysis,
    registry: ComponentRegistry,
    private val language: HdlLanguage,
) {
    private data class PinBit(val componentId: String, val bit: Int, val direction: PortDirection)

    private class Shape {
        val waveforms = mutableMapOf<FpgaClockComponentRef, FpgaClockHdlWaveform>()
        val pins = mutableMapOf<PinBit, HdlClockSignals>()
        val sinkTerminals = mutableMapOf<String, String>()
        val ramModes = mutableMapOf<FpgaClockSinkRef, String>()
        // Present for routed inputs; true when the route also needs Random's sampler.
        val routedChildInputs = mutableMapOf<Pair<String, PinBit>, Boolean>()
    }

    private val documents = mutableMapOf(emptyList<String>() to project.mainCircuit)
    private val shapes = mutableMapOf<String, Shape>()

    private fun document(path: List<String>): CircuitDocument = documents.getOrPut(path) {
        val parent = document(path.dropLast(1))
        val instance = requireNotNull(parent.components.firstOrNull { it.id == path.last() }) {
            "The FPGA clock route refers to a missing subcircuit instance."
        }
        requireNotNull(project.circuit(instance.circuitName.orEmpty())) { "The FPGA clock route refers to a missing circuit." }
    }

    private fun shape(path: List<String>): Shape = shapes.getOrPut(document(path).name) { Shape() }

    init {
        val orderedClocks = analysis.dedicatedClocks.sortedWith(
            compareBy({ it.instancePath.joinToString("/") }, { it.componentId }),
        )
        for (source in orderedClocks) {
            val clock = requireNotNull(document(source.instancePath).components.firstOrNull { it.id == source.componentId })
            val attributes = registry.attributes(clock)
            val waveform = FpgaEvolutionClockHdl.Waveform(
                attributes.string("highDuration")?.toInt() ?: 1,
                attributes.string("lowDuration")?.toInt() ?: 1,
                attributes.string("phaseOffset")?.toInt() ?: 0,
            )
            // Relative paths give a reusable module the same waveform formals at each instance.
            for (depth in 0..source.instancePath.size) {
                val formals = shape(source.instancePath.take(depth)).waveforms
                val relative = source.copy(instancePath = source.instancePath.drop(depth))
                formals.getOrPut(relative) {
                    val prefix = "fpga_wave_${formals.size}"
                    FpgaClockHdlWaveform(relative, waveform, "${prefix}_level", "${prefix}_inverted",
                        "${prefix}_rise", "${prefix}_fall")
                }
            }
        }
        for ((sink, route) in analysis.dedicatedClockRoutes) {
            val sinkShape = shape(sink.instancePath)
            val circuit = document(sink.instancePath)
            val component = requireNotNull(circuit.components.firstOrNull { it.id == sink.componentId })
            // A high-level latch already receives the visible clock on its ordinary net.
            if (route.use == FpgaClockUse.HIGH_LEVEL && component.kind != ComponentKind.RAM) continue
            val needsFallingSampler = component.kind.name == "RANDOM" && route.use == FpgaClockUse.FALLING_EDGE
            val existing = sinkShape.sinkTerminals.put(sink.componentId, sink.terminalName)
            require(existing == null || existing == sink.terminalName) { "Multiple FPGA clock terminals on one component are unsupported." }
            if (component.kind == ComponentKind.RAM && (circuit.id to component.id) in analysis.ordinaryRamSinks) {
                // Only mixed definitions need an elaboration parameter from the root.
                for (depth in 0..sink.instancePath.size) {
                    val modes = shape(sink.instancePath.take(depth)).ramModes
                    val relative = sink.copy(instancePath = sink.instancePath.drop(depth))
                    modes.getOrPut(relative) { "fpga_ram_mode_${modes.size}" }
                }
            }
            for (boundary in route.boundaries) {
                val childShape = shape(boundary.instancePath)
                val pin = PinBit(boundary.pinComponentId, boundary.bit, boundary.direction)
                val formal = childShape.pins.getOrPut(pin) { clockSignals("fpga_pin_${childShape.pins.size}", needsFallingSampler) }
                if (needsFallingSampler && formal.fallingSamplerClock == null) {
                    childShape.pins[pin] = formal.copy(fallingSamplerClock = "${formal.edgeClock}_sampler")
                }
                if (pin.direction == PortDirection.INPUT) {
                    val inputs = shape(boundary.instancePath.dropLast(1)).routedChildInputs
                    val key = boundary.instancePath.last() to pin
                    inputs[key] = needsFallingSampler || inputs[key] == true
                }
            }
        }
    }

    val waveforms: List<FpgaClockHdlWaveform> get() = shapes[project.mainCircuitName]?.waveforms?.values.orEmpty().toList()
    val oscillatorPort: String? get() = OSCILLATOR.takeIf { waveforms.isNotEmpty() }

    fun lower(module: HdlModule, localClocks: List<HdlPort>): HdlModule {
        // A parent without a dedicated clock can still instantiate a child whose
        // timing ports are shared with a dedicated-clock instance elsewhere.
        val shape = shapes[module.document.name] ?: Shape()
        val isRoot = module.document.name == project.mainCircuitName
        val ports = mutableListOf<HdlTimingPort>()
        val signals = module.signals.toMutableList()
        val assignments = mutableListOf<Pair<String, String>>()
        val sources = mutableMapOf<String, HdlClockSignals>()
        val portsByComponent = module.ports.associateBy { it.component.id }
        val componentsById = module.document.components.associateBy { it.id }
        val bindings = module.instances.associate { it.id to mutableMapOf<String, String>() }

        fun formalBit(pin: PinBit): String =
            requireNotNull(portsByComponent[pin.componentId]?.bits?.getOrNull(pin.bit)) {
                "An FPGA clock boundary bit is absent from the HDL module."
            }

        fun drive(net: String, clock: HdlClockSignals) {
            val previous = sources.put(net, clock)
            require(previous == null || previous == clock) { "An FPGA clock net has more than one timing source." }
        }

        if (shape.waveforms.isNotEmpty()) ports += HdlTimingPort(OSCILLATOR, PortDirection.INPUT)
        for (waveform in shape.waveforms.values) {
            ports += waveform.portNames().map { HdlTimingPort(it, PortDirection.INPUT) }
        }
        for (clock in localClocks) {
            val waveform = requireNotNull(shape.waveforms[FpgaClockComponentRef(emptyList(), clock.component.id)]) {
                "A local FPGA Clock has no waveform."
            }
            val net = clock.bits.single()
            assignments += net to waveform.levelPort
            drive(net, HdlClockSignals(OSCILLATOR, waveform.risePort, waveform.fallPort, waveform.invertedLevelPort,
                fallingSamplerClock = OSCILLATOR))
        }
        for ((pin, formal) in shape.pins) {
            ports += formal.names().map { HdlTimingPort(it, pin.direction) }
            if (pin.direction == PortDirection.INPUT) drive(formalBit(pin), formal)
        }

        fun childShape(instance: HdlInstance): Shape? =
            componentsById.getValue(instance.componentId).circuitName?.let(shapes::get)

        fun actualBit(instance: HdlInstance, pin: PinBit): String {
            val port = requireNotNull(instance.ports.firstOrNull { it.child.component.id == pin.componentId }) {
                "An FPGA clock boundary pin is not bound in the subcircuit instance."
            }
            return requireNotNull(port.endpoint.bits.getOrNull(pin.bit)) {
                "An FPGA clock boundary bit is not bound in the subcircuit instance."
            }
        }

        // Register all child output drivers before connecting any child inputs.
        for (instance in module.instances) {
            val child = childShape(instance) ?: continue
            val actuals = bindings.getValue(instance.id)
            if (child.waveforms.isNotEmpty()) actuals[OSCILLATOR] = OSCILLATOR
            for ((source, formal) in child.waveforms) {
                val relative = source.copy(instancePath = listOf(instance.componentId) + source.instancePath)
                val actual = requireNotNull(shape.waveforms[relative]) { "A child FPGA Clock has no parent waveform." }
                actuals.putAll(formal.portNames().zip(actual.portNames()))
            }
            for ((pin, formal) in child.pins) {
                if (pin.direction != PortDirection.OUTPUT) continue
                val actual = clockSignals("fpga_from_${instance.id}_${actuals.size}", formal.fallingSamplerClock != null)
                signals += actual.names()
                actuals.putAll(formal.names().zip(actual.names()))
                drive(actualBit(instance, pin), actual)
            }
        }
        for (instance in module.instances) {
            val child = childShape(instance) ?: continue
            val actuals = bindings.getValue(instance.id)
            for ((pin, formal) in child.pins) {
                if (pin.direction != PortDirection.INPUT) continue
                val bit = actualBit(instance, pin)
                val source = sources[bit]
                val needsFallingSampler = shape.routedChildInputs[instance.componentId to pin]
                require(source != null || needsFallingSampler == null) {
                    "A dedicated FPGA clock did not reach its child input."
                }
                require(needsFallingSampler != true || source?.fallingSamplerClock != null) {
                    "A falling Random clock did not reach its child input."
                }
                // A shared module can receive an ordinary clock in another instance.
                val high = if (language == HdlLanguage.VERILOG) "1'b1" else "'1'"
                val actual = source ?: HdlClockSignals(bit, high, high, bit)
                val sampled = if (formal.fallingSamplerClock != null && actual.fallingSamplerClock == null) {
                    // Random samples this lane on a rising edge, which represents the ordinary falling edge.
                    actual.copy(fallingSamplerClock = if (language == HdlLanguage.VERILOG) "~$bit" else "not $bit")
                } else actual
                actuals.putAll(formal.names().zip(sampled.names()))
            }
        }
        for ((pin, formal) in shape.pins) {
            if (pin.direction != PortDirection.OUTPUT) continue
            val bit = formalBit(pin)
            val source = requireNotNull(sources[bit]) { "A dedicated FPGA clock did not reach its child output." }
            require(formal.fallingSamplerClock == null || source.fallingSamplerClock != null) {
                "A falling Random clock did not reach its child output."
            }
            assignments += formal.names().zip(source.names())
        }
        val cells = module.cells.map { cell ->
            val terminal = shape.sinkTerminals[cell.component.id] ?: return@map cell
            val bit = requireNotNull(cell.inputs.firstOrNull { it.terminal.name == terminal }) {
                "The routed FPGA clock terminal is absent from the HDL cell."
            }.bits.single()
            val clock = requireNotNull(sources[bit]) { "A dedicated FPGA clock did not reach its HDL cell." }
            val ramMode = if (cell.component.kind == ComponentKind.RAM) {
                val sink = FpgaClockSinkRef(emptyList(), cell.component.id, terminal)
                shape.ramModes[sink].takeUnless { isRoot } ?: modeLiteral(true)
            } else null
            cell.copy(fpgaClock = clock.copy(ramDedicatedMode = ramMode))
        }
        return module.copy(
            signals = signals,
            cells = cells,
            instances = module.instances.map { instance ->
                val parameters = childShape(instance)?.ramModes.orEmpty().entries.associate { (sink, formal) ->
                    val relative = sink.copy(instancePath = listOf(instance.componentId) + sink.instancePath)
                    val mode = shape.ramModes[relative]
                    formal to when {
                        mode == null -> modeLiteral(false)
                        isRoot -> modeLiteral(true)
                        else -> mode
                    }
                }
                instance.copy(timingBindings = bindings.getValue(instance.id), timingParameters = parameters)
            },
            timingPorts = ports,
            timingAssignments = assignments,
            timingParameters = if (isRoot) emptyList() else shape.ramModes.values.toList(),
        )
    }

    private fun modeLiteral(dedicated: Boolean): String = when (language) {
        HdlLanguage.VERILOG -> if (dedicated) "1'b1" else "1'b0"
        else -> dedicated.toString()
    }

    private fun FpgaClockHdlWaveform.portNames(): List<String> = listOf(levelPort, invertedLevelPort, risePort, fallPort)

    private fun HdlClockSignals.names(): List<String> =
        listOfNotNull(edgeClock, riseEnable, fallEnable, requireNotNull(invertedLevel), fallingSamplerClock)

    private fun clockSignals(prefix: String, needsFallingSampler: Boolean): HdlClockSignals =
        HdlClockSignals("${prefix}_edge", "${prefix}_rise", "${prefix}_fall", "${prefix}_inverted",
            fallingSamplerClock = "${prefix}_edge_sampler".takeIf { needsFallingSampler })

    private companion object {
        const val OSCILLATOR = "fpga_oscillator"
    }
}
