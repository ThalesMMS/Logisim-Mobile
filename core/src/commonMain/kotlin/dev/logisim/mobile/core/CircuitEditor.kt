package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

/** A fragment contains complete components and the wires selected with them. */
data class CircuitFragment(
    val components: List<CircuitComponent> = emptyList(),
    val wires: List<WireSegment> = emptyList(),
) {
    val isEmpty: Boolean get() = components.isEmpty() && wires.isEmpty()
}

/** Document history excludes input stimulation and transient simulation state. */
class CircuitEditor(initial: CircuitDocument = CircuitDocument(), private val registry: ComponentRegistry = ComponentCatalog.registry) {
    var document: CircuitDocument = initial
        private set
    private val past = ArrayDeque<CircuitDocument>()
    private val future = ArrayDeque<CircuitDocument>()
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun add(kind: ComponentKind, position: GridPoint, defaults: CreationDefaults? = null): String {
        val id = nextId(document.components.map { it.id }.toSet())
        return addPlacedComponent(if (defaults == null) registry.create(kind, id, position) else registry.createNew(kind, id, position, defaults))
    }

    fun add(template: CircuitComponent, position: GridPoint): String {
        val id = nextId(document.components.map { it.id }.toSet())
        return addPlacedComponent(template.copy(id = id, position = position))
    }

    /** [component] as placed beside [existing], with the attributes its definition gives a new component. */
    private fun placed(component: CircuitComponent, existing: List<CircuitComponent>): CircuitComponent =
        registry.definition(component.kind)?.placedAttributes?.let { component.copy(attributes = it(component, existing)) } ?: component

    private fun addPlacedComponent(template: CircuitComponent): String {
        val component = placed(template, document.components)
        val exclusive = exclusivePlacementPoints(component).toSet()
        requireLocalized(exclusive.isEmpty() || document.components.none { existing ->
            exclusivePlacementPoints(existing).any { it in exclusive }
        }) { message(Messages.ErrorPlacementTerminalConflict) }
        update(document.copy(components = document.components + component))
        return component.id
    }

    private fun exclusivePlacementPoints(component: CircuitComponent): List<GridPoint> {
        val ttlModel = registry.definition(component.kind)?.takeIf { it.library == "#TTL" }?.fileName
        val ttlOutputs = ttlModel?.let { model -> TtlCatalog.specs.firstOrNull { it.model == model }?.outputPins }.orEmpty()
        // The 74299 reads and drives its bus pads, which the desktop reserves as output terminals.
        return component.layout(registry).outputs.filter { port ->
            port.direction == PortDirection.OUTPUT ||
                port.name.removePrefix("pin").toIntOrNull()?.let { it in ttlOutputs } == true
        }.map { it.position }
    }

    fun addComponents(templates: List<CircuitComponent>): Set<String> {
        if (templates.isEmpty()) return emptySet()
        val used = document.components.mapTo(mutableSetOf()) { it.id }
        var next = 1
        val copies = mutableListOf<CircuitComponent>()
        for (template in templates) {
            while ("c$next" in used) next++
            copies += placed(template.copy(id = "c${next++}"), document.components + copies).also { it.layout(registry) }
        }
        update(document.copy(components = document.components + copies))
        return copies.mapTo(mutableSetOf()) { it.id }
    }

    fun move(id: String, to: GridPoint) {
        val component = document.components.firstOrNull { it.id == id } ?: return
        move(setOf(id), GridPoint(to.x - component.position.x, to.y - component.position.y))
    }

    /** Move the group and reconnect its boundary wires in one history entry. */
    fun move(ids: Set<String>, delta: GridPoint) = move(ids, emptySet(), delta)

