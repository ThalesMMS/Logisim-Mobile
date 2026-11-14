package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message

/** Tunnels share an electrical connection by label, using the desktop's String.trim() rules. */
fun CircuitDocument.namedTunnelGroups(): Collection<List<CircuitComponent>> {
    val groups = components.asSequence()
        .filter { it.kind == ComponentKind.TUNNEL }
        .groupByTo(mutableMapOf()) { tunnel -> tunnel.label.trim { it <= ' ' } }
    groups.remove("")
    return groups.values
}

/** Electrical connectivity. Crossings join only where a terminal or wire endpoint exists. */
class CircuitNetworks(private val document: CircuitDocument, private val registry: ComponentRegistry = ComponentCatalog.registry) {
    private val terminals = document.components.flatMap { component ->
        component.layout(registry).ports.map { Terminal(it.position, it.width, component.id) }
    }
    private val points = (terminals.map { it.point } +
        document.wires.flatMap { listOf(it.from, it.to) }).distinct()
    private val pointIndex = points.withIndex().associate { it.value to it.index }
    private val physical = DisjointSet(points.size)
    private val rootAtPoint: Map<GridPoint, Int>
    private val widths: Map<Int, Int>
    private val offsets: Map<Int, Int>
    private val bits: DisjointSet
    private val invalidRoots: Set<Int>
    private val widthlessRoots: Set<Int>
    private val connected: Set<GridPoint>
    val notices: List<LocalizedText>
    /** The components whose terminals meet on the net each notice is about (C67). */
    val noticeComponents: Map<LocalizedText, List<String>>
    val diagnostics: List<String> get() = notices.map { it.resolve(AppLanguage.PORTUGUESE) }

    init {
        for (wire in document.wires) {
            val start = pointIndex.getValue(wire.from)
            for ((index, point) in points.withIndex()) {
                if (wire.contains(point)) physical.join(start, index)
            }
        }
        for (group in document.namedTunnelGroups()) {
            val first = pointIndex.getValue(group.first().position)
            group.drop(1).forEach { physical.join(first, pointIndex.getValue(it.position)) }
        }
        rootAtPoint = points.associateWith { physical.root(pointIndex.getValue(it)) }
        val terminalWidths = terminals.groupBy { rootAtPoint.getValue(it.point) }
        val errors = mutableListOf<LocalizedText>()
        val errorComponents = mutableMapOf<LocalizedText, List<String>>()
        invalidRoots = terminalWidths.filter { (_, terminals) ->
            terminals.map { it.width }.filter { it > 0 }.distinct().size > 1
        }.keys
        for (root in invalidRoots) {
            val terminals = terminalWidths.getValue(root)
            val error = message(Messages.ErrorNetworkWidth,
                // Evolution's Location.toString: "(x,y)".
                "position" to terminals.first().point.let { "(${it.x},${it.y})" },
                "widths" to terminals.map { it.width }.filter { it > 0 }.distinct().sorted().joinToString(" / "))
            errors += error
            errorComponents[error] = terminals.map { it.componentId }.distinct()
        }
        widthlessRoots = rootAtPoint.values.filterTo(mutableSetOf()) { root -> terminalWidths[root].orEmpty().none { it.width > 0 } }
        widths = rootAtPoint.values.distinct().associateWith { root ->
            (terminalWidths[root]?.maxOfOrNull { it.width } ?: 1).coerceAtLeast(1)
        }
        var nextOffset = 0
        offsets = widths.mapValues { (_, width) -> nextOffset.also { nextOffset += width } }
        bits = DisjointSet(nextOffset)
        for (splitter in document.components.filter { it.kind == ComponentKind.SPLITTER }) {
            val commonRoot = rootAtPoint[splitter.position] ?: continue
            val branches = splitter.splitterPorts().drop(1)
            val branchBits = IntArray(branches.size)
            for ((bit, branch) in splitter.splitterBitEnds().withIndex()) {
                if (branch == null || branch !in branches.indices) continue
                val localBit = branchBits[branch]++
                val branchRoot = rootAtPoint[branches[branch]] ?: continue
                if (bit < widths.getValue(commonRoot) && localBit < widths.getValue(branchRoot)) {
                    bits.join(offsets.getValue(commonRoot) + bit, offsets.getValue(branchRoot) + localBit)
                }
            }
        }
        val rootPointCounts = rootAtPoint.values.groupingBy { it }.eachCount()
        val terminalCounts = terminals.groupingBy { it.point }.eachCount()
        connected = points.filter { point ->
            rootPointCounts.getValue(rootAtPoint.getValue(point)) > 1 ||
                terminalCounts.getOrElse(point) { 0 } > 1
        }.toSet()
        notices = errors
        noticeComponents = errorComponents
    }

    fun isConnected(point: GridPoint): Boolean = point in connected

    fun widthAt(point: GridPoint): Int = rootAtPoint[point]?.let { widths.getValue(it) } ?: 1

    fun bitNetwork(point: GridPoint, bit: Int): Int? {
        val root = rootAtPoint[point] ?: return null
        if (bit !in 0 until widths.getValue(root)) return null
        return bits.root(offsets.getValue(root) + bit)
    }

