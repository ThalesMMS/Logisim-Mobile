package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message

data class FpgaCircuitDiagnostic(
    val severity: FpgaDiagnosticSeverity,
    val notice: LocalizedText,
    val target: DiagnosticTarget,
)

data class FpgaCircuitReview(
    val design: FpgaDesignArtifact?,
    val diagnostics: List<FpgaCircuitDiagnostic>,
    val hdlDiagnostics: List<HdlExportDiagnostic>,
    val hdlPorts: List<HdlExportPort>,
    /** Set when dedicated Clocks use the synthesized tick. See [forBoard]. */
    val clockTicks: FpgaClockTicks? = null,
)

internal data class FpgaClockComponentRef(val instancePath: List<String>, val componentId: String)

internal data class FpgaClockSinkRef(
    val instancePath: List<String>,
    val componentId: String,
    val terminalName: String,
)

/** Logical trigger at the sink. Hardware oscillator polarity is chosen during lowering. */
internal enum class FpgaClockUse { RISING_EDGE, FALLING_EDGE, HIGH_LEVEL, LOW_LEVEL }

/** Instance path and pin direction are relative to the child at the boundary. */
internal data class FpgaClockBoundary(
    val instancePath: List<String>,
    val pinComponentId: String,
    val bit: Int,
    val direction: PortDirection,
)

internal data class FpgaClockRoute(
    val source: FpgaClockComponentRef,
    val use: FpgaClockUse,
    val boundaries: List<FpgaClockBoundary> = emptyList(), // Traversal order from sink to source.
)

internal data class FpgaConnectivityAnalysis(
    val diagnostics: List<FpgaCircuitDiagnostic>,
    val dedicatedClocks: Set<FpgaClockComponentRef>,
    val dedicatedClockRoutes: Map<FpgaClockSinkRef, FpgaClockRoute>,
    val ordinaryRamSinks: Set<Pair<String, String>>, // Circuit and component IDs.
)

object FpgaCircuitRules {
    /**
     * Review for the FPGA panel. Dedicated Clocks use the Evolution tick generator. Circuits that the
     * tick lowering rejects keep the physical Clock input of [review].
     */
    fun reviewForFpga(
        project: CircuitProject,
        language: HdlLanguage,
        registry: ComponentRegistry = ComponentCatalog.registry,
        options: FpgaGenerationOptions = FpgaGenerationOptions(),
    ): FpgaCircuitReview {
        val legacy = review(project, language, registry, options)
        val ticks = tickReview(project, language, registry, legacy.takeIf { it.design != null }, options) ?: return legacy
        return if (ticks.design == null && legacy.design != null) legacy else ticks
    }

    fun review(
        project: CircuitProject,
        language: HdlLanguage,
        registry: ComponentRegistry = ComponentCatalog.registry,
        options: FpgaGenerationOptions = FpgaGenerationOptions(),
    ): FpgaCircuitReview {
        val export = HdlExporter.exportForFpga(project, language, registry, options)
        val diagnostics = analyzeConnectivity(project, registry).diagnostics.suppressed(options).toMutableList()
        val main = project.mainCircuit
        val dedicatedClocks = main.components.filter { it.kind == ComponentKind.CLOCK }.mapTo(mutableSetOf()) { it.id }
        // HDL exposes the simulator clock as an input, but the FPGA rule counts only mappable I/O.
        val hasMappableIo = export.ports.any { it.sourceCircuitId != main.id || it.sourceComponentId !in dedicatedClocks }
        if (export.successful && !hasMappableIo) {
            diagnostics += FpgaCircuitDiagnostic(FpgaDiagnosticSeverity.ERROR, message("fpga_core_no_circuit_io"),
                DiagnosticTarget(main.id))
        }
        val design = if (export.successful && diagnostics.none { it.severity == FpgaDiagnosticSeverity.ERROR })
            export.fpgaDesign(language, options = options) else null
        return FpgaCircuitReview(design, diagnostics, export.diagnostics, export.ports)
    }

