package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.localizedDescription

enum class DiagnosticSeverity { ERROR, WARNING, INFORMATION }
enum class DiagnosticCategory { ELECTRICAL, DOCUMENT, EXECUTION }
enum class DiagnosticCode {
    WIDTH_MISMATCH, DRIVER_CONFLICT, UNCONNECTED_INPUT, VALUE_ERROR,
    INVALID_REFERENCE, RECURSIVE_REFERENCE, UNKNOWN_COMPONENT, INVALID_ATTRIBUTES,
    OSCILLATION, EXECUTION_LIMIT, IMPORT_ERROR,
    ANALYSIS_LIMIT,
}

data class DiagnosticTarget(
    val circuitId: String,
    val instancePath: List<String> = emptyList(),
    val componentIds: Set<String> = emptySet(),
    val terminals: Set<DiagnosticTerminalRef> = emptySet(),
    val anchors: Set<GridPoint> = emptySet(),
    val wires: Set<WireSegment> = emptySet(),
)

data class CircuitDiagnostic(
    val id: String,
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val category: DiagnosticCategory,
    val target: DiagnosticTarget,
    val notice: LocalizedText,
)

data class DiagnosticReport(
    val diagnostics: List<CircuitDiagnostic>,
    val topologies: Map<String, CircuitTopology>,
    val limited: Boolean = false,
) {
    val errors: Int get() = diagnostics.count { it.severity == DiagnosticSeverity.ERROR }
    val warnings: Int get() = diagnostics.count { it.severity == DiagnosticSeverity.WARNING }
}

/** Values are the component's own output drivers before electrical resolution. */
data class DiagnosticDriver(val terminal: DiagnosticTerminalRef, val value: LogicVector)

