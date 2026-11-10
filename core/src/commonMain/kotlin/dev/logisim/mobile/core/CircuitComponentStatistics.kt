package dev.logisim.mobile.core

data class ComponentOccurrenceCounts(val local: Long, val unique: Long, val recursive: Long) {
    internal operator fun plus(other: ComponentOccurrenceCounts) = ComponentOccurrenceCounts(
        addOccurrences(local, other.local),
        addOccurrences(unique, other.unique),
        addOccurrences(recursive, other.recursive),
    )
}

data class ComponentStatisticsRow(
    val library: String?,
    val name: String,
    val counts: ComponentOccurrenceCounts,
    val registeredComponent: Boolean = false,
)

data class CircuitComponentStatistics(
    val rows: List<ComponentStatisticsRow>,
    val totalWithoutSubcircuits: ComponentOccurrenceCounts,
    val totalWithSubcircuits: ComponentOccurrenceCounts,
) {
    companion object {
        fun measure(
            project: CircuitProject,
            circuitName: String = project.mainCircuitName,
            registry: ComponentRegistry = ComponentCatalog.registry,
            checkCancellation: () -> Unit = {},
        ): CircuitComponentStatistics {
            val externalTypes = ProjectCircuitLibraries.list(project)
                .filter { library -> library.circuits.none { it.name == circuitName } }
                .flatMap { library -> library.circuits.map { it.name to ExternalComponentType(library.name, it.name) } }
                .toMap()
            return measure(project, circuitName, registry, externalTypes, checkCancellation)
        }

        internal fun measure(
            project: CircuitProject,
            circuitName: String,
            registry: ComponentRegistry,
            externalTypes: Map<String, ExternalComponentType>,
            checkCancellation: () -> Unit,
        ): CircuitComponentStatistics {
            val selected = project.copy(mainCircuitName = circuitName)
            val instances = CircuitStatistics.countCircuitInstances(selected, externalTypes.keys, checkCancellation)
            val rootId = selected.mainCircuit.id
            val counts = mutableMapOf<ComponentType, ComponentOccurrenceCounts>()
            val empty = ComponentOccurrenceCounts(0, 0, 0)
            for (circuit in selected.circuits) {
                val copies = instances[circuit.id] ?: continue
                for (component in circuit.components) {
                    checkCancellation()
                    // Evolution's FileStatistics lists only factories reached through an AddTool. Base/Text is
                    // placed by the Text tool, so the desktop statistics never count text annotations.
                    if (component.kind == ComponentKind.TEXT) continue
                    val type = if (component.kind == ComponentKind.SUBCIRCUIT) {
                        val name = requireNotNull(component.circuitName)
                        val external = externalTypes[name]
                        if (external == null) ComponentType(null, name) else ComponentType(external.library, external.name, name)
                    } else {
                        val definition = registry.requireDefinition(component.kind)
                        ComponentType(definition.library, definition.fileName)
                    }
                    val increment = ComponentOccurrenceCounts(if (circuit.id == rootId) 1 else 0, 1, copies)
                    counts[type] = (counts[type] ?: empty) + increment
                }
            }
            val rows = counts.entries.sortedWith(compareBy({ it.key.library.orEmpty() }, { it.key.name }))
                .map { (type, count) ->
                    ComponentStatisticsRow(type.library, type.name, count,
                        registeredComponent = type.library != null && type.externalDefinition == null)
                }
            return CircuitComponentStatistics(
                rows,
                rows.filter { it.library != null }.fold(empty) { total, row -> total + row.counts },
                rows.fold(empty) { total, row -> total + row.counts },
            )
        }
    }
}

internal data class ExternalComponentType(val library: String, val name: String)

private data class ComponentType(val library: String?, val name: String, val externalDefinition: String? = null)

private fun addOccurrences(first: Long, second: Long): Long {
    require(first <= Long.MAX_VALUE - second) { "Expanded component counts exceed the supported limit." }
    return first + second
}