    fun bitNetworks(): Map<GridPoint, List<Int>> = rootAtPoint.mapValues { (point, _) ->
        List(widthAt(point)) { bitNetwork(point, it)!! }
    }

    /**
     * Positions on nets that no component terminal gives a width, which Evolution's CircuitWires holds as
     * Value.NIL (a bundle of width 0) and draws with NIL_COLOR.
     */
    fun widthlessPoints(): Set<GridPoint> = points.filterTo(mutableSetOf()) { rootAtPoint.getValue(it) in widthlessRoots }

    /** Positions on nets without a source, including sources through splitters and tunnels. */
    fun undrivenPoints(): Set<GridPoint> {
        val drivenBits = mutableSetOf<Int>()
        for (component in document.components) {
            val layout = component.layout(registry)
            val pulls = registry.definition(component.kind)?.pullDrivers?.invoke(component, registry.attributes(component)).orEmpty()
            for (port in layout.outputs + layout.inputs.filter { it.name in pulls }) {
                repeat(widthAt(port.position)) { bit ->
                    bitNetwork(port.position, bit)?.let(drivenBits::add)
                }
            }
        }
        return points.filterTo(mutableSetOf()) { point ->
            (0 until widthAt(point)).none { bitNetwork(point, it) in drivenBits }
        }
    }

    /** Each point may have multiple drivers already resolved by the caller. UNKNOWN floats. */
    fun resolve(drivers: Map<GridPoint, LogicVector>): Map<GridPoint, LogicVector> =
        resolve(drivers, rejectWeak = false)

    internal fun resolve(drivers: Map<GridPoint, LogicVector>, rejectWeak: Boolean): Map<GridPoint, LogicVector> {
        val resolved = arrayOfNulls<LogicValue>(bits.size)
        fun drive(node: Int, value: LogicValue) {
            val root = bits.root(node)
            val before = resolved[root] ?: LogicValue.UNKNOWN
            resolved[root] = when {
                before == LogicValue.ERROR || value == LogicValue.ERROR -> LogicValue.ERROR
                value == LogicValue.UNKNOWN -> before
                before == LogicValue.UNKNOWN || before == value -> value
                else -> LogicValue.ERROR
            }
        }
        for (root in invalidRoots) {
            repeat(widths.getValue(root)) { drive(offsets.getValue(root) + it, LogicValue.ERROR) }
        }
        for ((point, value) in drivers) {
            val root = rootAtPoint[point] ?: continue
            val width = widths.getValue(root)
            val offset = offsets.getValue(root)
            repeat(width) { bit ->
                drive(offset + bit, if (value.width != width) LogicValue.ERROR else value.bit(bit))
            }
        }
        val weak = arrayOfNulls<LogicValue>(bits.size)
        for (component in document.components) {
            val definition = registry.definition(component.kind) ?: continue
            val pulls = definition.pullDrivers?.invoke(component, registry.attributes(component)) ?: continue
            val ports = component.layout(registry).ports.associateBy { it.name }
            for ((name, value) in pulls) {
                val point = ports[name]?.position ?: throw IllegalArgumentException("Pull em terminal inexistente: $name.")
                val root = rootAtPoint[point] ?: continue
                val width = widths.getValue(root)
                repeat(width) { bit ->
                    val node = bits.root(offsets.getValue(root) + bit)
                    val next = if (value.width == 1) value.bit(0) else if (value.width == width) value.bit(bit) else LogicValue.ERROR
                    val old = weak[node] ?: LogicValue.UNKNOWN
                    weak[node] = when {
                        old == LogicValue.ERROR || next == LogicValue.ERROR -> LogicValue.ERROR
                        old == LogicValue.UNKNOWN -> next
                        next == LogicValue.UNKNOWN || old == next -> old
                        else -> LogicValue.ERROR
                    }
                }
            }
        }
        for (node in resolved.indices) if (resolved[node] == null || resolved[node] == LogicValue.UNKNOWN) {
            resolved[node] = weak[node]?.let { if (rejectWeak && it != LogicValue.UNKNOWN) LogicValue.ERROR else it }
        }
        val valuesByRoot = widths.mapValues { (root, width) ->
            val offset = offsets.getValue(root)
            LogicVector.fromBits(List(width) { resolved[bits.root(offset + it)] ?: LogicValue.UNKNOWN })
        }
        return rootAtPoint.mapValues { (_, root) -> valuesByRoot.getValue(root) }
    }

    private data class Terminal(val point: GridPoint, val width: Int, val componentId: String)

    private class DisjointSet(val size: Int) {
        private val parent = IntArray(size) { it }
        private val rank = IntArray(size)

        fun root(index: Int): Int {
            var current = index
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }

        fun join(a: Int, b: Int) {
            val first = root(a)
            val second = root(b)
            if (first == second) return
            when {
                rank[first] < rank[second] -> parent[first] = second
                rank[first] > rank[second] -> parent[second] = first
                else -> {
                    parent[second] = first
                    rank[first]++
                }
            }
        }
    }
}