object CircuitDiagnosticAnalyzer {
    fun analyze(
        document: CircuitDocument,
        simulation: SimulationState? = null,
        registry: ComponentRegistry = ComponentCatalog.registry,
        instancePath: List<String> = emptyList(),
        includeUnconnectedInputs: Boolean = true,
        drivers: List<DiagnosticDriver>? = null,
        availableCircuitNames: Set<String>? = null,
    ): DiagnosticReport {
        val diagnostics = mutableListOf<CircuitDiagnostic>()
        val valid = document.components.filter { component ->
            val definition = registry.definition(component.kind)
            val target = DiagnosticTarget(document.id, instancePath, setOf(component.id), anchors = setOf(component.position))
            if (definition == null && component.kind != ComponentKind.SUBCIRCUIT) {
                diagnostics += diagnostic(DiagnosticCode.UNKNOWN_COMPONENT, DiagnosticCategory.DOCUMENT, target,
                    component.id, message("diagnostic_unknown_component", "type" to component.kind.name))
                false
            } else try {
                component.layout(registry)
                true
            } catch (error: IllegalArgumentException) {
                diagnostics += diagnostic(DiagnosticCode.INVALID_ATTRIBUTES, DiagnosticCategory.DOCUMENT, target,
                    component.id, message("diagnostic_invalid_attributes", "component" to component.id,
                        "detail" to error.localizedDescription(LocalizedText.literal(""))))
                false
            }
        }
        val topology = CircuitTopology.elaborate(document.copy(components = valid), registry)
        if (availableCircuitNames != null) for (component in document.components) {
            if (component.kind == ComponentKind.SUBCIRCUIT && component.circuitName !in availableCircuitNames) {
                diagnostics += diagnostic(DiagnosticCode.INVALID_REFERENCE, DiagnosticCategory.DOCUMENT,
                    DiagnosticTarget(document.id, instancePath, setOf(component.id), anchors = setOf(component.position)),
                    component.id, message("diagnostic_invalid_reference", "circuit" to component.circuitName.orEmpty()))
            }
        }
        val outputs = drivers ?: simulation?.componentOutputs?.flatMap { (componentId, ports) ->
            ports.map { (port, value) -> DiagnosticDriver(DiagnosticTerminalRef(componentId, port), value) }
        }.orEmpty()
        val byTerminal = outputs.associate { it.terminal to it.value }
        val weakByTerminal = valid.flatMap { component ->
            val pulls = registry.definition(component.kind)?.pullDrivers
                ?.invoke(component, registry.attributes(component)).orEmpty()
            pulls.map { (name, value) -> DiagnosticTerminalRef(component.id, name) to value }
        }.toMap()
        val conflictBits = mutableSetOf<String>()
        for (bitNet in topology.bitNets) {
            val strong = bitNet.terminals.mapNotNull { member ->
                if (member.terminal.direction == PortDirection.INPUT) null
                else byTerminal[member.terminal.reference]?.takeIf { member.bit < it.width }
                    ?.bit(member.bit)?.let { member to it }
            }
            val driven = if (strong.any { it.second != LogicValue.UNKNOWN }) strong else
                bitNet.terminals.mapNotNull { member ->
                    weakByTerminal[member.terminal.reference]?.let { value ->
                        val bit = if (value.width == 1) 0 else member.bit
                        value.takeIf { bit < it.width }?.bit(bit)?.let { member to it }
                    }
                }
            if (driven.none { it.second == LogicValue.LOW } || driven.none { it.second == LogicValue.HIGH }) continue
            conflictBits += bitNet.id
            val involved = driven.filter { it.second == LogicValue.LOW || it.second == LogicValue.HIGH }.map { it.first }
            val target = DiagnosticTarget(document.id, instancePath,
                involved.map { it.terminal.reference.componentId }.toSet(),
                involved.map { it.terminal.reference }.toSet(),
                bitNet.points.map { it.position }.toSet(),
                bitNet.points.flatMap { topology.netAt(it.position)?.wires.orEmpty() }.toSet())
            diagnostics += diagnostic(DiagnosticCode.DRIVER_CONFLICT, DiagnosticCategory.ELECTRICAL, target,
                bitNet.id, message("diagnostic_driver_conflict", "bit" to involved.first().bit,
                    "drivers" to involved.joinToString { "${it.terminal.reference.componentId}.${it.terminal.reference.terminalName}[${it.bit}]" }))
        }
        for (net in topology.nets) {
            val target = DiagnosticTarget(document.id, instancePath, net.terminals.map { it.reference.componentId }.toSet(),
                net.terminals.map { it.reference }.toSet(), net.points.toSet(), net.wires.toSet())
            if (net.declaredWidths.size > 1) {
                diagnostics += diagnostic(DiagnosticCode.WIDTH_MISMATCH, DiagnosticCategory.ELECTRICAL, target,
                    net.id, message("diagnostic_width_mismatch", "widths" to net.declaredWidths.sorted().joinToString(" / ")))
            } else {
                val errorBits = net.points.firstNotNullOfOrNull { simulation?.portVectors?.get(it) }?.let { vector ->
                    (0 until vector.width).filter { vector.bit(it) == LogicValue.ERROR }
                }.orEmpty().filter { bit ->
                    topology.bitAt(net.points.first(), bit)?.id !in conflictBits
                }
                if (errorBits.isNotEmpty()) diagnostics += diagnostic(DiagnosticCode.VALUE_ERROR,
                    DiagnosticCategory.ELECTRICAL, target, net.id,
                    message("diagnostic_value_error", "bits" to errorBits.joinToString(", ")))
            }
            if (includeUnconnectedInputs) for (terminal in net.terminals) {
                if (terminal.direction != PortDirection.INPUT || terminal.connected) continue
                val terminalTarget = target.copy(componentIds = setOf(terminal.reference.componentId),
                    terminals = setOf(terminal.reference), anchors = setOf(terminal.position))
                diagnostics += diagnostic(DiagnosticCode.UNCONNECTED_INPUT, DiagnosticCategory.ELECTRICAL,
                    terminalTarget, terminal.reference.key,
                    message("diagnostic_unconnected_input", "component" to terminal.reference.componentId,
                        "terminal" to terminal.reference.terminalName), DiagnosticSeverity.WARNING)
            }
        }
        if (simulation?.oscillating == true) {
            val feedback = combinationalFeedback(document, topology, registry)
            val target = DiagnosticTarget(document.id, instancePath, feedback,
                anchors = document.components.filter { it.id in feedback }.map { it.position }.toSet(),
                wires = topology.nets.filter { net -> net.terminals.any { it.reference.componentId in feedback } }
                    .flatMap { it.wires }.toSet())
            if (feedback.isNotEmpty() || instancePath.isEmpty()) {
                diagnostics += diagnostic(if (feedback.isEmpty()) DiagnosticCode.EXECUTION_LIMIT else DiagnosticCode.OSCILLATION,
                    DiagnosticCategory.EXECUTION, target, "propagation",
                    message(if (feedback.isEmpty()) "diagnostic_execution_limit" else "diagnostic_oscillation"))
            }
        }
        return DiagnosticReport(diagnostics.distinctBy { it.id }.sortedWith(compareBy({ it.category.ordinal }, { it.id })),
            mapOf(document.id to topology))
    }

