package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message

enum class HdlLanguage(val extension: String) { VERILOG("v"), VHDL("vhd") }
enum class HdlDiagnosticSeverity { WARNING, ERROR }

data class HdlExportDiagnostic(
    val code: String,
    val severity: HdlDiagnosticSeverity,
    val message: String,
    val circuitId: String,
    val componentId: String? = null,
    val terminalName: String? = null,
    val reason: LocalizedText = LocalizedText.literal(message),
) {
    val notice: LocalizedText get() = LocalizedText("hdl_diagnostic_${code.removePrefix("HDL_").lowercase()}",
        fallback = message, localizedArguments = mapOf("reason" to reason))
}

data class HdlSourceFile(val path: String, val content: String)

data class HdlExportPort(
    val name: String,
    val direction: PortDirection,
    val width: Int,
    val sourceCircuitId: String,
    val sourceComponentId: String,
    val reptarBank: ReptarPinBank? = null,
)

data class HdlExportResult(
    val topLevelName: String,
    val files: List<HdlSourceFile>,
    val ports: List<HdlExportPort>,
    val diagnostics: List<HdlExportDiagnostic>,
) {
    val successful: Boolean get() = diagnostics.none { it.severity == HdlDiagnosticSeverity.ERROR }
}

/** RTL generation has binary, defined inputs. Simulator X/E propagation and physical delays are not RTL. */
object HdlExporter {
    internal fun rootStimulusPorts(project: CircuitProject): Map<String, String> {
        val components = externalHdlComponents(project.mainCircuit)
        val names = externalHdlPortNames(components)
        return components.filter { it.kind == ComponentKind.CLOCK }.associate { it.id to names.getValue(it.id) }
    }

    fun export(
        project: CircuitProject,
        language: HdlLanguage,
        registry: ComponentRegistry = ComponentCatalog.registry,
        options: FpgaGenerationOptions = FpgaGenerationOptions(),
    ): HdlExportResult = emit(project, language, HdlLowering(project, registry, language), options = options)

    internal fun exportForFpga(
        project: CircuitProject,
        language: HdlLanguage,
        registry: ComponentRegistry,
        options: FpgaGenerationOptions = FpgaGenerationOptions(),
    ): HdlExportResult = emit(project, language, HdlLowering(project, registry, language), forFpga = true, options = options)

    internal fun exportWithFpgaClocks(
        project: CircuitProject,
        language: HdlLanguage,
        analysis: FpgaConnectivityAnalysis,
        registry: ComponentRegistry = ComponentCatalog.registry,
        options: FpgaGenerationOptions = FpgaGenerationOptions(),
    ): FpgaClockHdlExport {
        if (analysis.dedicatedClocks.isEmpty())
            return FpgaClockHdlExport(exportForFpga(project, language, registry, options), null, emptyList())
        return try {
            val clocks = HdlFpgaClockLowering(project, analysis, registry, language)
            val result = emit(project, language, HdlLowering(project, registry, language, clocks), forFpga = true, options = options)
            FpgaClockHdlExport(result, clocks.oscillatorPort, clocks.waveforms)
        } catch (failure: IllegalArgumentException) {
            val diagnostic = HdlExportDiagnostic("HDL_UNSUPPORTED_CONFIGURATION", HdlDiagnosticSeverity.ERROR,
                failure.message ?: "Invalid FPGA clock configuration.", project.mainCircuit.id)
            FpgaClockHdlExport(HdlExportResult("", emptyList(), emptyList(), listOf(diagnostic)), null, emptyList())
        }
    }

    private fun emit(
        project: CircuitProject,
        language: HdlLanguage,
        lowering: HdlLowering,
        forFpga: Boolean = false,
        options: FpgaGenerationOptions = FpgaGenerationOptions(),
    ): HdlExportResult {
        val modules = lowering.modules()
        val top = modules.firstOrNull { it.document.name == project.mainCircuitName }
        val names = lowering.moduleNames
        val topName = names.getValue(project.mainCircuitName)
        val generated = lowering.vhdlSources + modules.map { module ->
            val emitter = HdlEmitter(module, language, lowering.diagnostics, forFpga)
            val text = emitter.emit()
            // Only generated modules follow the keyword preference; VHDL entity sources keep the user's text.
            HdlSourceFile("${module.name}.${language.extension}",
                if (language == HdlLanguage.VHDL) VhdlKeywordCase.apply(text, options.vhdlKeywordsUpperCase) else text)
        }
        val ports = top?.ports.orEmpty().map {
            HdlExportPort(it.name, it.direction, it.bits.size, top!!.document.id, it.component.id)
        } + top?.pinBanks.orEmpty()
        val diagnostics = lowering.diagnostics.distinct()
        return HdlExportResult(topName,
            if (diagnostics.any { it.severity == HdlDiagnosticSeverity.ERROR }) emptyList() else generated,
            ports, diagnostics)
    }
}