    fun move(ids: Set<String>, selectedWires: Set<WireSegment>, delta: GridPoint, keepConnections: Boolean = true) {
        if ((ids.isEmpty() && selectedWires.isEmpty()) || delta == GridPoint(0, 0)) return
        val components = document.components.map {
            if (it.id in ids) it.copy(position = it.position.plus(delta)) else it
        }
        if (!keepConnections) {
            val wires = if (selectedWires.isEmpty()) document.wires else normalize(document.wires.map { wire ->
                if (selectedWires.any { it.sameSegment(wire) })
                    WireSegment(wire.from.plus(delta), wire.to.plus(delta))
                else wire
            })
            update(document.copy(components = components, wires = wires))
            return
        }
        val selected = document.components.filter { it.id in ids }
        val selectedPorts = selected.flatMap { it.inputPorts(registry) + it.outputPorts(registry) }.toSet()
        val fixedPorts = document.components.filter { it.id !in ids }
            .flatMap { it.inputPorts(registry) + it.outputPorts(registry) }.toSet()
        val atoms = normalize(document.wires, allPorts(document))
        val internal = internalWires(ids, atoms)
        val explicitAtoms = atoms.filter { atom -> selectedWires.any { it.contains(atom.from) && it.contains(atom.to) } }.toSet()
        val explicitPoints = explicitAtoms.flatMap { listOf(it.from, it.to) }.toSet()
        val movingPoints = selectedPorts + internal.flatMap { listOf(it.from, it.to) } + explicitPoints
        preserveConnections { verticalFirst ->
            val movedWires = atoms.flatMap { wire ->
                fun moves(point: GridPoint) = point in movingPoints && (point !in fixedPorts || wire in explicitAtoms)
                route(if (moves(wire.from)) wire.from.plus(delta) else wire.from,
                    if (moves(wire.to)) wire.to.plus(delta) else wire.to, verticalFirst)
            } + (movingPoints intersect fixedPorts).flatMap { route(it, it.plus(delta), verticalFirst) }
            document.copy(
                components = components,
                wires = normalize(movedWires),
            )
        }
    }

    fun updateComponent(id: String, transform: (CircuitComponent) -> CircuitComponent) {
        val before = document.components.firstOrNull { it.id == id } ?: return
        val after = transform(before)
        if (after.kind != ComponentKind.SUBCIRCUIT) registry.attributes(after)
        requireLocalized(after.id == before.id) { message(Messages.ErrorComponentIdChange) }
        if (before == after) return
        val oldTerminals = before.layout(registry).ports
        val newTerminals = after.layout(registry).ports
        val oldPorts = oldTerminals.map { it.position }
        val newPorts = newTerminals.map { it.position }
        val newByName = newTerminals.associateBy { it.name }
        val removedConnected = oldTerminals.any { terminal ->
            terminal.name !in newByName && document.wires.any { it.contains(terminal.position) }
        }
        requireLocalized(!removedConnected) { LocalizedText("core_editor_connected_terminal", fallback = "A propriedade removeria um terminal conectado.") }
        val fixedPorts = document.components.filter { it.id != id }
            .flatMap { it.inputPorts(registry) + it.outputPorts(registry) }.toSet()
        val replacements = oldTerminals.mapNotNull { old -> newByName[old.name]?.let { old.position to it.position } }.toMap()
        val atoms = normalize(document.wires, allPorts(document))
        val candidate = { verticalFirst: Boolean ->
            val wires = atoms.flatMap { wire ->
                route(if (wire.from in fixedPorts) wire.from else replacements[wire.from] ?: wire.from,
                    if (wire.to in fixedPorts) wire.to else replacements[wire.to] ?: wire.to, verticalFirst)
            } + oldPorts.filter { it in fixedPorts }.flatMap { route(it, replacements[it] ?: it, verticalFirst) }
            document.copy(
                components = document.components.map { if (it.id == id) after else it },
                wires = normalize(wires),
            )
        }
        if (oldPorts.size != newPorts.size) update(candidate(false)) else if (!preserveConnections(candidate)) {
            val moving = replacements.filter { (old, new) -> old != new && old !in fixedPorts }
            val chains = boundaryChains(atoms, moving.keys, allPorts(document).toSet())
            val rerouted = chains.flatMap { it.zipWithNext().map { (from, to) -> WireSegment(from, to) } }.toSet()
            preserveConnections { reverse ->
                val wires = atoms.filterNot { atom -> rerouted.any { it.sameSegment(atom) } } + chains.flatMap { points ->
                    val old = points.first()
                    val moved = replacements.getValue(old)
                    val end = points.last()
                    val fixed = if (end in fixedPorts) end else replacements[end] ?: end
                    if (end == old || moved == fixed) {
                        val delta = if (end == moved) GridPoint(0, 0) else GridPoint(moved.x - old.x, moved.y - old.y)
                        points.zipWithNext().map { (from, to) -> WireSegment(from.plus(delta), to.plus(delta)) }
                    } else {
                        val vertical = points[0].x == points[1].x
                        val order = moving.entries.sortedBy { if (vertical) it.value.x else it.value.y }
                        val index = order.indexOfFirst { it.key == old }
                        val lane = if (reverse) order.size - index else index + 1
                        routeStaggered(moved, fixed, vertical, lane)
                    }
                } + oldPorts.filter { it in fixedPorts }.flatMap { route(it, replacements[it] ?: it, reverse) }
                document.copy(
                    components = document.components.map { if (it.id == id) after else it },
                    wires = normalize(wires),
                )
            }
        }
    }

