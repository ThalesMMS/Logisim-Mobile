package dev.logisim.mobile.core

/** A probe transition after a complete batch of events at one propagation timestamp. */
data class TemporalProbeValue(val action: String, val time: Long, val probe: String, val bits: String)

/** Runs the same TSV stimuli used by the desktop propagation oracle. */
object CircuitTemporalTrace {
    fun run(project: CircuitProject, operations: String, singleEvent: Boolean = false): List<TemporalProbeValue> {
        data class Probe(val id: String, val point: GridPoint, val width: Int)
        val probes = mutableListOf<Probe>()
        val inputs = project.mainCircuit.components.filter { it.kind == ComponentKind.INPUT }
            .associateBy { it.attributes["label"].orEmpty() }
        val supplied = mutableMapOf<String, LogicVector>()
        val result = mutableListOf<TemporalProbeValue>()
        val simulator = CircuitSimulator()
        var state = SimulationState()
        var action = "initial"
        var origin = 0L
        var last: List<String>? = null
        fun observe(snapshot: SimulationState, time: Long) {
            require(probes.isNotEmpty()) { "Define a temporal probe before observing" }
            val values = probes.map { (snapshot.portVectors[it.point] ?: LogicVector.unknown(it.width)).display }
            if (values != last) {
                probes.zip(values).forEach { (probe, bits) -> result += TemporalProbeValue(action, time - origin, probe.id, bits) }
                last = values
            }
        }
        fun drain(emit: Boolean, limit: Int) {
            require(limit > 0) { "Temporal step limit must be positive" }
            val observer: ((SimulationState, Long) -> Unit)? = if (emit) ::observe else null
            if (!singleEvent) {
                state = simulator.settle(project, state, vectorInputs = supplied, observer = observer)
            } else {
                var batches = 0
                do {
                    check(batches++ < limit) { "Temporal fixture did not settle in $limit batches: $action" }
                    state = simulator.stepEvent(project, state, vectorInputs = supplied, observer = observer)
                    check(!state.oscillating) { "Temporal fixture oscillates in $action" }
                } while (state.pendingEvents.isNotEmpty())
            }
            check(!state.oscillating) { "Temporal fixture oscillates in $action" }
        }
        for ((index, line) in operations.lineSequence().withIndex()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val fields = line.split('\t')
            when (fields[0]) {
                "P" -> {
                    require(fields.size == 5) { "Invalid probe at line ${index + 1}" }
                    require(fields[1].isNotEmpty() && probes.none { it.id == fields[1] }) { "Duplicate or empty temporal probe" }
                    val width = fields[4].toInt()
                    require(width in 1..64) { "Temporal probe width must be in 1..64" }
                    probes += Probe(fields[1], GridPoint(fields[2].toInt(), fields[3].toInt()), width)
                }
                "A" -> { action = fields[1]; origin = state.propagationTime; last = null }
                "I" -> {
                    val component = inputs[fields[1]] ?: error("No input ${fields[1]}")
                    require(fields[2].length == component.width) { "Input ${fields[1]} needs ${component.width} bits" }
                    supplied[component.id] = LogicVector.fromBits(fields[2].reversed().map { bit -> when (bit) {
                        '0' -> LogicValue.LOW; '1' -> LogicValue.HIGH; '?', 'x', 'X', 'u', 'U' -> LogicValue.UNKNOWN
                        'E', 'e' -> LogicValue.ERROR; else -> error("Invalid binary input ${fields[2]}")
                    } })
                }
                "K" -> error("Use Pin clock stimuli for the temporal oracle")
                "D", "Q" -> {
                    require(fields.size <= 2) { "Expected D/Q with an optional batch limit" }
                    drain(fields[0] == "D", fields.getOrNull(1)?.toInt() ?: 256)
                }
                "S" -> observe(state, state.propagationTime)
                else -> error("Unknown temporal operation at line ${index + 1}")
            }
        }
        return result
    }
}
