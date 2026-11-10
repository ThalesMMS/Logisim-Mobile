package dev.logisim.mobile.core

import java.io.File

/**
 * Writes one temporal oracle fixture for each catalog component with separate input and output terminals.
 * Each fixture changes every input three times per action, draining the queue between changes, so the
 * recorded times also expose events that keep the queue busy without changing a value. With a desktop
 * attribute option table, it also writes one variant for each listed value of each attribute.
 */
object TemporalKindSweep {
    private val skipped = setOf(ComponentKind.INPUT, ComponentKind.OUTPUT, ComponentKind.SUBCIRCUIT,
        ComponentKind.TUNNEL, ComponentKind.SPLITTER)
    private val cosmetic = setOf("facing", "label", "labelloc", "labelfont", "labelcolor", "labelvisible")

    @JvmStatic
    fun main(arguments: Array<String>) {
        val flags = arguments.filter { it.startsWith("--") }.toSet()
        val pairLimit = arguments.indexOf("--pairs").takeIf { it >= 0 }?.let { arguments[it + 1].toInt() } ?: 0
        val positional = arguments.filterIndexed { index, value ->
            !value.startsWith("--") && (index == 0 || arguments[index - 1] != "--pairs") }
        require(positional.size in 1..2) {
            "Usage: TemporalKindSweep output-directory [desktop-attribute-options.tsv] [--negative] [--pairs N]"
        }
        // Negative fixtures also drive unknown and error bits.
        val negative = "--negative" in flags
        val output = File(positional[0])
        // library, file name -> attribute -> desktop standard values
        val options = positional.getOrNull(1)?.let { path -> File(path).readLines().filter { it.isNotBlank() }.map { it.split('\t') }
            .groupBy({ it[0] to it[1] }, { it[2] to it[3].split('|') }) }.orEmpty()
        val rejected = mutableListOf<String>()
        require(output.isDirectory || output.mkdirs()) { "Cannot create ${output.path}" }
        val registry = ComponentCatalog.registry
        val random = java.util.Random(304)
        val written = mutableListOf<String>()
        for (definition in registry.definitions) {
            val kind = definition.kind
            if (kind in skipped) continue
            val defaults = definition.schema.defaults + definition.defaultAttributes
            fun suffix(name: String, value: String) = "__${name}_${value.replace(Regex("[^A-Za-z0-9]"), "_")}"
            val singles = options[definition.library to definition.fileName].orEmpty()
                .filter { (name, _) -> name !in cosmetic }
                .flatMap { (name, values) -> values.filter { it != defaults[name] }.map { value -> name to value } }
            // With --pairs, sample combinations of two different attributes instead of single changes.
            val pairs = singles.flatMapIndexed { index, first -> singles.drop(index + 1)
                .filter { it.first != first.first }.map { first to it } }
                .shuffled(java.util.Random(kind.name.hashCode().toLong())).take(pairLimit)
            val variants = listOf("" to emptyMap<String, String>()) +
                (if (pairLimit > 0) emptyList() else singles.map { (name, value) -> suffix(name, value) to mapOf(name to value) }) +
                pairs.map { (a, b) -> suffix(a.first, a.second) + suffix(b.first, b.second) to mapOf(a, b) }
            for ((suffix, overrides) in variants) {
                val id = kind.name.lowercase() + suffix
                val component = CircuitComponent("c", kind, GridPoint(400, 400), attributes = overrides)
                if (overrides.isNotEmpty() && runCatching { registry.attributes(component) }.isFailure) { rejected += id; continue }
                val layout = runCatching { component.layout(registry) }.getOrNull() ?: run { if (overrides.isNotEmpty()) rejected += id; null } ?: continue
                val terminals = layout.inputs + layout.outputs
                if (layout.inputs.isEmpty() || layout.outputs.isEmpty() || terminals.any { it.direction == PortDirection.INOUT }) continue
                if (terminals.map { it.position }.toSet().size != terminals.size) continue
                val parts = mutableListOf(component)
                val operations = StringBuilder()
                val inputs = layout.inputs.mapIndexed { index, terminal -> Triple("in$index", terminal, terminal.width.coerceIn(1, 64)) }
                for ((name, terminal, width) in inputs) {
                    parts += CircuitComponent(name, ComponentKind.INPUT, terminal.position, label = name,
                        attributes = mapOf("width" to "$width"))
                }
                layout.outputs.forEachIndexed { index, terminal ->
                    val width = terminal.width.coerceIn(1, 64)
                    parts += CircuitComponent("out$index", ComponentKind.OUTPUT, terminal.position, label = "out$index",
                        attributes = mapOf("facing" to "west", "width" to "$width"))
                    operations.append("P\tout$index\t${terminal.position.x}\t${terminal.position.y}\t$width\n")
                }
                fun pattern() = inputs.joinToString("") { (name, _, width) ->
                    val text = if (negative) {
                        String(CharArray(width) { when (random.nextInt(10)) { 0, 1 -> 'x'; 2 -> 'E'; 3, 4, 5 -> '1'; else -> '0' } })
                    } else {
                        val mask = if (width == 64) -1L else (1L shl width) - 1
                        java.lang.Long.toBinaryString(random.nextLong() and mask).padStart(width, '0').takeLast(width)
                    }
                    "I\t$name\t$text\n"
                }
                for ((name, _, width) in inputs) operations.append("I\t$name\t${"0".repeat(width)}\n")
                operations.append("Q\n")
                repeat(3) { action ->
                    operations.append("A\tact$action\n")
                    repeat(3) { operations.append(pattern()).append("D\n") }
                }
                val project = CircuitProject(listOf(CircuitDocument("main", parts)), mainCircuitName = "main")
                val text = runCatching { CircCodec.writeProject(project, registry) }.getOrNull() ?: continue
                File(output, "$id.circ").writeText(text)
                File(output, "$id.ops.tsv").writeText(operations.toString())
                written += id
            }
        }
        File(output, "index.txt").writeText(written.joinToString("\n", postfix = "\n"))
        File(output, "rejected.txt").writeText(rejected.joinToString("\n", postfix = if (rejected.isEmpty()) "" else "\n"))
        println("fixtures=${written.size} rejected=${rejected.size}")
    }
}
