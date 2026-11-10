package dev.logisim.mobile.core

fun workspaceNavigationKey(circuitId: String, instancePath: List<String>): String =
    (listOf(circuitId) + instancePath).joinToString(":") { "${it.length}:$it" }

data class WorkspaceViewRestoration(val viewState: WorkspaceViewState, val recovered: Boolean = false)

/** Restore only routes, components, and wires still present in this project revision. */
fun WorkspaceViewState.compatibleWith(project: CircuitProject): WorkspaceViewState = restore(project, false).viewState

/** Stored XML must prove component identity before a saved route or selection can target it. */
fun WorkspaceViewState.restoreStored(project: CircuitProject): WorkspaceViewRestoration = restore(project, true)

private fun WorkspaceViewState.restore(project: CircuitProject, requirePersistentIds: Boolean): WorkspaceViewRestoration {
    var recovered = false
    val requestedCircuit = project.circuits.firstOrNull { it.id == activeCircuitId }
        ?: project.circuits.firstOrNull { viewportKeyFormat == WorkspaceViewportKeyFormat.LEGACY && it.name == activeCircuitId }
    val requested = requestedCircuit ?: project.mainCircuit
    val instance = resolveInstance(project, activeInstancePath)
    val path = activeInstancePath.takeIf { it.isNotEmpty() && instance?.id == requested.id &&
        (!requirePersistentIds || resolveInstance(project, it, true)?.id == requested.id) }.orEmpty()
    if (requirePersistentIds && activeInstancePath.isNotEmpty() && path.isEmpty()) recovered = true
    val active = if (path.isEmpty()) requested else instance ?: requested
    val selectionContextTrusted = !requirePersistentIds || requestedCircuit != null && (activeInstancePath.isEmpty() || path.isNotEmpty())
    val componentIds = if (selectionContextTrusted) active.components.filter {
        !requirePersistentIds || it.hasPersistentId()
    }.map { it.id }.toSet() else emptySet()
    val selected = selectedComponentIds.intersect(componentIds)
    if (requirePersistentIds && selected != selectedComponentIds) recovered = true
    val wires = if (selectionContextTrusted && (!requirePersistentIds || selected == selectedComponentIds))
        selectedWires.intersect(active.wires.toSet()) else emptySet()
    if (requirePersistentIds && wires != selectedWires) recovered = true
    val restoredViewports = viewports.mapNotNull { (key, viewport) ->
        val target = when (viewportKeyFormat) {
            WorkspaceViewportKeyFormat.LEGACY -> legacyTargets(key, project).singleOrNull()
            WorkspaceViewportKeyFormat.LENGTH_PREFIXED -> framedTarget(key, project)
        }
        if (target == null || requirePersistentIds && target.second.isNotEmpty() &&
            resolveInstance(project, target.second, true)?.id != target.first) {
            if (requirePersistentIds) recovered = true
            null
        } else workspaceNavigationKey(target.first, target.second) to viewport
    }.groupBy({ it.first }, { it.second }).mapNotNull { (key, values) ->
        val value = values.distinct().singleOrNull()
        if (requirePersistentIds && value == null) recovered = true
        value?.let { key to it }
    }.toMap()
    return WorkspaceViewRestoration(copy(activeCircuitId = active.id, activeInstancePath = path,
        selectedComponentIds = selected, selectedWires = wires,
        viewports = restoredViewports, viewportKeyFormat = WorkspaceViewportKeyFormat.LENGTH_PREFIXED), recovered)
}

private fun legacyTargets(key: String, project: CircuitProject): Set<Pair<String, List<String>>> = buildSet {
    for (circuit in project.circuits) {
        if (circuit.id == key || circuit.name == key) add(circuit.id to emptyList())
        val prefix = "${circuit.id}:"
        if (key.startsWith(prefix)) {
            val route = parsePath(key.removePrefix(prefix))
            if (route != null && (route.isEmpty() || resolveInstance(project, route)?.id == circuit.id)) {
                add(circuit.id to route)
            }
        }
    }
}

private fun framedTarget(key: String, project: CircuitProject): Pair<String, List<String>>? {
    val parts = parsePath(key, maximumParts = 257) ?: return null
    val circuit = project.circuits.firstOrNull { it.id == parts.firstOrNull() } ?: return null
    val route = parts.drop(1)
    return (circuit.id to route).takeIf { route.isEmpty() || resolveInstance(project, route)?.id == circuit.id }
}

private fun resolveInstance(project: CircuitProject, path: List<String>, requirePersistentIds: Boolean = false): CircuitDocument? {
    if (path.size > 256) return null
    var circuit = project.mainCircuit
    for (id in path) {
        val component = circuit.components.firstOrNull { it.id == id && it.kind == ComponentKind.SUBCIRCUIT } ?: return null
        if (requirePersistentIds && !component.hasPersistentId()) return null
        circuit = project.circuits.firstOrNull { it.name == component.circuitName } ?: return null
    }
    return circuit
}

private fun CircuitComponent.hasPersistentId(): Boolean = xmlAttributes[CircCodec.COMPONENT_ID_ATTRIBUTE] == id

private fun parsePath(encoded: String, maximumParts: Int = 256): List<String>? = runCatching {
    val result = mutableListOf<String>()
    var index = 0
    while (index < encoded.length) {
        val colon = encoded.indexOf(':', index)
        require(colon in index..index + 8)
        val length = encoded.substring(index, colon).toInt()
        require(length > 0 && length <= encoded.length - colon - 1 && result.size < maximumParts)
        val end = colon + 1 + length
        result += encoded.substring(colon + 1, end)
        index = if (end == encoded.length) end else {
            require(encoded[end] == ':' && end + 1 < encoded.length); end + 1
        }
    }
    result.toList()
}.getOrNull()