internal data class FpgaClockHdlWaveform(
    val source: FpgaClockComponentRef,
    val waveform: FpgaEvolutionClockHdl.Waveform,
    val levelPort: String,
    val invertedLevelPort: String,
    val risePort: String,
    val fallPort: String,
)

internal data class FpgaClockHdlExport(
    val export: HdlExportResult,
    val oscillatorPort: String?,
    val waveforms: List<FpgaClockHdlWaveform>,
)

internal data class HdlTimingPort(val name: String, val direction: PortDirection)

internal data class HdlClockSignals(
    val edgeClock: String,
    val riseEnable: String,
    val fallEnable: String,
    val invertedLevel: String? = null,
    val fallingSamplerClock: String? = null,
    val ramDedicatedMode: String? = null,
)

internal data class HdlPort(
    val name: String,
    val direction: PortDirection,
    val bits: List<String>,
    val component: CircuitComponent,
)

internal data class HdlEndpoint(
    val terminal: ComponentTerminal,
    val bits: List<String>,
    val driven: List<Boolean>,
) {
    val width: Int get() = bits.size
}

internal data class HdlCell(
    val component: CircuitComponent,
    val prefix: String,
    val inputs: List<HdlEndpoint>,
    val outputs: List<HdlEndpoint>,
    val vhdl: HdlIpDesign? = null,
    val pinBank: HdlExportPort? = null,
    val reptarBanks: List<HdlExportPort> = emptyList(),
    val fpgaClock: HdlClockSignals? = null,
)

internal data class HdlInstancePort(val child: HdlPort, val endpoint: HdlEndpoint)
internal data class HdlInstance(
    val id: String,
    val moduleName: String,
    val ports: List<HdlInstancePort>,
    val pinBanks: Map<String, String>,
    val timingBindings: Map<String, String> = emptyMap(),
    val componentId: String = id,
    val timingParameters: Map<String, String> = emptyMap(),
)

internal data class HdlModule(
    val document: CircuitDocument,
    val name: String,
    val ports: List<HdlPort>,
    val signals: List<String>,
    val cells: List<HdlCell>,
    val instances: List<HdlInstance>,
    val pinBanks: List<HdlExportPort> = emptyList(),
    val timingPorts: List<HdlTimingPort> = emptyList(),
    val timingAssignments: List<Pair<String, String>> = emptyList(),
    val timingParameters: List<String> = emptyList(),
)

private fun externalHdlComponents(document: CircuitDocument): List<CircuitComponent> = document.components.filter {
    it.kind in setOf(ComponentKind.INPUT, ComponentKind.OUTPUT, ComponentKind.CLOCK, ComponentKind.BUTTON)
}.sortedBy { it.id }

private fun externalHdlPortNames(components: List<CircuitComponent>): Map<String, String> = uniqueNames(components.map {
    it.id to "p_${hdlIdentifier(it.label.ifBlank { if (it.kind == ComponentKind.CLOCK) "clock_${it.id}" else it.id })}"
})

