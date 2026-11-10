package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentKind

data class CircuitNavigationTarget(
    val circuitId: String,
    val circuitName: String,
    val instancePath: List<String> = emptyList(),
    val componentId: String? = null,
)

enum class ProjectNavigationKind { CIRCUIT, INSTANCE, RECURSIVE_REFERENCE }

data class ProjectNavigationRow(
    val key: String,
    val label: String,
    val target: CircuitNavigationTarget,
    val depth: Int,
    val kind: ProjectNavigationKind,
    val isMain: Boolean = false,
    val breadcrumb: List<String> = emptyList(),
)

object ProjectNavigation {
    fun rows(project: CircuitProject): List<ProjectNavigationRow> = buildList {
        val childrenByCircuit = mutableMapOf<String, List<Instance>>()
        fun children(circuit: CircuitDocument): List<Instance> = childrenByCircuit.getOrPut(circuit.id) {
            namedChildren(project, circuit)
        }

        fun instances(root: CircuitDocument) {
            val path = mutableListOf<String>()
            val labels = mutableListOf(root.name)
            val ancestry = mutableSetOf(root.id)
            val stack = ArrayDeque<TraversalFrame>()
            stack.addLast(TraversalFrame(root, children(root).iterator()))
            while (stack.isNotEmpty()) {
                val frame = stack.last()
                if (!frame.children.hasNext()) {
                    stack.removeLast()
                    ancestry.remove(frame.circuit.id)
                    if (path.isNotEmpty()) {
                        path.removeAt(path.lastIndex)
                        labels.removeAt(labels.lastIndex)
                    }
                    continue
                }
                val instance = frame.children.next()
                val child = instance.circuit
                path += instance.componentId
                val childPath = path.toList()
                val label = instance.label
                labels += label
                val recursive = child.id in ancestry
                add(ProjectNavigationRow(
                    key = "instance:${navigationPathKey(childPath)}",
                    label = label,
                    target = CircuitNavigationTarget(child.id, child.name, childPath),
                    depth = childPath.size,
                    kind = if (recursive) ProjectNavigationKind.RECURSIVE_REFERENCE else ProjectNavigationKind.INSTANCE,
                    breadcrumb = labels.toList(),
                ))
                if (recursive) {
                    path.removeAt(path.lastIndex)
                    labels.removeAt(labels.lastIndex)
                }
                else {
                    ancestry += child.id
                    stack.addLast(TraversalFrame(child, children(child).iterator()))
                }
            }
        }

        for (circuit in project.circuits) {
            val main = circuit.name == project.mainCircuitName
            add(ProjectNavigationRow(
                key = "circuit:${circuit.id}",
                label = circuit.name,
                target = CircuitNavigationTarget(circuit.id, circuit.name),
                depth = 0,
                kind = ProjectNavigationKind.CIRCUIT,
                isMain = main,
                breadcrumb = listOf(circuit.name),
            ))
            if (main) instances(circuit)
        }
    }

    internal fun instanceBreadcrumb(project: CircuitProject, path: List<String>): List<String>? {
        var circuit = project.mainCircuit
        val labels = mutableListOf(circuit.name)
        for (id in path) {
            val instance = namedChildren(project, circuit).firstOrNull { it.componentId == id } ?: return null
            labels += instance.label
            circuit = instance.circuit
        }
        return labels
    }

    private fun namedChildren(project: CircuitProject, circuit: CircuitDocument): List<Instance> {
        val instances = circuit.components.mapNotNull { component ->
            if (component.kind != ComponentKind.SUBCIRCUIT) return@mapNotNull null
            val child = project.circuit(component.circuitName ?: return@mapNotNull null) ?: return@mapNotNull null
            Instance(component.id, child, component.label.ifBlank { child.name })
        }
        val counts = instances.groupingBy(Instance::label).eachCount()
        val takenLabels = counts.keys.toMutableSet()
        val nextOrdinal = mutableMapOf<String, Int>()
        return instances.map { instance ->
            if (counts.getValue(instance.label) == 1) instance
            else {
                var ordinal = nextOrdinal[instance.label] ?: 1
                var label = "${instance.label} [$ordinal]"
                while (!takenLabels.add(label)) {
                    ordinal++
                    label = "${instance.label} [$ordinal]"
                }
                nextOrdinal[instance.label] = ordinal + 1
                instance.copy(label = label)
            }
        }
    }

    private data class Instance(val componentId: String, val circuit: CircuitDocument, val label: String)
    private class TraversalFrame(val circuit: CircuitDocument, val children: Iterator<Instance>)
}

internal fun navigationPathKey(parts: List<String>): String = parts.joinToString(":") { "${it.length}:$it" }