    fun analyze(
        project: CircuitProject,
        simulation: SimulationState? = null,
        registry: ComponentRegistry = ComponentCatalog.registry,
        includeUnconnectedInputs: Boolean = true,
        maxInstances: Int = 4096,
    ): DiagnosticReport {
        require(maxInstances > 0) { "O limite de instâncias precisa ser positivo." }
        val diagnostics = mutableListOf<CircuitDiagnostic>()
        val topologies = mutableMapOf<String, CircuitTopology>()
        val definitions = project.circuits.associateBy { it.name }
        val availableNames = definitions.keys
        var visited = 0
        var limited = false
        data class Instance(val document: CircuitDocument, val path: List<String>, val ancestry: Set<String>, val live: Boolean)
        fun visit(origin: CircuitDocument, live: Boolean) {
            val pending = ArrayDeque<Instance>()
            pending.addLast(Instance(origin, emptyList(), setOf(origin.id), live))
            while (pending.isNotEmpty()) {
                if (visited >= maxInstances) { limited = true; break }
                val (document, path, ancestry, active) = pending.removeLast()
                visited++
                val state = if (!active) null else if (path.isEmpty()) simulation
                    else simulation?.circuitStates?.get(path.joinToString("/"))
                val result = analyze(document, state, registry, path, includeUnconnectedInputs,
                    availableCircuitNames = availableNames)
                diagnostics += result.diagnostics
                topologies += result.topologies
                for (component in document.components.asReversed().filter { it.kind == ComponentKind.SUBCIRCUIT }) {
                    val child = definitions[component.circuitName]
                    if (child == null || child.id in ancestry) {
                        val code = if (child == null) DiagnosticCode.INVALID_REFERENCE else DiagnosticCode.RECURSIVE_REFERENCE
                        val target = DiagnosticTarget(document.id, path, setOf(component.id), anchors = setOf(component.position))
                        diagnostics += diagnostic(code, DiagnosticCategory.DOCUMENT, target, component.id,
                            message(if (child == null) "diagnostic_invalid_reference" else "diagnostic_recursive_reference",
                                "circuit" to component.circuitName.orEmpty()))
                    } else pending.addLast(Instance(child, path + component.id, ancestry + child.id, active))
                }
            }
        }
        visit(project.mainCircuit, live = true)
        for (document in project.circuits) {
            if (document.id in topologies) continue
            if (visited >= maxInstances) { limited = true; break }
            visit(document, live = false)
        }
        if (simulation?.oscillating == true && !limited) {
            val feedback = hierarchicalDiagnosticFeedback(project, registry, maxInstances, topologies)
            if (feedback.isNotEmpty()) {
                diagnostics.removeAll { it.code == DiagnosticCode.OSCILLATION || it.code == DiagnosticCode.EXECUTION_LIMIT }
                for (origins in feedback.groupBy { it.circuitId to it.instancePath }.values) {
                    val target = origins.first().copy(
                        componentIds = origins.flatMap { it.componentIds }.toSet(),
                        terminals = origins.flatMap { it.terminals }.toSet(),
                        anchors = origins.flatMap { it.anchors }.toSet(),
                        wires = origins.flatMap { it.wires }.toSet())
                    diagnostics += diagnostic(DiagnosticCode.OSCILLATION, DiagnosticCategory.EXECUTION,
                        target, "propagation", message("diagnostic_oscillation"))
                }
            }
        }
        if (limited) diagnostics += diagnostic(DiagnosticCode.ANALYSIS_LIMIT, DiagnosticCategory.EXECUTION,
            DiagnosticTarget(project.mainCircuit.id), "diagnostic-instances",
            message("diagnostic_analysis_limit", "count" to maxInstances), DiagnosticSeverity.WARNING)
        return DiagnosticReport(diagnostics.distinctBy { it.id }, topologies, limited)
    }

    fun importFailure(detail: String, sourceId: String = "import"): CircuitDiagnostic =
        importFailure(LocalizedText.literal(detail), sourceId)

    fun importFailure(detail: LocalizedText, sourceId: String = "import"): CircuitDiagnostic = diagnostic(
        DiagnosticCode.IMPORT_ERROR, DiagnosticCategory.DOCUMENT, DiagnosticTarget(sourceId), "import",
        message("diagnostic_import_error", "detail" to detail))

    private fun combinationalFeedback(
        document: CircuitDocument,
        topology: CircuitTopology,
        registry: ComponentRegistry,
    ): Set<String> {
        val leaves = document.copy(components = document.components.filterNot { it.kind == ComponentKind.SUBCIRCUIT })
        return hierarchicalDiagnosticFeedback(CircuitProject(listOf(leaves)), registry, 1,
            mapOf(document.id to topology)).flatMap { it.componentIds }.toSet()
    }

    private fun diagnostic(
        code: DiagnosticCode,
        category: DiagnosticCategory,
        target: DiagnosticTarget,
        origin: String,
        notice: LocalizedText,
        severity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
    ): CircuitDiagnostic = CircuitDiagnostic(
        "${code.name}:${identityPart(target.circuitId)}${target.instancePath.joinToString("") { identityPart(it) }}:${identityPart(origin)}",
        code, severity, category, target, notice)
}