    fun updateComponents(components: Map<String, CircuitComponent>) {
        requireLocalized(components.all { (id, component) -> id == component.id && document.components.any { it.id == id } }) {
            LocalizedText("core_editor_group_ids", fallback = "A edição em grupo deve preservar as identificações existentes.")
        }
        val candidate = CircuitEditor(document, registry)
        for ((id, component) in components) {
            candidate.updateComponent(id) { component }
            requireLocalized(candidate.document.components.first { it.id == id } == component) {
                LocalizedText("core_editor_group_connections", fallback = "A edição em grupo não pode preservar as conexões.")
            }
        }
        update(candidate.document)
    }

    fun remove(id: String) = remove(setOf(id))

    fun remove(ids: Set<String>, wires: Set<WireSegment> = emptySet()) {
        if (ids.isEmpty() && wires.isEmpty()) return
        update(document.copy(
            components = document.components.filterNot { it.id in ids },
            wires = document.wires.filterNot { wire -> wires.any { it.sameSegment(wire) } },
        ))
    }

    /** Boundary branches are excluded unless their segments were explicitly selected. */
    fun copy(ids: Set<String>, wires: Set<WireSegment> = emptySet()): CircuitFragment = CircuitFragment(
        components = document.components.filter { it.id in ids },
        wires = normalize(internalWires(ids, normalize(document.wires, allPorts(document))) +
            document.wires.filter { wire -> wires.any { it.sameSegment(wire) } }),
    )

    fun paste(fragment: CircuitFragment, offset: GridPoint): Set<String> {
        if (fragment.isEmpty) return emptySet()
        val usedIds = document.components.map { it.id }.toMutableSet()
        val copies = fragment.components.map { component ->
            val id = nextId(usedIds)
            usedIds += id
            component.copy(id = id, position = component.position.plus(offset))
        }
        val bySourceId = fragment.components.zip(copies).associate { (source, copy) -> source.id to copy }
        val components = fragment.components.map { source ->
            val copy = bySourceId.getValue(source.id)
            val context = ComponentCopyContext(source, copy, fragment.components, bySourceId, document.components)
            copy.copy(attributes = registry.definition(source.kind)?.copyAttributes?.invoke(context) ?: copy.attributes)
        }
        val wires = fragment.wires.map { WireSegment(it.from.plus(offset), it.to.plus(offset)) }
        update(document.copy(components = document.components + components,
            wires = normalize(document.wires + wires)))
        return components.map { it.id }.toSet()
    }

    fun connect(from: GridPoint, to: GridPoint, verticalFirst: Boolean = false) {
        if (from == to) return
        update(document.copy(wires = normalize(document.wires + route(from, to, verticalFirst), allPorts(document))))
    }

    /** Remove only the part of a wire retraced by the wiring tool. */
    fun backtrackWire(from: GridPoint, to: GridPoint): Boolean {
        val removed = document.wireBacktrack(from, to, registry) ?: return false
        val wires = document.wires.flatMap { wire ->
            if (wire.contains(from) && wire.contains(to)) {
                normalize(listOf(wire), listOf(from, to)).filterNot { it.sameSegment(removed) }
            } else listOf(wire)
        }
        update(document.copy(wires = wires))
        return true
    }

