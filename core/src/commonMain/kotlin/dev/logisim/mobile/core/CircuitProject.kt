package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

data class CircuitProject(
    val circuits: List<CircuitDocument>,
    val mainCircuitName: String = circuits.firstOrNull()?.name ?: "main",
    val sourceVersion: String = "3.8.0",
    val metadata: Map<String, String> = emptyMap(),
    val extraXml: List<String> = emptyList(),
    val libraries: Map<String, String> = emptyMap(),
    val mainXml: String? = null,
) {
    init {
        requireLocalized(circuits.isNotEmpty()) { message(Messages.CoreProjectCircuitRequired) }
        requireLocalized(circuits.map { it.name }.distinct().size == circuits.size) { message(Messages.ErrorCircuitNamesUnique) }
        requireLocalized(circuits.map { it.id }.distinct().size == circuits.size) { message(Messages.ErrorCircuitIdsUnique) }
        requireLocalized(circuits.any { it.name == mainCircuitName }) { message(Messages.ErrorMainCircuitMissing, "name" to mainCircuitName) }
        val names = circuits.map { it.name }.toSet()
        requireLocalized(circuits.flatMap { it.components }.all {
            it.kind != ComponentKind.SUBCIRCUIT || it.circuitName in names
        }) { message(Messages.ErrorSubcircuitMissing) }
    }

    val mainCircuit: CircuitDocument get() = circuits.first { it.name == mainCircuitName }
    val isOriginalLogisim: Boolean get() = CircFormatProfile.parse(sourceVersion).originalLogisim
    fun circuit(name: String): CircuitDocument? = circuits.firstOrNull { it.name == name }
    fun withCircuit(document: CircuitDocument): CircuitProject {
        requireLocalized(circuits.any { it.id == document.id }) { message(Messages.ErrorCircuitMissing, "name" to document.id) }
        val original = circuits.first { it.id == document.id }
        val replaced = if (original.name != document.name) renameCircuit(original.name, document.name) else this
        return replaced.copy(circuits = replaced.circuits.map { if (it.id == document.id) document else it })
    }
    fun addCircuit(document: CircuitDocument): CircuitProject {
        requireLocalized(sharedVhdlDefinitions().none { it.name == document.name }) { message("shared_vhdl_name_conflict") }
        return copy(circuits = circuits + document)
    }
    fun renameCircuit(name: String, newName: String): CircuitProject {
        requireLocalized(newName.isNotBlank() && circuits.none { it.name == newName && it.name != name }) { message(Messages.ErrorCircuitNameInvalid) }
        requireLocalized(sharedVhdlDefinitions().none { it.name == newName }) { message("shared_vhdl_name_conflict") }
        requireLocalized(circuits.any { it.name == name }) { message(Messages.ErrorCircuitMissing, "name" to name) }
        return copy(
            mainCircuitName = if (mainCircuitName == name) newName else mainCircuitName,
            circuits = circuits.map { document -> document.copy(
                name = if (document.name == name) newName else document.name,
                components = document.components.map { component ->
                    if (component.kind == ComponentKind.SUBCIRCUIT && component.circuitName == name)
                        component.copy(circuitName = newName) else component
                },
            ) },
        )
    }
    fun removeCircuit(name: String): CircuitProject {
        requireLocalized(name != mainCircuitName) { message(Messages.ErrorMainCircuitRemoval) }
        requireLocalized(circuits.none { document -> document.components.any { it.circuitName == name } }) {
            message(Messages.ErrorCircuitInUse)
        }
        requireLocalized(circuits.any { it.name == name }) { message(Messages.ErrorCircuitMissing, "name" to name) }
        return copy(circuits = circuits.filterNot { it.name == name })
    }
    fun reorderCircuits(names: List<String>): CircuitProject {
        requireLocalized(names.size == circuits.size && names.toSet() == circuits.map { it.name }.toSet()) { message(Messages.ErrorCircuitOrder) }
        return copy(circuits = names.map { name -> circuits.first { it.name == name } })
    }
    fun subcircuitPortBindings(component: CircuitComponent): List<Pair<GridPoint, CircuitComponent>> {
        val child = circuit(component.circuitName ?: return emptyList()) ?: return emptyList()
        return component.explicitPorts.mapNotNull { port ->
            child.components.firstOrNull { it.id == port.name }?.let { component.relativePoint(port.position) to it }
        }
    }
}
