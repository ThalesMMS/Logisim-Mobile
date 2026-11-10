package dev.logisim.mobile.core

/** The component and named terminal, independent of their current circuit coordinates. */
data class DiagnosticTerminalRef(val componentId: String, val terminalName: String) {
    internal val key: String get() = identityPart(componentId) + identityPart(terminalName)
}

data class DiagnosticTerminal(
    val reference: DiagnosticTerminalRef,
    val position: GridPoint,
    val direction: PortDirection,
    val declaredWidth: Int,
    val connected: Boolean,
)

data class DiagnosticBitTerminal(val terminal: DiagnosticTerminal, val bit: Int)
data class DiagnosticBitPoint(val position: GridPoint, val bit: Int)

data class CircuitNet(
    val id: String,
    val width: Int,
    val points: List<GridPoint>,
    val terminals: List<DiagnosticTerminal>,
    val wires: List<WireSegment>,
) {
    val declaredWidths: Set<Int> get() = terminals.map { it.declaredWidth }.filter { it > 0 }.toSet()
}

/** Splitters can connect one bit while the surrounding bus segments remain distinct nets. */
data class CircuitBitNet(
    val id: String,
    val points: List<DiagnosticBitPoint>,
    val terminals: List<DiagnosticBitTerminal>,
)

/** A read-only view of the same bit connectivity used by simulation. */
class CircuitTopology private constructor(
    val circuitId: String,
    val nets: List<CircuitNet>,
    val bitNets: List<CircuitBitNet>,
) {
    private val netByPoint = nets.flatMap { net -> net.points.map { it to net } }.toMap()
    private val bitByPoint = bitNets.flatMap { net -> net.points.map { it to net } }.toMap()

    fun netAt(point: GridPoint): CircuitNet? = netByPoint[point]
    fun bitAt(point: GridPoint, bit: Int): CircuitBitNet? = bitByPoint[DiagnosticBitPoint(point, bit)]

    companion object {
        fun elaborate(
            document: CircuitDocument,
            registry: ComponentRegistry = ComponentCatalog.registry,
        ): CircuitTopology {
            val networks = CircuitNetworks(document, registry)
            val pointBits = networks.bitNetworks()
            val terminals = document.components.flatMap { component ->
                component.layout(registry).ports.map { port ->
                    DiagnosticTerminal(DiagnosticTerminalRef(component.id, port.name), port.position,
                        port.direction, port.width, networks.isConnected(port.position))
                }
            }
            val terminalsAt = terminals.groupBy { it.position }
            val prefix = identityPart(document.id)
            val nets = pointBits.entries.groupBy { it.value }.values.map { entries ->
                val points = entries.map { it.key }.sortedWith(pointOrder)
                val ports = points.flatMap { terminalsAt[it].orEmpty() }.sortedBy { it.reference.key }
                val anchor = ports.firstOrNull()?.reference?.key ?: pointKey(points.first())
                CircuitNet("net:$prefix$anchor", entries.first().value.size, points, ports,
                    document.wires.filter { wire ->
                        pointBits[wire.from] == entries.first().value && pointBits[wire.to] == entries.first().value
                    })
            }.sortedBy { it.id }
            val bitNets = pointBits.flatMap { (point, bits) ->
                bits.mapIndexed { bit, root -> root to DiagnosticBitPoint(point, bit) }
            }.groupBy({ it.first }, { it.second }).values.map { members ->
                val points = members.sortedWith(compareBy<DiagnosticBitPoint>({ it.position.x }, { it.position.y }, { it.bit }))
                val ports = points.flatMap { member -> terminalsAt[member.position].orEmpty()
                    .filter { it.declaredWidth == 0 || member.bit < it.declaredWidth }
                    .map { DiagnosticBitTerminal(it, member.bit) } }
                    .sortedWith(compareBy({ it.terminal.reference.key }, { it.bit }))
                val anchor = ports.firstOrNull()?.let { it.terminal.reference.key + ":${it.bit}" }
                    ?: (pointKey(points.first().position) + ":${points.first().bit}")
                CircuitBitNet("bit:$prefix$anchor", points, ports)
            }.sortedBy { it.id }
            return CircuitTopology(document.id, nets, bitNets)
        }
    }
}

internal val pointOrder: Comparator<GridPoint> = compareBy({ it.x }, { it.y })
internal fun identityPart(value: String): String = "${value.length}:$value"
internal fun pointKey(point: GridPoint): String = "${point.x},${point.y}"
