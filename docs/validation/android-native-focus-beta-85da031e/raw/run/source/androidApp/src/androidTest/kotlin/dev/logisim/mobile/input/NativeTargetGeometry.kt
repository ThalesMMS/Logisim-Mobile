package dev.logisim.mobile.input

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.GridPoint

/** Verifies the exported model before an observed body point may be translated. */
internal object NativeTargetGeometry {
    fun verifiedDelta(before: CircuitProject, after: CircuitProject, expectedDelta: GridPoint,
                      expectedLabel: String? = null): GridPoint {
        val old = before.mainCircuit.components.associateBy { it.id }
        val current = after.mainCircuit.components.associateBy { it.id }
        check(old.size == before.mainCircuit.components.size && current.size == after.mainCircuit.components.size &&
            old.keys == current.keys) { "Target inference requires the same component identities" }
        val initial = old.getValue("mi-not")
        val target = current.getValue("mi-not")
        val delta = GridPoint(target.position.x - initial.position.x, target.position.y - initial.position.y)
        check(delta == expectedDelta) { "Unexpected XML target displacement: $delta" }
        if (expectedLabel != null) check(target.label == expectedLabel)
        fun normalized(component: CircuitComponent): CircuitComponent = component.copy(
            position = GridPoint(0, 0), xmlAttributes = component.xmlAttributes - "loc",
            label = if (expectedLabel == null) component.label else "",
            attributes = if (expectedLabel == null) component.attributes else component.attributes - "label",
            // Effective values are already in attributes; explicitness is source metadata.
            implicitAttributes = emptyMap(),
        )
        check(normalized(initial) == normalized(target) && old.all { (id, component) -> id == "mi-not" || current[id] == component }) {
            "Target inference requires unchanged geometry and unchanged anchor components"
        }
        return delta
    }
}
