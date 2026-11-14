package dev.logisim.mobile.core

/**
 * The components Evolution's chronogram lists by default: the factories with an InstanceLogger (LoggableContract
 * implementers in std/ at the pinned revision), by their file names. Gates, splitters, constants and subcircuit
 * components only appear when the picker shows every component.
 */
object TraceLoggable {
    val fileNames: Set<String> = setOf(
        // std/wiring
        "Pin", "Clock", "Probe", "Tunnel",
        // std/io and std/io/extra
        "Button", "LED", "RGBLED", "MatrixKeypad", "NE555", "PlaRom", "ProgrammableGenerator", "Switch", "TWOPINLED",
        "TwoWaySwitch",
        // std/memory
        "D Flip-Flop", "T Flip-Flop", "J-K Flip-Flop", "S-R Flip-Flop", "Register", "Counter", "Shift Register",
        "RAM", "DualRAM", "Random",
    )

    fun isLoggable(component: CircuitComponent, registry: ComponentRegistry = ComponentCatalog.registry): Boolean =
        component.kind != ComponentKind.SUBCIRCUIT && registry.definition(component.kind)?.fileName in fileNames
}

/** The signals of one circuit instance in the picker: [path] names it from the main circuit down. */
data class TraceSignalGroup(val instancePath: String, val path: List<String>, val signals: List<TraceSignal>)

object TraceSignalPicker {
    /**
     * Human names of every instance path: the main circuit's name, then each subcircuit component's label, or its
     * circuit and location as Evolution's ComponentSelector shows them ("ALU(120,40)"). Sibling instances that share a
     * label also show their location ("Sub1 (300,100)"), so their signals never read the same.
     */
    fun instanceNames(project: CircuitProject): Map<String, List<String>> {
        val result = linkedMapOf("" to listOf(project.mainCircuitName))
        fun visit(circuit: CircuitDocument, path: String, names: List<String>, parents: Set<String>) {
            if (circuit.id in parents) return
            val instances = circuit.components.filter { it.kind == ComponentKind.SUBCIRCUIT && project.circuit(it.circuitName.orEmpty()) != null }
            val sharedLabels = instances.map { it.label }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            for (component in instances) {
                val child = project.circuit(component.circuitName.orEmpty()) ?: continue
                val childPath = if (path.isEmpty()) component.id else "$path/${component.id}"
                val location = "(${component.position.x},${component.position.y})"
                val childNames = names + when {
                    component.label.isEmpty() -> "${child.name}$location"
                    component.label in sharedLabels -> "${component.label} $location"
                    else -> component.label
                }
                result[childPath] = childNames
                visit(child, childPath, childNames, parents + circuit.id)
            }
        }
        visit(project.mainCircuit, "", listOf(project.mainCircuitName), emptySet())
        return result
    }

    /** The component a signal observes, or null when the project no longer has it. */
    fun component(project: CircuitProject, reference: TraceSignalReference): CircuitComponent? {
        var circuit = project.mainCircuit
        if (reference.instancePath.isNotEmpty()) for (id in reference.instancePath.split('/')) {
            val instance = circuit.components.firstOrNull { it.id == id && it.kind == ComponentKind.SUBCIRCUIT } ?: return null
            circuit = project.circuit(instance.circuitName.orEmpty()) ?: return null
        }
        return circuit.components.firstOrNull { it.id == reference.componentId }
    }

    /**
     * The picker's rows by instance, in project order. Without [showAll] only loggable components are listed;
     * signals already [selected] and their RAM cells stay listed either way, so an older selection never hides.
     */
    fun groups(
        project: CircuitProject,
        available: List<TraceSignal>,
        selected: List<TraceSignal>,
        showAll: Boolean,
        registry: ComponentRegistry = ComponentCatalog.registry,
        matches: (TraceSignal, List<String>) -> Boolean = { _, _ -> true },
    ): List<TraceSignalGroup> {
        val names = instanceNames(project)
        val chosen = selected.map { it.reference }.toSet()
        val rows = (available + selected.filter { candidate -> available.none { it.reference == candidate.reference } })
            .filter { signal ->
                signal.reference in chosen || showAll ||
                    component(project, signal.reference)?.let { TraceLoggable.isLoggable(it, registry) } == true
            }
        return rows.groupBy { it.reference.instancePath }.mapNotNull { (path, signals) ->
            val pathNames = names[path] ?: listOf(project.mainCircuitName) + path.split('/')
            val shown = signals.filter { matches(it, pathNames) }
            if (shown.isEmpty()) null else TraceSignalGroup(path, pathNames, shown)
        }.sortedBy { group -> names.keys.indexOf(group.instancePath).let { if (it < 0) Int.MAX_VALUE else it } }
    }
}

/** A parsed RAM cell entry: the addresses in order, or why it was rejected. */
sealed interface TraceRamCellEntry {
    data class Addresses(val ranges: List<LongRange>) : TraceRamCellEntry {
        val count: Long get() = ranges.sumOf { it.last - it.first + 1 }
        fun addresses(limit: Int): List<Long> = ranges.asSequence().flatMap { it.asSequence() }.distinct().take(limit).toList()
    }
    data object Empty : TraceRamCellEntry
    data object Invalid : TraceRamCellEntry
    data class OutOfRange(val last: Long) : TraceRamCellEntry
}

object TraceRamCells {
    /**
     * Reads RAM addresses as the memory editor shows them: hexadecimal without a prefix ("1F0"), or with "0x".
     * A dash gives an inclusive range ("1F0-1F7") and commas separate entries ("10, 20-23").
     */
    fun parse(text: String, addressBits: Int): TraceRamCellEntry {
        val last = (1L shl addressBits) - 1
        val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return TraceRamCellEntry.Empty
        val ranges = mutableListOf<LongRange>()
        for (part in parts) {
            val bounds = part.split('-').map { it.trim() }
            if (bounds.size !in 1..2) return TraceRamCellEntry.Invalid
            val values = bounds.map { address(it) ?: return TraceRamCellEntry.Invalid }
            val first = values.first()
            val end = values.last()
            if (end < first) return TraceRamCellEntry.Invalid
            if (first > last || end > last) return TraceRamCellEntry.OutOfRange(last)
            ranges += first..end
        }
        return TraceRamCellEntry.Addresses(ranges)
    }

    private fun address(text: String): Long? {
        val digits = if (text.startsWith("0x", ignoreCase = true)) text.substring(2) else text
        if (digits.isEmpty() || digits.length > 15 || digits.any { it.digitToIntOrNull(16) == null }) return null
        return digits.toLong(16)
    }
}
