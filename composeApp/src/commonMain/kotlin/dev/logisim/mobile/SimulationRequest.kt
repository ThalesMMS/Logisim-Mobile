package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentEvent
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.IoInputEvent
import dev.logisim.mobile.core.IoKinds

/** A runtime rejection must never request synchronous computation by the editor. */
enum class SimulationRequestResult { ACCEPTED, REJECTED }

internal fun ComponentEvent.releasesInput(kind: ComponentKind): Boolean = when (kind) {
    IoKinds.Button, IoKinds.Joystick -> this == IoInputEvent.Cancel || this is IoInputEvent.Press && !pressed
    IoKinds.MatrixKeypad -> this == IoInputEvent.Cancel || this is IoInputEvent.KeypadKey && !pressed
    else -> false
}

internal fun releasesInputs(project: CircuitProject, events: Map<String, List<ComponentEvent>>): Boolean {
    if (events.isEmpty()) return false
    return events.all { (key, batch) ->
        if (batch.isEmpty()) return@all false
        val path = key.split('/')
        var circuit = project.mainCircuit
        val visited = mutableSetOf(circuit.id)
        for (id in path.dropLast(1)) {
            val instance = circuit.components.firstOrNull { it.id == id && it.kind == ComponentKind.SUBCIRCUIT }
                ?: return@all false
            circuit = project.circuit(instance.circuitName ?: return@all false) ?: return@all false
            if (!visited.add(circuit.id)) return@all false
        }
        val component = circuit.components.firstOrNull { it.id == path.last() } ?: return@all false
        batch.all { it.releasesInput(component.kind) }
    }
}
