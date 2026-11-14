package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitProject

/**
 * A token for each circuit id of the open project. It survives renames and edits, and changes when the circuit is
 * deleted and another one is created under the same id ("circuit-N" ids are reused), so state kept per circuit, such
 * as unsaved FPGA bindings, never passes from a deleted circuit to its successor.
 */
internal class CircuitLifetimes(initial: CircuitProject) {
    private var next = 1L
    private val tokens = mutableMapOf<String, Long>()

    init { observe(initial) }

    /** Called with every project the editor shows; ids that disappeared lose their token. */
    fun observe(project: CircuitProject) {
        val ids = project.circuits.mapTo(HashSet()) { it.id }
        tokens.keys.retainAll(ids)
        for (id in ids) tokens.getOrPut(id) { next++ }
    }

    /** The token of [id], or 0 for a circuit the project does not contain. */
    operator fun get(id: String): Long = tokens[id] ?: 0L
}