    /**
     * Evolution's SuppressGatedClockWarnings and SuppressOpenPinWarnings remove only warnings: clocks driven by
     * logic or by an input pin, mixed gated instances, and unconnected pins. Errors stay.
     */
    internal fun List<FpgaCircuitDiagnostic>.suppressed(options: FpgaGenerationOptions): List<FpgaCircuitDiagnostic> =
        filterNot { diagnostic ->
            diagnostic.severity == FpgaDiagnosticSeverity.WARNING && when (diagnostic.notice.key) {
                in GATED_CLOCK_WARNINGS -> options.suppressGatedClockWarnings
                in OPEN_PIN_WARNINGS -> options.suppressOpenPinWarnings
                else -> false
            }
        }

    private val GATED_CLOCK_WARNINGS = setOf("fpga_core_clock_gated", "fpga_core_clock_external", "fpga_core_clock_mixed_instances")
    private val OPEN_PIN_WARNINGS = setOf("fpga_core_circuit_input_open", "fpga_core_circuit_output_open", "fpga_core_terminal_open")

    internal fun analyzeConnectivity(
        project: CircuitProject,
        registry: ComponentRegistry = ComponentCatalog.registry,
    ): FpgaConnectivityAnalysis {
        val circuits = project.circuits.associateBy { it.name }
        val netlists = mutableMapOf<String, Netlist?>()
        fun netlist(name: String): Netlist? {
            if (name !in netlists) {
                val document = circuits.getValue(name)
                netlists[name] = try {
                    Netlist(document, CircuitTopology.elaborate(document, registry))
                } catch (_: IllegalArgumentException) {
                    null // The HDL check reports the invalid component or layout.
                }
            }
            return netlists[name]
        }

        val main = netlist(project.mainCircuitName) ?: return FpgaConnectivityAnalysis(emptyList(), emptySet(), emptyMap(), emptySet())
        val diagnostics = mutableListOf<FpgaCircuitDiagnostic>()
        val dedicatedClocks = mutableSetOf<FpgaClockComponentRef>()
        val dedicatedClockRoutes = mutableMapOf<FpgaClockSinkRef, FpgaClockRoute>()
        val ordinaryRamSinks = mutableSetOf<Pair<String, String>>()
        val pending = ArrayDeque<Instance>()
        pending.addLast(Instance(main))
        val checked = mutableSetOf<String>()
        val clocksByInstance = mutableMapOf<String, MutableMap<DiagnosticTarget, MutableSet<ClockOrigin>>>()
        var instances = 0
        while (pending.isNotEmpty() && instances < MAX_INSTANCES) {
            val instance = pending.removeLast()
            instances++
            val current = instance.netlist
            if (checked.add(current.document.id)) diagnostics.addAll(current.connectionDiagnostics())
            for ((componentId, terminals) in current.terminals) {
                val component = current.components.getValue(componentId)
                for (clock in terminals) {
                    val use = clock.clockUseOf(component, registry) ?: continue
                    val source = traceClock(instance, clock.position, project, ::netlist)
                    if (source.origin == ClockOrigin.DEDICATED) {
                        val origin = FpgaClockComponentRef(checkNotNull(source.instance).path, checkNotNull(source.component).id)
                        val sink = FpgaClockSinkRef(instance.path, component.id, clock.reference.terminalName)
                        dedicatedClockRoutes[sink] = FpgaClockRoute(origin, use, source.boundaries)
                    } else if (component.kind == ComponentKind.RAM) {
                        ordinaryRamSinks += current.document.id to component.id
                    }
                    // Storage latches were excluded from clock warnings. RAM clock pins keep their existing checks.
                    val levelSensitive = use == FpgaClockUse.HIGH_LEVEL || use == FpgaClockUse.LOW_LEVEL
                    if (levelSensitive && component.kind != ComponentKind.RAM) continue
                    val detail = when (source.origin) {
                        ClockOrigin.DEDICATED, ClockOrigin.AMBIGUOUS -> null
                        ClockOrigin.MISSING -> message("fpga_core_clock_disconnected", "component" to component.diagnosticName())
                        ClockOrigin.EXTERNAL -> message("fpga_core_clock_external", "component" to component.diagnosticName())
                        ClockOrigin.LOGIC -> message("fpga_core_clock_gated", "component" to component.diagnosticName(),
                            "source" to source.component?.diagnosticName().orEmpty())
                    }
                    if (detail != null) {
                        val localSource = source.component?.id?.takeIf { source.instance === instance }
                        val components = setOf(component.id) + listOfNotNull(localSource)
                        val target = DiagnosticTarget(current.document.id, instance.path, components,
                            terminals = setOf(clock.reference), wires = current.wiresAt(clock.position))
                        diagnostics.add(FpgaCircuitDiagnostic(FpgaDiagnosticSeverity.WARNING, detail, target))
                    }
                    val parent = instance.parent
                    val portOwner = instance.component
                    if (parent != null && portOwner != null && source.crossedInput) {
                        val target = DiagnosticTarget(parent.netlist.document.id, parent.path, setOf(portOwner.id))
                        clocksByInstance.getOrPut(current.document.id) { mutableMapOf() }
                            .getOrPut(target) { mutableSetOf() }.add(source.origin)
                    }
                }
            }
            for (component in current.components.values) {
                when (component.kind) {
                    ComponentKind.CLOCK -> dedicatedClocks += FpgaClockComponentRef(instance.path, component.id)
                    ComponentKind.SUBCIRCUIT -> instance.child(component, ::netlist)?.let(pending::addLast)
                }
            }
        }
        for (observations in clocksByInstance.values) {
            val allOrigins = observations.values.flatten().toSet()
            if (ClockOrigin.DEDICATED !in allOrigins || allOrigins.none { it == ClockOrigin.LOGIC || it == ClockOrigin.EXTERNAL }) continue
            for ((target, origins) in observations) {
                if (origins.none { it == ClockOrigin.LOGIC || it == ClockOrigin.EXTERNAL || it == ClockOrigin.DEDICATED }) continue
                diagnostics.add(FpgaCircuitDiagnostic(FpgaDiagnosticSeverity.WARNING,
                    message("fpga_core_clock_mixed_instances", "component" to target.componentIds.single()), target))
            }
        }
        if (pending.isNotEmpty()) {
            diagnostics.add(FpgaCircuitDiagnostic(FpgaDiagnosticSeverity.ERROR, message("fpga_core_drc_instance_limit", "limit" to MAX_INSTANCES),
                DiagnosticTarget(main.document.id)))
        }
        return FpgaConnectivityAnalysis(diagnostics, dedicatedClocks, dedicatedClockRoutes, ordinaryRamSinks)
    }

