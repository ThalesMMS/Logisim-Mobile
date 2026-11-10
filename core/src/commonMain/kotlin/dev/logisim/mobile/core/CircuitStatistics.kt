package dev.logisim.mobile.core

/** Counts describe the current drawing. A split segment changes the segment count, not its length. */
data class CircuitStatisticsSnapshot(
    val circuitId: String,
    val circuitName: String,
    val componentCount: Int,
    val componentsByType: Map<String, Int>,
    val wireSegmentCount: Int,
    val wireLength: Long,
    val terminalCount: Int,
    val terminalLocationCount: Int,
    val junctionCount: Int,
    val netCount: Int,
    val subcircuitReferenceCount: Int,
    val unresolvedComponentIds: Set<String> = emptySet(),
) {
    val hasIncompleteTopology: Boolean get() = unresolvedComponentIds.isNotEmpty()
}

data class ProjectStatisticsSnapshot(
    val circuits: Map<String, CircuitStatisticsSnapshot>,
    val definitionComponentCount: Long,
    val definitionWireSegmentCount: Long,
    val definitionWireLength: Long,
    val expansion: CircuitExpansionResult,
) {
    val circuitCount: Int get() = circuits.size
}

/** An instance count includes the main circuit; expanded components exclude subcircuit symbols. */
data class CircuitExpansionStatistics(
    val instanceCount: Long,
    val instancesByCircuit: Map<String, Long>,
    val componentCount: Long,
    val componentsByType: Map<String, Long>,
    val wireSegmentCount: Long,
    val wireLength: Long,
)

sealed interface CircuitExpansionResult {
    data class Complete(val statistics: CircuitExpansionStatistics) : CircuitExpansionResult
    data class Invalid(val problems: List<CircuitExpansionProblem>) : CircuitExpansionResult
}

enum class CircuitExpansionProblemKind { RECURSION, MISSING_CIRCUIT, COUNT_OVERFLOW }

data class CircuitExpansionProblem(
    val kind: CircuitExpansionProblemKind,
    val circuitId: String,
    val instancePath: List<String>,
    val componentId: String?,
    val targetCircuitName: String?,
) {
    val id: String get() = buildString {
        append("statistics:").append(kind.name).append(':')
        appendPart(circuitId)
        instancePath.forEach { appendPart(it) }
        componentId?.let { appendPart(it) }
    }
}

/** No snapshot is cached, so edits, undo, and changes in the selected main circuit are visible. */
object CircuitStatistics {
    fun measure(
        document: CircuitDocument,
        registry: ComponentRegistry = ComponentCatalog.registry,
        netCount: Int? = null,
    ): CircuitStatisticsSnapshot {
        require(netCount == null || netCount >= 0) { "A contagem de redes não pode ser negativa." }
        val unresolved = mutableSetOf<String>()
        val terminals = document.components.flatMap { component ->
            try {
                component.layout(registry).ports
            } catch (_: IllegalArgumentException) {
                unresolved += component.id
                emptyList()
            }
        }
        val knownTopologyDocument = if (unresolved.isEmpty()) document else document.copy(
            components = document.components.filterNot { it.id in unresolved },
        )
        val terminalLocations = terminals.map { it.position }.toSet()
        val segments = document.wires.distinctBy { canonicalEndpoints(it) }
        val candidateJunctions = terminalLocations + segments.flatMap { listOf(it.from, it.to) }
        val junctions = candidateJunctions.count { point ->
            segments.sumOf { segment ->
                when {
                    point == segment.from || point == segment.to -> 1
                    segment.contains(point) -> 2
                    else -> 0
                }
            } >= 3
        }
        return CircuitStatisticsSnapshot(
            circuitId = document.id,
            circuitName = document.name,
            componentCount = document.components.size,
            componentsByType = document.components.groupingBy { it.kind.name }.eachCount().sortedByKey(),
            wireSegmentCount = document.wires.size,
            wireLength = document.wires.sumOf(::wireLength),
            terminalCount = terminals.size,
            terminalLocationCount = terminalLocations.size,
            junctionCount = junctions,
            netCount = netCount ?: CircuitTopology.elaborate(knownTopologyDocument, registry).nets.size,
            subcircuitReferenceCount = document.components.count { it.kind == ComponentKind.SUBCIRCUIT },
            unresolvedComponentIds = unresolved.toSet(),
        )
    }

    fun measure(
        project: CircuitProject,
        registry: ComponentRegistry = ComponentCatalog.registry,
    ): ProjectStatisticsSnapshot {
        val circuits = project.circuits.associate { it.id to measure(it, registry) }.sortedByKey()
        return ProjectStatisticsSnapshot(
            circuits = circuits,
            definitionComponentCount = circuits.values.sumOf { it.componentCount.toLong() },
            definitionWireSegmentCount = circuits.values.sumOf { it.wireSegmentCount.toLong() },
            definitionWireLength = circuits.values.sumOf { it.wireLength },
            expansion = Expansion(project, circuits).measure(),
        )
    }

    /** Reachable definition IDs and their occurrence counts, without measuring wire topology. */
    internal fun countCircuitInstances(
        project: CircuitProject,
        opaqueCircuits: Set<String>,
        checkCancellation: () -> Unit,
    ): Map<String, Long> =
        when (val result = Expansion(project, opaqueCircuits = opaqueCircuits, checkCancellation = checkCancellation).measure()) {
            is CircuitExpansionResult.Complete -> result.statistics.instancesByCircuit
            is CircuitExpansionResult.Invalid -> throw IllegalArgumentException(
                result.problems.joinToString("; ") { problem ->
                    when (problem.kind) {
                        CircuitExpansionProblemKind.RECURSION -> "Recursive circuit '${problem.targetCircuitName}'."
                        CircuitExpansionProblemKind.MISSING_CIRCUIT -> "Missing circuit '${problem.targetCircuitName}'."
                        CircuitExpansionProblemKind.COUNT_OVERFLOW -> "Expanded circuit counts exceed the supported limit."
                    }
                },
            )
        }

