package dev.logisim.mobile.core

/** Attributes feedback to leaf components after joining the bit nets at instance boundaries. */
internal fun hierarchicalDiagnosticFeedback(
    project: CircuitProject,
    registry: ComponentRegistry,
    maxInstances: Int,
    topologies: Map<String, CircuitTopology> = emptyMap(),
): List<DiagnosticTarget> {
    require(maxInstances > 0) { "O limite de instâncias precisa ser positivo." }
    val topologyCache = topologies.toMutableMap()
    fun hasLayout(component: CircuitComponent): Boolean = try {
        component.layout(registry)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
    fun topology(document: CircuitDocument): CircuitTopology = topologyCache.getOrPut(document.id) {
        val valid = document.components.filter(::hasLayout)
        CircuitTopology.elaborate(document.copy(components = valid), registry)
    }
    val definitions = project.circuits.associateBy { it.name }
    val instances = mutableListOf<FeedbackInstance>()
    val boundaries = mutableListOf<FeedbackBoundary>()
    val pending = ArrayDeque<PendingFeedbackInstance>()
    pending.addLast(PendingFeedbackInstance(project.mainCircuit, emptyList(), setOf(project.mainCircuit.id)))
    while (pending.isNotEmpty()) {
        // A partial graph cannot establish that no other instance participates in a cycle.
        if (instances.size == maxInstances) return emptyList()
        val next = pending.removeLast()
        val index = instances.size
        instances += FeedbackInstance(next.document, next.path, topology(next.document))
        if (next.parent != null && next.symbol != null) boundaries += FeedbackBoundary(next.parent, index, next.symbol)
        for (symbol in next.document.components.asReversed()) {
            if (symbol.kind != ComponentKind.SUBCIRCUIT || !hasLayout(symbol)) continue
            val child = symbol.circuitName?.let(definitions::get) ?: continue
            if (child.id in next.ancestors) continue
            pending.addLast(PendingFeedbackInstance(child, next.path + symbol.id, next.ancestors + child.id,
                index, symbol))
        }
    }

    var bitCount = 0
    for (instance in instances) {
        instance.bits = instance.topology.bitNets.associate { it.id to bitCount++ }
    }
    val joined = FeedbackDisjointSet(bitCount)
    for (boundary in boundaries) {
        val parent = instances[boundary.parent]
        val child = instances[boundary.child]
        for ((outside, pin) in project.subcircuitPortBindings(boundary.symbol)) {
            if (pin.kind != ComponentKind.INPUT && pin.kind != ComponentKind.OUTPUT) continue
            val outsideWidth = parent.topology.netAt(outside)?.width ?: continue
            val insideWidth = child.topology.netAt(pin.position)?.width ?: continue
            repeat(minOf(outsideWidth, insideWidth)) { bit ->
                val outer = parent.topology.bitAt(outside, bit) ?: return@repeat
                val inner = child.topology.bitAt(pin.position, bit) ?: return@repeat
                joined.join(parent.bits.getValue(outer.id), child.bits.getValue(inner.id))
            }
        }
    }

    val graph = FeedbackGraph()
    val components = mutableMapOf<FeedbackComponent, Map<Int?, Int>>()
    val enabledComponents = mutableSetOf<FeedbackComponent>()
    for ((instanceIndex, instance) in instances.withIndex()) {
        val members = instance.topology.bitNets.flatMap { it.terminals }
            .groupBy { it.terminal.reference.componentId }
        for (component in instance.document.components) {
            if (component.kind == ComponentKind.SUBCIRCUIT || registry.definition(component.kind)?.isCombinational != true) continue
            val terminals = members[component.id].orEmpty()
            val directions = terminals.map { it.terminal.direction }.toSet()
            if (PortDirection.INPUT !in directions || PortDirection.OUTPUT !in directions) continue
            val reference = FeedbackComponent(instanceIndex, component.id)
            val bitwise = component.kind in feedbackBitwiseKinds && (component.kind !in feedbackControlledKinds ||
                terminals.any { it.terminal.reference.terminalName == "enable" })
            if (bitwise && component.kind in feedbackControlledKinds) enabledComponents += reference
            val bits: List<Int?> = if (bitwise) terminals.filter { it.terminal.direction == PortDirection.OUTPUT }
                .map { it.bit }.distinct() else listOf(null)
            components[reference] = bits.associateWith { bit -> graph.addNode(reference.copy(bit = bit)) }
        }
    }
    val netNodes = mutableMapOf<Int, Int>()
    for ((instanceIndex, instance) in instances.withIndex()) {
        for (bitNet in instance.topology.bitNets) {
            val root = joined.root(instance.bits.getValue(bitNet.id))
            for (member in bitNet.terminals) {
                val reference = FeedbackComponent(instanceIndex, member.terminal.reference.componentId)
                val references = components[reference] ?: continue
                val nodes = when {
                    null in references -> references.values
                    reference in enabledComponents && member.terminal.reference.terminalName == "enable" -> references.values
                    else -> listOfNotNull(references[member.bit])
                }
                when (member.terminal.direction) {
                    PortDirection.OUTPUT -> for (component in nodes) graph.connect(component, netNodes.getOrPut(root) { graph.addNode(null) })
                    PortDirection.INPUT -> for (component in nodes) graph.connect(netNodes.getOrPut(root) { graph.addNode(null) }, component)
                    PortDirection.INOUT -> Unit
                }
            }
        }
    }

    val results = mutableListOf<DiagnosticTarget>()
    for (cycle in graph.cycles()) {
        val involved = cycle.mapNotNull { graph.components[it] }.groupBy { it.instance }
        if (involved.isEmpty()) continue
        val cycleNets = netNodes.filterValues { it in cycle }.keys
        for ((instanceIndex, references) in involved) {
            val instance = instances[instanceIndex]
            val componentIds = references.map { it.componentId }.toSet()
            val localBits = instance.topology.bitNets.filter {
                joined.root(instance.bits.getValue(it.id)) in cycleNets
            }
            val anchors = localBits.flatMap { bitNet -> bitNet.points.map { it.position } }.toSet()
            val terminals = localBits.flatMap { it.terminals }.filter {
                it.terminal.reference.componentId in componentIds && it.terminal.direction != PortDirection.INOUT
            }.map { it.terminal.reference }.toSet()
            results += DiagnosticTarget(instance.document.id, instance.path, componentIds, terminals,
                anchors + instance.document.components.filter { it.id in componentIds }.map { it.position },
                anchors.flatMap { instance.topology.netAt(it)?.wires.orEmpty() }.toSet())
        }
    }
    return results.groupBy { it.circuitId to it.instancePath }.values.map { targets ->
        targets.first().copy(componentIds = targets.flatMap { it.componentIds }.toSet(),
            terminals = targets.flatMap { it.terminals }.toSet(), anchors = targets.flatMap { it.anchors }.toSet(),
            wires = targets.flatMap { it.wires }.toSet())
    }.sortedWith(compareBy<DiagnosticTarget>({ it.circuitId },
        { it.instancePath.joinToString("") { part -> identityPart(part) } },
        { it.componentIds.sorted().joinToString("") { part -> identityPart(part) } }))
}

private data class FeedbackInstance(
    val document: CircuitDocument,
    val path: List<String>,
    val topology: CircuitTopology,
    var bits: Map<String, Int> = emptyMap(),
)

private data class PendingFeedbackInstance(
    val document: CircuitDocument,
    val path: List<String>,
    val ancestors: Set<String>,
    val parent: Int? = null,
    val symbol: CircuitComponent? = null,
)

private data class FeedbackBoundary(val parent: Int, val child: Int, val symbol: CircuitComponent)
private data class FeedbackComponent(val instance: Int, val componentId: String, val bit: Int? = null)

private val feedbackControlledKinds = setOf(ComponentKind.CONTROLLED_BUFFER, ComponentKind.CONTROLLED_INVERTER)
private val feedbackBitwiseKinds = setOf(ComponentKind.BUFFER, ComponentKind.NOT, ComponentKind.AND,
    ComponentKind.OR, ComponentKind.XOR, ComponentKind.NAND, ComponentKind.NOR, ComponentKind.XNOR) + feedbackControlledKinds

private class FeedbackDisjointSet(size: Int) {
    private val parents = IntArray(size) { it }
    private val ranks = IntArray(size)

    fun root(node: Int): Int {
        var current = node
        while (parents[current] != current) {
            parents[current] = parents[parents[current]]
            current = parents[current]
        }
        return current
    }

    fun join(first: Int, second: Int) {
        val a = root(first)
        val b = root(second)
        if (a == b) return
        when {
            ranks[a] < ranks[b] -> parents[a] = b
            ranks[a] > ranks[b] -> parents[b] = a
            else -> { parents[b] = a; ranks[a]++ }
        }
    }
}

/** Net vertices keep high-fanout wiring linear instead of expanding every driver/input pair. */
private class FeedbackGraph {
    val components = mutableListOf<FeedbackComponent?>()
    private val edges = mutableListOf<MutableSet<Int>>()
    private val reversed = mutableListOf<MutableSet<Int>>()

    fun addNode(component: FeedbackComponent?): Int = components.size.also {
        components += component
        edges += mutableSetOf<Int>()
        reversed += mutableSetOf<Int>()
    }

    fun connect(from: Int, to: Int) {
        edges[from] += to
        reversed[to] += from
    }

    fun cycles(): List<Set<Int>> {
        val seen = BooleanArray(components.size)
        val order = mutableListOf<Int>()
        for (origin in components.indices) {
            if (seen[origin]) continue
            val pending = ArrayDeque<Pair<Int, Boolean>>()
            pending.addLast(origin to false)
            while (pending.isNotEmpty()) {
                val (node, finished) = pending.removeLast()
                if (finished) order += node
                else if (!seen[node]) {
                    seen[node] = true
                    pending.addLast(node to true)
                    for (next in edges[node]) if (!seen[next]) pending.addLast(next to false)
                }
            }
        }
        seen.fill(false)
        val cycles = mutableListOf<Set<Int>>()
        for (origin in order.asReversed()) {
            if (seen[origin]) continue
            val group = mutableSetOf<Int>()
            val pending = ArrayDeque<Int>()
            pending.addLast(origin)
            while (pending.isNotEmpty()) {
                val node = pending.removeLast()
                if (seen[node]) continue
                seen[node] = true
                group += node
                for (next in reversed[node]) if (!seen[next]) pending.addLast(next)
            }
            if (group.size > 1 || origin in edges[origin]) cycles += group
        }
        return cycles
    }
}
