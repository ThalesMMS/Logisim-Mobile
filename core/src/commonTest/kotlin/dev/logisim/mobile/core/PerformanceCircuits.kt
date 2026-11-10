package dev.logisim.mobile.core

/** Deterministic circuits shared by performance tools and platform measurements. */
object PerformanceCircuits {
    data class Case(
        val project: CircuitProject,
        val drivenInputs: List<String>,
        val observedOutputs: List<String>,
        val highInputs: Map<String, LogicVector>,
    ) {
        fun expected(high: Boolean): LogicVector = LogicVector.of(8, if (high) 0x5auL else 0uL)
        fun inputs(high: Boolean): Map<String, LogicVector> {
            if (high) return highInputs
            val driven = drivenInputs.toSet()
            return highInputs.mapValues { (id, value) -> if (id in driven) LogicVector.low(8) else value }
        }

        fun rebound(imported: CircuitProject): Case {
            val original = project.mainCircuit.components.associateBy { it.id }
            val restored = imported.mainCircuit.components.associateBy { it.kind to it.position }
            fun newId(id: String): String {
                val component = original.getValue(id)
                return restored.getValue(component.kind to component.position).id
            }
            return Case(imported, drivenInputs.map(::newId), observedOutputs.map(::newId),
                highInputs.mapKeys { (id, _) -> newId(id) })
        }
    }

    fun independentGates(count: Int): Case {
        require(count in 1..16_384)
        val components = mutableListOf<CircuitComponent>()
        val wires = mutableListOf<WireSegment>()
        val sources = mutableListOf<String>()
        val sinks = mutableListOf<String>()
        val inputs = mutableMapOf<String, LogicVector>()
        repeat(count) { index ->
            val origin = GridPoint(100 + index % 32 * 200, 100 + index / 32 * 100)
            val gate = CircuitComponent("gate_$index", ComponentKind.AND, origin,
                attributes = mapOf("width" to "8", "inputs" to "2", "size" to "50"))
            val pins = gate.inputPorts()
            val a = CircuitComponent("a_$index", ComponentKind.INPUT, pins[0], attributes = mapOf("width" to "8"))
            val b = CircuitComponent("b_$index", ComponentKind.INPUT, pins[1], attributes = mapOf("width" to "8"))
            val out = CircuitComponent("out_$index", ComponentKind.OUTPUT,
                GridPoint(origin.x + 40, origin.y), attributes = mapOf("width" to "8"))
            components += listOf(a, b, gate, out)
            wires += WireSegment(origin, out.position)
            sources += a.id
            sinks += out.id
            inputs[a.id] = LogicVector.high(8)
            inputs[b.id] = LogicVector.of(8, 0x5auL)
        }
        return Case(CircuitProject(listOf(CircuitDocument("gates_$count", components, wires))), sources, sinks, inputs)
    }

    fun hierarchicalGates(count: Int): Case {
        require(count in 1..16_384)
        val child = CircuitDocument("and8", listOf(
            CircuitComponent("a", ComponentKind.INPUT, GridPoint(0, 0), attributes = mapOf("width" to "8")),
            CircuitComponent("b", ComponentKind.INPUT, GridPoint(0, 40), attributes = mapOf("width" to "8")),
            CircuitComponent("gate", ComponentKind.AND, GridPoint(100, 20), attributes = mapOf("width" to "8")),
            CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(140, 20), attributes = mapOf("width" to "8")),
        ), listOf(
            WireSegment(GridPoint(0, 0), GridPoint(50, 0)),
            WireSegment(GridPoint(0, 40), GridPoint(50, 40)),
            WireSegment(GridPoint(100, 20), GridPoint(140, 20)),
        ), attributes = mapOf("appearance" to "custom"), extraXml = listOf("""
            <appear><rect x="60" y="80" width="80" height="40"/>
              <circ-anchor facing="east" x="100" y="100"/>
              <circ-port pin="0,0" x="60" y="90"/>
              <circ-port pin="0,40" x="60" y="110"/>
              <circ-port pin="140,20" x="140" y="100"/>
            </appear>
        """.trimIndent()))
        val components = mutableListOf<CircuitComponent>()
        val wires = mutableListOf<WireSegment>()
        val sources = mutableListOf<String>()
        val sinks = mutableListOf<String>()
        val inputs = mutableMapOf<String, LogicVector>()
        repeat(count) { index ->
            val origin = GridPoint(100 + index % 32 * 200, 100 + index / 32 * 100)
            val a = CircuitComponent("a_$index", ComponentKind.INPUT,
                GridPoint(origin.x - 40, origin.y - 10), attributes = mapOf("width" to "8"))
            val b = CircuitComponent("b_$index", ComponentKind.INPUT,
                GridPoint(origin.x - 40, origin.y + 10), attributes = mapOf("width" to "8"))
            val nested = CircuitComponent("instance_$index", ComponentKind.SUBCIRCUIT, origin,
                circuitName = child.name, explicitPorts = listOf(
                    ComponentPort("a", GridPoint(-40, -10), PortDirection.INPUT, 8),
                    ComponentPort("b", GridPoint(-40, 10), PortDirection.INPUT, 8),
                    ComponentPort("out", GridPoint(40, 0), PortDirection.OUTPUT, 8),
                ))
            val out = CircuitComponent("out_$index", ComponentKind.OUTPUT,
                GridPoint(origin.x + 70, origin.y), attributes = mapOf("width" to "8"))
            components += listOf(a, b, nested, out)
            wires += WireSegment(GridPoint(origin.x + 40, origin.y), out.position)
            sources += a.id
            sinks += out.id
            inputs[a.id] = LogicVector.high(8)
            inputs[b.id] = LogicVector.of(8, 0x5auL)
        }
        val parent = CircuitDocument("hierarchy_$count", components, wires)
        return Case(CircuitProject(listOf(parent, child), parent.name), sources, sinks, inputs)
    }
}