    private fun DiagnosticTerminal.clockUseOf(component: CircuitComponent, registry: ComponentRegistry): FpgaClockUse? {
        if (direction != PortDirection.INPUT || declaredWidth != 1) return null
        val trigger = when (component.kind.name) {
            "D_FLIP_FLOP", "T_FLIP_FLOP", "JK_FLIP_FLOP", "SR_FLIP_FLOP", "REGISTER" ->
                if (reference.terminalName == "clock") registry.attributes(component).string("trigger") else return null
            "COUNTER", "SHIFT_REGISTER", "RANDOM" -> {
                if (reference.terminalName != "clock") return null
                return if (registry.attributes(component).string("trigger") == "falling") FpgaClockUse.FALLING_EDGE
                else FpgaClockUse.RISING_EDGE
            }
            "RAM" -> if (reference.terminalName == MemoryLayouts.clockName(0)) {
                MemoryComponents.configuration(component).trigger.serialized
            } else return null
            else -> return null
        }
        return when (trigger) {
            "falling" -> FpgaClockUse.FALLING_EDGE
            "high" -> FpgaClockUse.HIGH_LEVEL
            "low" -> FpgaClockUse.LOW_LEVEL
            else -> FpgaClockUse.RISING_EDGE
        }
    }

    private enum class ClockOrigin { MISSING, DEDICATED, EXTERNAL, LOGIC, AMBIGUOUS }
    private data class ClockSource(
        val origin: ClockOrigin,
        val instance: Instance? = null,
        val component: CircuitComponent? = null,
        val crossedInput: Boolean = false,
        val boundaries: List<FpgaClockBoundary> = emptyList(),
    )

