package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.SimulationState

/** The selected instance becomes the root of an independent simulation. */
internal class DebugInstanceSimulation private constructor(
    val path: String,
    val project: CircuitProject,
    var state: SimulationState,
    val initialDrives: Map<String, LogicVector>,
) {
    var drives = initialDrives

    companion object {
        fun from(
            project: CircuitProject,
            live: SimulationState,
            document: CircuitDocument,
            instancePath: List<String>,
        ): DebugInstanceSimulation? {
            val path = instancePath.joinToString("/")
            val instance = live.circuitStates[path] ?: return null
            val prefix = "$path/"
            val children = buildMap {
                for ((key, state) in live.circuitStates) {
                    if (key.startsWith(prefix)) {
                        val relativePath = key.removePrefix(prefix)
                        put(relativePath, state.copy(instancePath = relativePath))
                    }
                }
            }
            val drives = buildMap {
                for (pin in document.components) {
                    if (pin.kind == ComponentKind.INPUT) {
                        instance.vectorValues[pin.id]?.let { put(pin.id, it) }
                    }
                }
            }
            val pending = live.pendingEvents.mapNotNull { event ->
                when {
                    event.instancePath == path -> event.copy(instancePath = "")
                    event.instancePath.startsWith(prefix) -> event.copy(instancePath = event.instancePath.removePrefix(prefix))
                    else -> null
                }
            }
            val state = instance.copy(
                instancePath = "",
                circuitStates = children,
                pendingEvents = pending,
                nextWakeupMillis = (children.values.mapNotNull { it.nextWakeupMillis } +
                    listOfNotNull(instance.nextWakeupMillis)).minOrNull(),
            )
            return DebugInstanceSimulation(path, project.copy(mainCircuitName = document.name), state, drives)
        }
    }
}
