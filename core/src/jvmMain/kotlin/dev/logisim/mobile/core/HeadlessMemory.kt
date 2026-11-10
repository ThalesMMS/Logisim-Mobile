package dev.logisim.mobile.core

import java.io.File

/** Memory overrides and the dump target for one headless execution. */
internal class HeadlessMemory(
    project: CircuitProject,
    loads: Map<String, File>,
    private val destination: File?,
    private val checkCancellation: () -> Unit,
    /** Evolution's -l applies an unlabeled image to every RAM and ROM; the 2.7.1 profile only to RAMs. */
    unlabeledIncludesRoms: Boolean = false,
) {
    private data class Location(val path: String, val component: CircuitComponent) {
        val isRam: Boolean get() = component.kind != ComponentKind.ROM
        val key: String get() = if (path.isEmpty()) component.id else "$path/${component.id}"
    }

    private val dumpTarget: Location?
    val initialState: SimulationState

    init {
        val memories = if (loads.isEmpty() && destination == null) emptyList() else buildList {
            fun visit(circuit: CircuitDocument, path: String, ancestors: Set<String>) {
                checkCancellation()
                require(circuit.name !in ancestors) { "Recursive circuit '${circuit.name}'." }
                for (component in circuit.components) {
                    if (component.kind in memoryKinds) add(Location(path, component))
                }
                for (component in circuit.components) {
                    if (component.kind != ComponentKind.SUBCIRCUIT) continue
                    val name = requireNotNull(component.circuitName)
                    val child = requireNotNull(project.circuit(name)) { "No circuit named '$name'." }
                    val childPath = if (path.isEmpty()) component.id else "$path/${component.id}"
                    visit(child, childPath, ancestors + circuit.name)
                }
            }
            visit(project.mainCircuit, "", emptySet())
        }
        dumpTarget = destination?.let {
            requireNotNull(memories.lastOrNull { it.isRam }) {
                "--save requires a RAM in the selected circuit or its subcircuits."
            }
        }
        val overrides = loads.keys.filter { it.isNotEmpty() }.associateWith { label ->
            val matches = memories.filter { it.component.label == label }
            require(matches.isNotEmpty()) { "No RAM or ROM labeled '$label' for --load." }
            require(matches.size == 1) { "Ambiguous memory label '$label' for --load: ${matches.joinToString { it.key }}." }
            matches.single()
        }
        val states = mutableMapOf<String, MutableMap<String, MemoryComponentState>>()
        val limits = MemoryImageLimits()
        for ((label, file) in loads) {
            checkCancellation()
            val targets = if (label.isEmpty()) {
                memories.filter { (it.isRam || unlabeledIncludesRoms) && it.component.label !in overrides }
            } else listOf(overrides.getValue(label))
            require(targets.isNotEmpty()) { "--load requires a RAM without a labeled override in the selected circuit or its subcircuits." }
            val bytes = file.inputStream().use { it.readNBytes(limits.maxInputBytes + 1) }
            require(bytes.size <= limits.maxInputBytes) { "Memory image exceeds 16 MiB." }
            for (target in targets) {
                checkCancellation()
                val component = target.component
                val configuration = MemoryComponents.configuration(component)
                val image = when (val imported = MemoryImageCodec.decode(bytes, configuration.addressBits, configuration.dataBits)) {
                    is MemoryImageImportResult.Success -> imported.image
                    is MemoryImageImportResult.Failure -> throw IllegalArgumentException(
                        "Cannot load memory '${target.key}': ${imported.diagnostics.joinToString("; ")}")
                }
                val source = if (target.isRam) "" else component.attributes["contents"].orEmpty()
                states.getOrPut(target.path) { mutableMapOf() }[component.id] =
                    MemoryComponentState(MemoryRuntimeState.initial(configuration, image), source)
            }
        }
        initialState = SimulationState(componentStates = states.remove("").orEmpty(),
            circuitStates = states.mapValues { (path, components) ->
                SimulationState(instancePath = path, componentStates = components)
            })
    }

    fun save(state: SimulationState) {
        val target = dumpTarget ?: return
        checkCancellation()
        val instance = if (target.path.isEmpty()) state else state.circuitStates.getValue(target.path)
        val image = checkNotNull(MemoryComponents.image(instance.componentStates.getValue(target.component.id))) {
            "RAM '${target.key}' has no simulation image."
        }
        checkNotNull(destination).bufferedWriter().use { MemoryImageCodec.writeFullHexWordsPlain(image, it) }
    }

    private companion object {
        val memoryKinds = setOf(ComponentKind.RAM, MemoryComponents.DualRam, ComponentKind.ROM)
    }
}