internal class HdlLowering(
    private val project: CircuitProject,
    private val registry: ComponentRegistry,
    private val language: HdlLanguage,
    private val fpgaClocks: HdlFpgaClockLowering? = null,
) {
    val diagnostics = mutableListOf<HdlExportDiagnostic>()
    var moduleNames: Map<String, String> = emptyMap()
        private set
    private val built = mutableMapOf<String, HdlModule>()
    private val sourcesByEntity = mutableMapOf<String, String>()
    private val designsByComponent = mutableMapOf<CircuitComponent, HdlIpDesign>()
    val vhdlSources: List<HdlSourceFile> get() {
        val paths = uniqueNames(sourcesByEntity.keys.map { name -> name to "hdl_ip_${name.take(180)}" })
        return sourcesByEntity.entries.sortedBy { it.key }.map { (name, source) ->
            HdlSourceFile("${paths.getValue(name)}.vhd", source)
        }
    }

    fun modules(): List<HdlModule> {
        val ordered = mutableListOf<CircuitDocument>()
        val visited = mutableSetOf<String>()
        fun visit(document: CircuitDocument, stack: Set<String>) {
            if (document.name in visited) return
            for (component in document.components.filter { it.kind == ComponentKind.SUBCIRCUIT }.sortedBy { it.id }) {
                val child = project.circuit(component.circuitName.orEmpty())
                when {
                    child == null -> error(document, component, "HDL_MISSING_CIRCUIT", "The referenced circuit is missing.")
                    child.name in stack -> error(document, component, "HDL_RECURSIVE_CIRCUIT", "Recursive circuit instances cannot be exported.")
                    else -> visit(child, stack + child.name)
                }
            }
            visited += document.name
            ordered += document
        }
        visit(project.mainCircuit, setOf(project.mainCircuitName))
        if (language == HdlLanguage.VHDL) {
            if (ordered.any { document -> document.components.any { it.kind == ExternalIoComponents.ReptarLocalBus } }) {
                sourcesByEntity[ReptarLocalBusHdl.ENTITY] = ReptarLocalBusHdl.source
            }
            ordered.forEach(::registerVhdl)
        }
        moduleNames = uniqueNames(project.circuits.map { it.name to "c_${hdlIdentifier(it.name)}" }, sourcesByEntity.keys)
        for (document in ordered) {
            try {
                built[document.name] = lower(document)
            } catch (exception: IllegalArgumentException) {
                error(document, null, "HDL_INVALID_LAYOUT", exception.message ?: "The circuit layout is invalid.")
            }
        }
        return ordered.mapNotNull { built[it.name] }
    }

    private fun registerVhdl(document: CircuitDocument) {
        for (component in document.components.filter { it.kind == HdlIpLibrary.VHDL }.sortedBy { it.id }) {
            val design = HdlIpLibrary.parse(component)
            val entity = design.topLevel.lowercase()
            val previous = sourcesByEntity[entity]
            val reason = when {
                !design.isExecutable -> LocalizedText.join(design.diagnostics.map { it.notice })
                design.ports.any { it.direction == PortDirection.INOUT } -> message("hdl_reason_vhdl_inout")
                previous != null && previous != design.source ->
                    message("hdl_reason_vhdl_entity_collision", "entity" to design.topLevel)
                else -> null
            }
            if (reason != null) {
                diagnostics += HdlExportDiagnostic("HDL_UNSUPPORTED_CONFIGURATION", HdlDiagnosticSeverity.ERROR,
                    reason.resolve(), document.id, component.id, reason = reason)
            } else {
                sourcesByEntity[entity] = design.source
                designsByComponent[component] = design
            }
        }
    }

    private fun lower(document: CircuitDocument): HdlModule {
        val layouts = mutableMapOf<CircuitComponent, ComponentLayout>()
        for (component in document.components) {
            try {
                layouts[component] = component.layout(registry)
            } catch (exception: IllegalArgumentException) {
                error(document, component, "HDL_INVALID_LAYOUT", exception.message ?: "The component layout is invalid.")
            }
        }
        if (layouts.size != document.components.size) return HdlModule(document, moduleNames.getValue(document.name),
            emptyList(), emptyList(), emptyList(), emptyList())
        val networks = CircuitNetworks(document, registry)
        for (message in networks.diagnostics) error(document, null, "HDL_INVALID_NETWORK", message)
        val topology = networks.bitNetworks()
        val orderedNodes = topology.entries.sortedWith(compareBy({ it.key.x }, { it.key.y }))
            .flatMap { it.value }.distinct()
        val signalNames = orderedNodes.mapIndexed { index, node -> node to "s_${index.toString().padStart(4, '0')}" }.toMap()
        val drivers = mutableMapOf<Int, MutableList<Pair<CircuitComponent, String>>>()
        for ((component, layout) in layouts) {
            if (component.kind.name in passiveKinds) continue
            for (terminal in layout.outputs) {
                topology[terminal.position].orEmpty().forEachIndexed { bit, node ->
                    drivers.getOrPut(node) { mutableListOf() }.add(component to "${terminal.name}[$bit]")
                }
            }
        }
        for ((_, components) in drivers) {
            val strong = components.distinctBy { it.first.id to it.second }.filterNot { (component, _) ->
                component.kind == ComponentKind.CONTROLLED_BUFFER || component.kind == ComponentKind.CONTROLLED_INVERTER
            }
            if (strong.size > 1) {
                error(document, strong.first().first, "HDL_MULTIPLE_DRIVERS",
                    "A signal has multiple output drivers: ${strong.joinToString { "${it.first.id}.${it.second}" }}.")
            }
        }
        fun endpoint(terminal: ComponentTerminal): HdlEndpoint {
            val ids = topology[terminal.position].orEmpty()
            require(ids.isNotEmpty()) { "Terminal ${terminal.name} has no elaborated network." }
            return HdlEndpoint(terminal, ids.map { signalNames.getValue(it) }, ids.map { !drivers[it].isNullOrEmpty() })
        }
        val external = externalHdlComponents(document)
        val portNames = externalHdlPortNames(external)
        val externalPorts = external.map { component ->
            val terminal = layouts.getValue(component).ports.firstOrNull()
            require(terminal != null) { "External component ${component.id} has no terminal." }
            val point = endpoint(terminal)
            if (component.kind == ComponentKind.BUTTON || component.kind == ComponentKind.CLOCK && fpgaClocks == null) {
                if (document.name != project.mainCircuitName) {
                    error(document, component, "HDL_NESTED_STIMULUS",
                        "Route clocks and interactive inputs through circuit pins before exporting a child circuit.")
                } else warning(document, component, "HDL_EXTERNAL_STIMULUS",
                    "This interactive component becomes a top-level input; its simulator timing is not generated in RTL.")
            }
            if (component.kind == ComponentKind.INPUT && component.attributes["behavior"] == "tristate") {
                error(document, component, "HDL_TRISTATE_PIN", "Tri-state interactive pins are not supported by the synthesizable RTL profile.")
            }
            if (component.kind == ComponentKind.OUTPUT && point.driven.any { !it }) {
                error(document, component, "HDL_UNDRIVEN_OUTPUT", "An output pin contains an undriven bit.")
            }
            HdlPort(portNames.getValue(component.id),
                if (component.kind == ComponentKind.OUTPUT) PortDirection.OUTPUT else PortDirection.INPUT,
                point.bits, component)
        }
        val clockPorts = if (fpgaClocks == null) emptyList() else externalPorts.filter { it.component.kind == ComponentKind.CLOCK }
        val ports = externalPorts - clockPorts.toSet()
        val cells = mutableListOf<HdlCell>()
        val instances = mutableListOf<HdlInstance>()
        val pinBanks = mutableListOf<HdlExportPort>()
        fun addPinBank(port: HdlExportPort, desired: String): HdlExportPort {
            var name = desired
            var suffix = 2
            while (pinBanks.any { it.name == name }) name = "${desired}_${suffix++}"
            return port.copy(name = name).also(pinBanks::add)
        }
        val cellNames = uniqueNames(document.components.map { it.id to "v_${hdlIdentifier(it.id)}" })
        for ((component, layout) in layouts.entries.sortedBy { it.key.id }) {
            if (component.kind == ComponentKind.SUBCIRCUIT) {
                val child = built[component.circuitName] ?: continue
                val bindings = project.subcircuitPortBindings(component).associate { it.second.id to it.first }
                val bound = child.ports.mapNotNull { childPort ->
                    val point = bindings[childPort.component.id]
                    if (point == null) {
                        error(document, component, "HDL_MISSING_INSTANCE_PORT", "A circuit pin has no matching appearance port: ${childPort.component.id}.")
                        null
                    } else {
                        val terminal = layout.ports.firstOrNull { it.position == point }
                        if (terminal == null) {
                            error(document, component, "HDL_MISSING_INSTANCE_PORT", "An instance appearance port is missing.")
                            null
                        } else {
                            val actual = endpoint(terminal)
                            if (actual.width != childPort.bits.size) {
                                error(document, component, "HDL_INSTANCE_PORT_WIDTH", "The instance terminal width differs from its circuit pin.")
                                null
                            } else HdlInstancePort(childPort, actual)
                        }
                    }
                }
                val id = cellNames.getValue(component.id)
                val forwarded = child.pinBanks.associate { port ->
                    port.name to addPinBank(port, "io_${id}_${port.name}").name
                }
                instances += HdlInstance(id, child.name, bound, forwarded, componentId = component.id)
            } else if (component !in external) {
                if (component.kind.name in observerKinds) {
                    warning(document, component, "HDL_OBSERVER_OMITTED", "A display or annotation observes the circuit and does not produce RTL.")
                } else if (component.kind.name !in passiveKinds) {
                    val definition = registry.definition(component.kind)
                    val defaults = definition?.schema?.defaults.orEmpty().let { if (component.kind == ComponentKind.COUNTER) it - "max" else it }
                    val effective = defaults + definition?.defaultAttributes.orEmpty() +
                        component.implicitAttributes + component.attributes
                    val pinBank = if (component.kind == ExternalIoComponents.PortIo) {
                        val attributes = registry.attributes(component)
                        val direction = when (attributes[ExternalIoComponents.PortMode]) {
                            "onlyinput" -> PortDirection.INPUT
                            "onlyOutput" -> PortDirection.OUTPUT
                            else -> PortDirection.INOUT
                        }
                        val name = "io_" + hdlIdentifier(component.label.ifBlank { component.id })
                        addPinBank(HdlExportPort(name, direction, attributes[ExternalIoComponents.Number],
                            document.id, component.id), name)
                    } else null
                    val reptarBanks = if (language == HdlLanguage.VHDL && component.kind == ExternalIoComponents.ReptarLocalBus) {
                        val prefix = "reptar_" + hdlIdentifier(component.label.ifBlank { component.id })
                        ReptarPinBank.entries.map { bank ->
                            val name = "${prefix}_${bank.suffix}"
                            addPinBank(HdlExportPort(name, bank.direction, bank.width, document.id, component.id, bank), name)
                        }
                    } else emptyList()
                    cells += HdlCell(component.copy(attributes = effective), cellNames.getValue(component.id),
                        layout.inputs.map(::endpoint), layout.outputs.map(::endpoint),
                        vhdl = designsByComponent[component], pinBank = pinBank, reptarBanks = reptarBanks)
                }
            }
        }
        if (ports.none { it.direction == PortDirection.OUTPUT } && pinBanks.none { it.direction != PortDirection.INPUT } &&
            document == project.mainCircuit) {
            warning(document, null, "HDL_NO_OUTPUT_PORTS", "The top-level circuit has no output pins; synthesis may remove unobserved logic.")
        }
        val module = HdlModule(document, moduleNames.getValue(document.name), ports, signalNames.values.toList(), cells, instances, pinBanks)
        return fpgaClocks?.lower(module, clockPorts) ?: module
    }

    private fun error(document: CircuitDocument, component: CircuitComponent?, code: String, message: String) {
        diagnostics += HdlExportDiagnostic(code, HdlDiagnosticSeverity.ERROR, message, document.id, component?.id)
    }
    private fun warning(document: CircuitDocument, component: CircuitComponent?, code: String, message: String) {
        diagnostics += HdlExportDiagnostic(code, HdlDiagnosticSeverity.WARNING, message, document.id, component?.id)
    }

    companion object {
        private val observerKinds = setOf("PROBE", "HEX_DISPLAY", "SEVEN_SEGMENT_DISPLAY", "LED", "RGB_LED", "LED_BAR", "DOT_MATRIX", "TEXT")
        private val passiveKinds = observerKinds + setOf("SPLITTER", "TUNNEL", "NO_CONNECT")
    }
}

internal fun hdlIdentifier(raw: String): String {
    val text = buildString {
        for (character in raw) when {
            character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' -> append(character.lowercaseChar())
            character == '_' || character == ' ' || character == '-' -> append('_')
            else -> append("_u${character.code.toString(16).padStart(4, '0')}_")
        }
    }.split('_').filter { it.isNotEmpty() }.joinToString("_").ifEmpty { "unnamed" }
    return if (text.first() in '0'..'9') "n_$text" else text
}

internal fun uniqueNames(candidates: List<Pair<String, String>>): Map<String, String> =
    uniqueNames(candidates, emptySet())

private fun uniqueNames(candidates: List<Pair<String, String>>, reserved: Set<String>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val used = reserved.toMutableSet()
    for ((id, desired) in candidates.sortedBy { it.first }) {
        var name = desired
        var suffix = 2
        while (!used.add(name.lowercase())) name = "${desired}_${suffix++}"
        result[id] = name
    }
    return result
}
