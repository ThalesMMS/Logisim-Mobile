package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentRegistry

internal object PlacementLabels {
    private val identifier = Regex("[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)*")

    fun isValid(label: String): Boolean = identifier.matches(label)

    fun unavailable(document: CircuitDocument, registry: ComponentRegistry, kind: ComponentKind): Set<String> {
        if (kind == ComponentKind.TUNNEL) return emptySet()
        return buildSet {
            if (kind == ComponentKind.INPUT || kind == ComponentKind.OUTPUT) add(document.name.lowercase())
            for (component in document.components) {
                if (component.kind != ComponentKind.TUNNEL && component.label.isNotEmpty()) add(component.label.lowercase())
                val name = component.circuitName ?: registry.definition(component.kind)?.fileName
                if (name != null) add(name.lowercase())
            }
        }
    }

    fun nextAvailable(start: String, unavailable: Set<String>): String? {
        var candidate = start
        while (candidate.lowercase() in unavailable) candidate = after(candidate) ?: return null
        return candidate
    }

    fun after(label: String): String? {
        val digits = label.takeLastWhile { it in '0'..'9' }
        if (digits.isEmpty()) return "${label}_1"
        val number = digits.toLongOrNull() ?: return null
        return if (number == Long.MAX_VALUE) null else label.dropLast(digits.length) + (number + 1)
    }
}