    private fun traceClock(
        sink: Instance,
        position: GridPoint,
        project: CircuitProject,
        netlist: (String) -> Netlist?,
    ): ClockSource {
        var instance = sink
        var point = position
        var bit = 0
        var crossedInput = false
        val visited = mutableSetOf<Pair<List<String>, String>>()
        val boundaries = mutableListOf<FpgaClockBoundary>()
        while (true) {
            val net = instance.netlist.topology.bitAt(point, bit) ?: return ClockSource(ClockOrigin.MISSING)
            if (!visited.add(instance.path to net.id)) return ClockSource(ClockOrigin.AMBIGUOUS)
            val drivers = instance.netlist.drivers[net.id].orEmpty()
            if (drivers.isEmpty()) return ClockSource(ClockOrigin.MISSING)
            val driver = drivers.singleOrNull() ?: return ClockSource(ClockOrigin.AMBIGUOUS)
            val component = instance.netlist.components.getValue(driver.terminal.reference.componentId)
            bit = driver.bit
            when (component.kind) {
                ComponentKind.CLOCK -> return ClockSource(ClockOrigin.DEDICATED, instance, component, crossedInput, boundaries)
                ComponentKind.INPUT -> {
                    val parent = instance.parent
                        ?: return ClockSource(ClockOrigin.EXTERNAL, instance, component, crossedInput)
                    val portOwner = instance.component ?: return ClockSource(ClockOrigin.MISSING)
                    val binding = project.subcircuitPortBindings(portOwner).firstOrNull { it.second.id == component.id }
                        ?: return ClockSource(ClockOrigin.MISSING)
                    boundaries += FpgaClockBoundary(instance.path, component.id, bit, PortDirection.INPUT)
                    if (instance === sink) crossedInput = true
                    point = binding.first
                    instance = parent
                }
                ComponentKind.SUBCIRCUIT -> {
                    val child = instance.child(component, netlist) ?: return ClockSource(ClockOrigin.MISSING)
                    val binding = project.subcircuitPortBindings(component).firstOrNull { it.first == driver.terminal.position }
                        ?: return ClockSource(ClockOrigin.MISSING)
                    val port = child.netlist.terminals[binding.second.id]?.singleOrNull()
                        ?: return ClockSource(ClockOrigin.MISSING)
                    boundaries += FpgaClockBoundary(child.path, binding.second.id, bit, PortDirection.OUTPUT)
                    point = port.position
                    instance = child
                }
                else -> return ClockSource(ClockOrigin.LOGIC, instance, component, crossedInput)
            }
        }
    }

    private class Instance(
        val netlist: Netlist,
        val parent: Instance? = null,
        val component: CircuitComponent? = null,
    ) {
        val path: List<String> = parent?.path.orEmpty() + listOfNotNull(component?.id)
        private val ancestry: Set<String> = parent?.ancestry.orEmpty() + netlist.document.id

        fun child(component: CircuitComponent, lookup: (String) -> Netlist?): Instance? {
            val child = component.circuitName?.let(lookup) ?: return null
            if (child.document.id in ancestry) return null
            return Instance(child, this, component)
        }
    }

    private class Netlist(val document: CircuitDocument, val topology: CircuitTopology) {
        val components = document.components.associateBy { it.id }
        private val passive = setOf("SPLITTER", "TUNNEL", "NO_CONNECT", "TEXT")
        val terminals = topology.nets.flatMap { it.terminals }
            .filter { components.getValue(it.reference.componentId).kind.name !in passive }
            .groupBy { it.reference.componentId }
        private val activeTerminals = terminals.values.flatten().mapTo(mutableSetOf()) { it.reference }
        val drivers = topology.bitNets.associate { net ->
            net.id to net.terminals.filter { it.terminal.reference in activeTerminals && it.terminal.direction != PortDirection.INPUT }
        }