    private class Expansion(
        project: CircuitProject,
        private val snapshots: Map<String, CircuitStatisticsSnapshot> = emptyMap(),
        private val opaqueCircuits: Set<String> = emptySet(),
        private val checkCancellation: () -> Unit = {},
    ) {
        private val definitions = project.circuits.associateBy { it.name }
        private val mainName = project.mainCircuitName
        private val complete = mutableMapOf<String, CircuitExpansionStatistics>()

        fun measure(): CircuitExpansionResult {
            val main = definitions[mainName] ?: return CircuitExpansionResult.Invalid(listOf(
                CircuitExpansionProblem(CircuitExpansionProblemKind.MISSING_CIRCUIT, mainName,
                    emptyList(), null, mainName),
            ))
            return expand(main, emptyList(), emptySet())
        }

        private fun expand(
            document: CircuitDocument,
            path: List<String>,
            ancestors: Set<String>,
        ): CircuitExpansionResult {
            checkCancellation()
            complete[document.name]?.let { return CircuitExpansionResult.Complete(it) }
            val (references, primitive) = document.components.partition {
                it.kind == ComponentKind.SUBCIRCUIT && it.circuitName !in opaqueCircuits
            }
            val instances = mutableMapOf(document.id to 1L)
            val types = primitive.groupingBy { it.kind.name }.eachCount()
                .mapValues { it.value.toLong() }.toMutableMap()
            var componentCount = primitive.size.toLong()
            val own = snapshots[document.id]
            var segmentCount = own?.wireSegmentCount?.toLong() ?: 0L
            var length = own?.wireLength ?: 0L
            val problems = mutableListOf<CircuitExpansionProblem>()
            for (reference in references) {
                val target = reference.circuitName?.let(definitions::get)
                val nextPath = path + reference.id
                if (target == null) {
                    problems += CircuitExpansionProblem(CircuitExpansionProblemKind.MISSING_CIRCUIT,
                        document.id, nextPath, reference.id, reference.circuitName)
                    continue
                }
                if (target.name == document.name || target.name in ancestors) {
                    problems += CircuitExpansionProblem(CircuitExpansionProblemKind.RECURSION,
                        document.id, nextPath, reference.id, target.name)
                    continue
                }
                when (val nested = expand(target, nextPath, ancestors + document.name)) {
                    is CircuitExpansionResult.Invalid -> problems += nested.problems
                    is CircuitExpansionResult.Complete -> {
                        val stats = nested.statistics
                        val nextComponents = add(componentCount, stats.componentCount)
                        val nextSegments = add(segmentCount, stats.wireSegmentCount)
                        val nextLength = add(length, stats.wireLength)
                        if (nextComponents == null || nextSegments == null || nextLength == null ||
                            !mergeCounts(instances, stats.instancesByCircuit) ||
                            !mergeCounts(types, stats.componentsByType)) {
                            problems += CircuitExpansionProblem(CircuitExpansionProblemKind.COUNT_OVERFLOW,
                                document.id, nextPath, reference.id, target.name)
                            continue
                        }
                        componentCount = nextComponents
                        segmentCount = nextSegments
                        length = nextLength
                    }
                }
            }
            if (problems.isNotEmpty()) return CircuitExpansionResult.Invalid(problems.distinctBy { it.id })
            var instanceCount = 0L
            for (count in instances.values) {
                instanceCount = add(instanceCount, count) ?: return CircuitExpansionResult.Invalid(listOf(
                    CircuitExpansionProblem(CircuitExpansionProblemKind.COUNT_OVERFLOW,
                        document.id, path, null, document.name),
                ))
            }
            val result = CircuitExpansionStatistics(instanceCount, instances.sortedByKey(), componentCount,
                types.sortedByKey(), segmentCount, length)
            complete[document.name] = result
            return CircuitExpansionResult.Complete(result)
        }

        private fun mergeCounts(target: MutableMap<String, Long>, values: Map<String, Long>): Boolean {
            val merged = values.mapValues { (key, value) -> add(target[key] ?: 0L, value) }
            if (merged.values.any { it == null }) return false
            merged.forEach { (key, value) -> target[key] = value!! }
            return true
        }

        private fun add(first: Long, second: Long): Long? =
            if (first > Long.MAX_VALUE - second) null else first + second
    }
}

private fun wireLength(segment: WireSegment): Long =
    kotlin.math.abs(segment.from.x.toLong() - segment.to.x.toLong()) +
        kotlin.math.abs(segment.from.y.toLong() - segment.to.y.toLong())

private fun canonicalEndpoints(segment: WireSegment): Pair<GridPoint, GridPoint> =
    if (segment.from.x < segment.to.x || segment.from.x == segment.to.x && segment.from.y <= segment.to.y)
        segment.from to segment.to else segment.to to segment.from

private fun <T> Map<String, T>.sortedByKey(): Map<String, T> = entries.sortedBy { it.key }.associate { it.key to it.value }

private fun StringBuilder.appendPart(value: String) {
    append(value.length).append(':').append(value).append(':')
}
