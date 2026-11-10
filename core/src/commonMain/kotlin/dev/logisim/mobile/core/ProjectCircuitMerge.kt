package dev.logisim.mobile.core

object ProjectCircuitMerge {
    fun copySelected(destination: CircuitProject, source: CircuitProject, selected: Set<String>): CircuitProject {
        val byName = source.circuits.associateBy { it.name }
        require(selected.isNotEmpty() && selected.all(byName::containsKey))
        // Evolution's LogisimFileActions.addCircWithDeps: each selected circuit, in file order, after the
        // circuits it instantiates (depth first), so the merged toolbox lists dependencies first.
        val included = linkedSetOf<String>()
        fun include(name: String) {
            if (name in included) return
            included += name
            for (component in byName.getValue(name).components) {
                if (component.kind == ComponentKind.SUBCIRCUIT) {
                    val dependency = requireNotNull(component.circuitName)
                    if (dependency in byName) include(dependency)
                }
            }
            // Post-order: move the circuit behind everything it depends on.
            included -= name
            included += name
        }
        for (circuit in source.circuits) if (circuit.name in selected) include(circuit.name)
        val circuits = included.map(byName::getValue)
        val names = mergedKeys(destination.circuits.map { it.name }, circuits.map { it.name })
        val ids = mergedKeys(destination.circuits.map { it.id }, circuits.map { it.id })
        return destination.copy(circuits = destination.circuits + circuits.map { circuit ->
            circuit.copy(
                id = ids.getValue(circuit.id),
                name = names.getValue(circuit.name),
                components = circuit.components.map { component ->
                    if (component.kind == ComponentKind.SUBCIRCUIT) {
                        component.copy(circuitName = names.getValue(requireNotNull(component.circuitName)))
                    } else component
                },
                xmlAttributes = circuit.xmlAttributes - "logisim-mobile-library-id" - "logisim-mobile-library-name" +
                    ("logisim-mobile-source" to (circuit.xmlAttributes["logisim-mobile-source"] ?: source.sourceVersion)),
            )
        })
    }

    private fun mergedKeys(existing: List<String>, imported: List<String>): Map<String, String> {
        val collisions = existing.toSet()
        val reserved = (existing + imported).toMutableSet()
        return imported.associateWith { key ->
            if (key !in collisions) key else {
                var suffix = 2
                // `Name_2` keeps a name Evolution accepts (#321); `Name (2)` would be renamed when Evolution opens the file.
                var candidate = "${key}_$suffix"
                while (!reserved.add(candidate)) candidate = "${key}_${++suffix}"
                candidate
            }
        }
    }
}