    fun splitWire(point: GridPoint) {
        update(document.copy(wires = normalize(document.wires, listOf(point))))
    }

    fun removeWire(segment: WireSegment) = remove(emptySet(), setOf(segment))

    /** Move a segment while retaining connections at its two former endpoints. */
    fun moveWire(segment: WireSegment, delta: GridPoint) {
        val original = document.wires.firstOrNull { it.sameSegment(segment) } ?: return
        if (delta == GridPoint(0, 0)) return
        val moved = WireSegment(original.from.plus(delta), original.to.plus(delta))
        preserveConnections { verticalFirst ->
            val wires = document.wires.filterNot { it.sameSegment(original) } + moved +
                route(original.from, moved.from, verticalFirst) + route(original.to, moved.to, verticalFirst)
            document.copy(wires = normalize(wires, allPorts(document)))
        }
    }

    /** Re-route every branch meeting at a free wire point without moving component pins. */
    fun moveWirePoint(from: GridPoint, to: GridPoint) {
        if (from == to || from in allPorts(document)) return
        if (document.wires.none { it.from == from || it.to == from }) return
        val atoms = normalize(document.wires, allPorts(document))
        preserveConnections { verticalFirst ->
            val moved = atoms.flatMap { wire ->
                route(if (wire.from == from) to else wire.from,
                    if (wire.to == from) to else wire.to, verticalFirst)
            }
            document.copy(wires = normalize(moved, allPorts(document) + to))
        }
    }

    fun undo(): Boolean {
        if (past.isEmpty()) return false
        future.addLast(document)
        document = past.removeLast()
        return true
    }

    fun redo(): Boolean {
        if (future.isEmpty()) return false
        past.addLast(document)
        document = future.removeLast()
        return true
    }

    fun replace(document: CircuitDocument) { update(document) }

    private fun update(next: CircuitDocument) {
        if (next == document) return
        past.addLast(document)
        if (past.size > 100) past.removeFirst()
        future.clear()
        document = next
    }

    private fun nextId(usedIds: Set<String>): String {
        var number = 1
        while ("c$number" in usedIds) number++
        return "c$number"
    }

    private fun internalWires(ids: Set<String>, wires: List<WireSegment>): List<WireSegment> {
        val terminals = document.components.filter { it.id in ids }
            .flatMap { it.inputPorts(registry) + it.outputPorts(registry) }.toSet()
        if (terminals.size < 2) return emptyList()
        val remaining = wires.toMutableSet()
        var removed: Boolean
        do {
            val degree = remaining.flatMap { listOf(it.from, it.to) }.groupingBy { it }.eachCount()
            val leaves = degree.filter { (point, count) -> count == 1 && point !in terminals }.keys
            removed = remaining.removeAll { it.from in leaves || it.to in leaves }
        } while (removed)
        // Disconnected loops and wires with only one selected terminal do not belong
        // to the fragment, even when they survived leaf pruning.
        val result = mutableListOf<WireSegment>()
        while (remaining.isNotEmpty()) {
            val group = mutableSetOf(remaining.first())
            val points = mutableSetOf(group.first().from, group.first().to)
            var grew: Boolean
            do {
                grew = false
                for (wire in remaining) if (wire !in group && (wire.from in points || wire.to in points)) {
                    group += wire
                    points += wire.from
                    points += wire.to
                    grew = true
                }
            } while (grew)
            if (points.count { it in terminals } >= 2) result += group
            remaining.removeAll(group)
        }
        return result
    }

    private fun allPorts(document: CircuitDocument): List<GridPoint> = document.components
        .flatMap { it.inputPorts(registry) + it.outputPorts(registry) }