        fun wiresAt(point: GridPoint): Set<WireSegment> = topology.netAt(point)?.wires.orEmpty().toSet()

        fun connectionDiagnostics(): List<FpgaCircuitDiagnostic> = buildList {
            for ((id, ports) in terminals) {
                val component = components.getValue(id)
                for (port in ports) {
                    if (port.connected || (port.direction == PortDirection.OUTPUT && component.kind != ComponentKind.INPUT)) continue
                    val detail = when (component.kind) {
                        ComponentKind.INPUT -> message("fpga_core_circuit_input_open", "component" to component.diagnosticName())
                        ComponentKind.OUTPUT -> message("fpga_core_circuit_output_open", "component" to component.diagnosticName())
                        else -> message("fpga_core_terminal_open", "component" to component.diagnosticName(), "terminal" to port.reference.terminalName)
                    }
                    add(warning(detail, DiagnosticTarget(document.id, componentIds = setOf(id), terminals = setOf(port.reference))))
                }
            }
            for (net in topology.nets) {
                val widths = net.declaredWidths
                if (widths.size < 2) continue
                val target = DiagnosticTarget(document.id,
                    componentIds = net.terminals.map { it.reference.componentId }.toSet(), wires = net.wires.toSet())
                add(FpgaCircuitDiagnostic(FpgaDiagnosticSeverity.ERROR,
                    message("fpga_core_circuit_width_mismatch", "widths" to widths.sorted().joinToString(" / ")), target))
            }
            for (net in topology.bitNets) {
                val sources = drivers.getValue(net.id)
                val sinks = net.terminals.filter {
                    it.terminal.reference in activeTerminals && it.terminal.direction != PortDirection.OUTPUT
                }
                val strong = sources.filter { source ->
                    val kind = components.getValue(source.terminal.reference.componentId).kind
                    source.terminal.direction == PortDirection.OUTPUT &&
                        kind != ComponentKind.CONTROLLED_BUFFER && kind != ComponentKind.CONTROLLED_INVERTER
                }
                if (strong.size <= 1 && sources.isNotEmpty() && sinks.isNotEmpty()) continue
                val wires = net.points.flatMap { topology.netAt(it.position)?.wires.orEmpty() }.toSet()
                if (strong.size > 1) {
                    val refs = strong.map { it.terminal.reference }.toSet()
                    val ids = refs.map { it.componentId }.toSet()
                    val names = refs.joinToString { "${components.getValue(it.componentId).diagnosticName()}.${it.terminalName}" }
                    val detail = message("fpga_core_circuit_short", "drivers" to names)
                    add(FpgaCircuitDiagnostic(FpgaDiagnosticSeverity.ERROR, detail,
                        DiagnosticTarget(document.id, componentIds = ids, terminals = refs, wires = wires)))
                }
                if (wires.isEmpty()) continue
                if (sources.isEmpty()) for (sink in sinks.filter { it.terminal.connected }) {
                    val ref = sink.terminal.reference
                    add(warning(message("fpga_core_circuit_no_driver", "component" to components.getValue(ref.componentId).diagnosticName(),
                        "terminal" to ref.terminalName),
                        DiagnosticTarget(document.id, componentIds = setOf(ref.componentId), terminals = setOf(ref), wires = wires)))
                }
                if (sources.isNotEmpty() && sinks.isEmpty()) {
                    val ids = sources.map { it.terminal.reference.componentId }.toSet()
                    add(warning(message("fpga_core_circuit_no_sink", "components" to ids.joinToString { components.getValue(it).diagnosticName() }),
                        DiagnosticTarget(document.id, componentIds = ids, wires = wires)))
                }
            }
        }.distinct()

        private fun warning(notice: LocalizedText, target: DiagnosticTarget) =
            FpgaCircuitDiagnostic(FpgaDiagnosticSeverity.WARNING, notice, target)
    }

    private fun CircuitComponent.diagnosticName(): String = label.ifBlank { id }

    private const val MAX_INSTANCES = 4096
}
