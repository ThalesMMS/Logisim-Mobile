package dev.logisim.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitFragment
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind

internal class CircuitClipboard {
    data class Origin(val editor: Any, val documentSession: Long)

    data class Entry(
        val fragment: CircuitFragment,
        val subcircuits: Map<String, CircuitDocument>,
        val origin: Origin,
    )

    var entry by mutableStateOf<Entry?>(null)
        private set

    fun copy(fragment: CircuitFragment, project: CircuitProject, origin: Origin) {
        if (fragment.isEmpty) {
            entry = null
            return
        }
        val definitions = project.circuits.associateBy { it.name }
        val subcircuits = mutableMapOf<String, CircuitDocument>()
        val pending = ArrayDeque(fragment.components.mapNotNull {
            if (it.kind == ComponentKind.SUBCIRCUIT) it.circuitName else null
        })
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (name in subcircuits) continue
            val definition = definitions.getValue(name)
            subcircuits[name] = definition
            for (component in definition.components) {
                if (component.kind == ComponentKind.SUBCIRCUIT) pending.addLast(requireNotNull(component.circuitName))
            }
        }
        entry = Entry(fragment, subcircuits, origin)
    }
}
