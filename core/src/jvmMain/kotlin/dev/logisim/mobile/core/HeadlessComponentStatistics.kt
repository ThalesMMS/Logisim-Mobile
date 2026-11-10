package dev.logisim.mobile.core

import java.nio.file.Path

internal object HeadlessComponentStatistics {
    fun rows(
        project: CircuitProject,
        registry: ComponentRegistry,
        resolved: ResolvedCircuitProject? = null,
        checkCancellation: () -> Unit,
    ): List<List<String>> {
        val externalTypes = buildMap {
            if (resolved != null) {
                for ((source, file) in resolved.sources) {
                    if (source == resolved.rootSourceId) continue
                    val library = Path.of(source).fileName.toString().removeSuffix(".circ")
                    for ((name, definition) in file.names) put(definition, ExternalComponentType(library, name))
                }
            }
        }
        val statistics = CircuitComponentStatistics.measure(project, project.mainCircuitName, registry, externalTypes, checkCancellation)
        return buildList {
            statistics.rows.forEach { (library, name, count) ->
                val kind = if (library == null) "subcircuit" else "component"
                add(listOf(kind, library.orEmpty(), name) + count.fields())
            }
            add(listOf("total", "", "without-subcircuits") + statistics.totalWithoutSubcircuits.fields())
            add(listOf("total", "", "with-subcircuits") + statistics.totalWithSubcircuits.fields())
        }
    }

    private fun ComponentOccurrenceCounts.fields() = listOf(local.toString(), unique.toString(), recursive.toString())
}