    /** Follow wires through free bends, stopping at terminals, junctions, or loose ends. */
    private fun boundaryChains(
        wires: List<WireSegment>,
        moving: Set<GridPoint>,
        terminals: Set<GridPoint>,
    ): List<List<GridPoint>> {
        val at = wires.flatMap { listOf(it.from to it, it.to to it) }.groupBy({ it.first }, { it.second })
        val remaining = wires.toMutableSet()
        val chains = mutableListOf<List<GridPoint>>()
        for (start in moving) for (first in at[start].orEmpty()) {
            if (first !in remaining) continue
            val points = mutableListOf(start)
            var wire = first
            while (remaining.remove(wire)) {
                val next = if (wire.from == points.last()) wire.to else wire.from
                points += next
                if (next in terminals || at.getValue(next).size != 2) break
                wire = at.getValue(next).single { it != wire }
            }
            chains += points
        }
        return chains
    }

    private fun portConnections(document: CircuitDocument): Set<Set<Pair<String, Int>>> {
        val ports = document.components.flatMap { component ->
            (component.inputPorts(registry) + component.outputPorts(registry)).mapIndexed { index, point ->
                (component.id to index) to point
            }
        }
        val wires = normalize(document.wires, ports.map { it.second })
        val points = (wires.flatMap { listOf(it.from, it.to) } + ports.map { it.second }).distinct()
        val parents = points.associateWith { it }.toMutableMap()
        fun root(point: GridPoint): GridPoint {
            var current = point
            while (parents.getValue(current) != current) current = parents.getValue(current)
            return current
        }
        wires.forEach { parents[root(it.from)] = root(it.to) }
        return ports.groupBy { root(it.second) }.values.map { net -> net.map { it.first }.toSet() }.toSet()
    }

    private fun normalize(wires: List<WireSegment>, extraPoints: List<GridPoint> = emptyList()): List<WireSegment> {
        val junctions = (wires.flatMap { listOf(it.from, it.to) } + extraPoints).distinct()
        val normalized = mutableListOf<WireSegment>()
        for (wire in wires) {
            val points = junctions.filter { wire.contains(it) }.sortedBy {
                if (wire.from.x == wire.to.x) it.y else it.x
            }
            for ((from, to) in points.zipWithNext()) {
                if (from != to) {
                    val segment = WireSegment(from, to)
                    if (normalized.none { it.sameSegment(segment) }) normalized += segment
                }
            }
        }
        return normalized
    }

    private fun preserveConnections(candidate: (Boolean) -> CircuitDocument): Boolean {
        val originalConnections = portConnections(document)
        for (verticalFirst in listOf(false, true)) {
            val next = candidate(verticalFirst)
            if (originalConnections == portConnections(next)) {
                update(next)
                return true
            }
        }
        return false
    }

    private fun routeStaggered(from: GridPoint, to: GridPoint, vertical: Boolean, lane: Int): List<WireSegment> {
        if (from.x == to.x || from.y == to.y) return route(from, to)
        val step = 10 * lane
        val channel = if (vertical) {
            from.y + if (to.y > from.y) step else -step
        } else {
            from.x + if (to.x > from.x) step else -step
        }
        val first = if (vertical) GridPoint(from.x, channel) else GridPoint(channel, from.y)
        val second = if (vertical) GridPoint(to.x, channel) else GridPoint(channel, to.y)
        return route(from, first) + route(first, second) + route(second, to)
    }

    private fun route(from: GridPoint, to: GridPoint, verticalFirst: Boolean = false): List<WireSegment> =
        orthogonalWireRoute(from, to, verticalFirst)
}

/** Return the path shared by the wire preview and the editor. */
fun orthogonalWireRoute(from: GridPoint, to: GridPoint, verticalFirst: Boolean): List<WireSegment> {
    if (from == to) return emptyList()
    if (from.x == to.x || from.y == to.y) return listOf(WireSegment(from, to))
    val elbow = if (verticalFirst) GridPoint(from.x, to.y) else GridPoint(to.x, from.y)
    return listOf(WireSegment(from, elbow), WireSegment(elbow, to))
}

private fun GridPoint.plus(delta: GridPoint) = GridPoint(x + delta.x, y + delta.y)
private fun WireSegment.sameSegment(other: WireSegment): Boolean =
    (from == other.from && to == other.to) || (from == other.to && to == other.from)
