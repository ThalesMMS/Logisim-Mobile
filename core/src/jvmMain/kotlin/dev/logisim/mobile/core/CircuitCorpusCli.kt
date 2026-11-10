package dev.logisim.mobile.core

import java.nio.file.Files
import java.nio.file.Path

/** Command-line adapter; example execution itself remains in common Kotlin. */
object CircuitCorpusCli {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--fixture") {
            require(args.size == 4) { "Usage: CircuitCorpusCli --fixture fixture.circ operations.tsv exported.circ" }
            val fixture = Path.of(args[1])
            val xml = Files.readString(fixture)
            val imported = CircCodec.readProject(xml)
            require(imported is CircProjectImportResult.Success) { "Cannot import $fixture: $imported" }
            imported.migrations.forEach { println("D\t${it.rule}\t${it.circuit}\t${it.componentId.orEmpty()}") }
            val example = readFixture(fixture.fileName.toString(), xml, Files.readAllLines(Path.of(args[2])))
            execute(example, Path.of(args[3]), includeStructure = true)
            return
        }
        require(args.size == 2) { "Usage: CircuitCorpusCli example-id exported.circ" }
        val example = requireNotNull(CircuitExamples.find(args[0])) { "Unknown example ${args[0]}." }
        execute(example, Path.of(args[1]))
    }

    private fun execute(example: CircuitExample, export: Path, includeStructure: Boolean = false) {
        val runner = ExampleRunner()
        var session = runner.initialize(example)
        println("F\tcircuits\t${session.project.circuits.size}")
        if (includeStructure) println("F\tmain\t${session.project.mainCircuitName}")
        for (circuit in session.project.circuits.sortedBy { it.name }) {
            for (component in circuit.components.sortedWith(compareBy({ it.position.y }, { it.position.x }, { it.kind.name }))) {
                val inputs = component.inputPorts().toSet()
                val outputs = component.outputPorts().toSet()
                val ports = (inputs + outputs).map { point ->
                    val direction = if (component.kind in setOf(ComponentKind.TUNNEL, ComponentKind.SPLITTER)) 3
                        else (if (point in inputs) 1 else 0) + (if (point in outputs) 2 else 0)
                    "${point.x},${point.y}:${component.portWidths().getValue(point)}:$direction"
                }.sorted().joinToString(";")
                val type = requireNotNull(component.xmlAttributes["name"])
                val key = "${circuit.name}\t$type\t${point(component.position)}"
                println("G\t$key\t$ports")
                if (includeStructure) {
                    if (component.label.isNotEmpty()) println("A\t$key\tlabel\t${component.label}")
                    if (component.kind != ComponentKind.ROM || !MemoryComponents.configuration(component).originalRom) {
                        println("A\t$key\tfacing\t${component.facing}")
                    }
                    for (attribute in listOf("highDuration", "lowDuration")) {
                        component.attributes[attribute]?.let { println("A\t$key\t$attribute\t$it") }
                    }
                }
            }
            if (includeStructure) for (wire in circuit.wires) {
                val ends = listOf(point(wire.from), point(wire.to)).sorted()
                println("W\t${circuit.name}\t${ends[0]}\t${ends[1]}\t1")
            }
        }
        Files.writeString(export, CircCodec.writeProject(session.project))
        for (action in example.actions) {
            session = runner.advance(session, action)
            require(!session.state.oscillating) { "${example.id}/${action.name} did not settle." }
            for ((probe, value) in runner.observe(session).toSortedMap()) println("V\t${action.name}\t$probe\t$value")
        }
    }

    private fun point(point: GridPoint): String = "${point.x},${point.y}"

    private fun readFixture(name: String, xml: String, operations: List<String>): CircuitExample {
        val probes = mutableListOf<ExampleProbe>()
        val startup = mutableListOf<ExampleAction>()
        val actions = mutableListOf<ExampleAction>()
        var action = ""
        var ticks = 0
        val inputs = linkedMapOf<String, String>()
        for (line in operations) {
            val fields = line.split('\t')
            when (fields[0]) {
                "P" -> probes += ExampleProbe(
                    fields[1], GridPoint(fields[2].toInt(), fields[3].toInt()), fields[4].toInt(),
                )
                "A" -> {
                    action = fields[1]
                    ticks = fields[2].toInt()
                    inputs.clear()
                }
                "I" -> inputs[fields[1]] = fields[2]
                "F" -> {
                    val sample = ExampleAction(action, inputs.toMap(), ticks)
                    if (fields[1] == "1") actions += sample
                    else {
                        require(actions.isEmpty()) { "Fixture setup must precede observed actions" }
                        startup += sample
                    }
                }
                else -> error("Unknown fixture operation ${fields[0]}")
            }
        }
        return CircuitExample(name, name, "Original 2.7.1", xml, probes, startup, actions)
    }
}
